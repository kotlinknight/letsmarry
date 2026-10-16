package com.makemymarriage;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.makemymarriage.identity.application.AccountPrincipal;
import com.makemymarriage.identity.application.LoginRateLimitException;
import com.makemymarriage.identity.application.LoginRateLimiter;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
      "server.address=127.0.0.1",
      "server.servlet.session.cookie.secure=false",
      "app.authentication.enabled=true"
    })
@ActiveProfiles("test")
@Testcontainers
class AuthenticationIntegrationTests {
  private static final String RATE_KEY = randomKey();
  private static final String PASSWORD = "SecurePassword123!";

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2.9")).withExposedPorts(6379);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.data.redis.host", REDIS::getHost);
    properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    properties.add("spring.data.redis.password", () -> "");
    properties.add("app.authentication.rate-limit.key", () -> RATE_KEY);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired SessionRepository<? extends Session> sessions;
  @Autowired StringRedisTemplate redis;
  @Autowired LoginRateLimiter limiter;

  @Test
  void loginRotatesSessionStoresSafePrincipalAndRequiresFreshCsrf() throws Exception {
    String email = uniqueEmail();
    UUID id = insertUser(email, PASSWORD, "ACTIVE", true);
    Browser browser = browser();
    Csrf before = csrf(browser);
    String oldSessionId = sessionId(browser);

    var response = login(browser, before, email, PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat((String) JsonPath.read(response.body(), "$.user.id")).isEqualTo(id.toString());
    assertThat((String) JsonPath.read(response.body(), "$.user.email")).isEqualTo(email);
    assertThat((Boolean) JsonPath.read(response.body(), "$.user.emailVerified")).isTrue();
    assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "SESSION");
    String newSessionId = sessionId(browser);
    assertThat(newSessionId).isNotEqualTo(oldSessionId);
    assertThat(response.headers().allValues("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("SESSION=", "HttpOnly", "SameSite=Lax"));
    assertThat(get(browser, "/api/v1/weddings").statusCode()).isEqualTo(404);
    assertThat(getWithSession(oldSessionId, "/api/v1/weddings").statusCode()).isEqualTo(401);

    Session stored = sessions.findById(newSessionId);
    if (stored == null) {
      stored =
          sessions.findById(
              new String(Base64.getDecoder().decode(newSessionId), StandardCharsets.US_ASCII));
    }
    assertThat(stored).isNotNull();
    assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
    SecurityContext context = stored.getAttribute("SPRING_SECURITY_CONTEXT");
    assertThat(context).isNotNull();
    assertThat(context.getAuthentication().getPrincipal()).isEqualTo(new AccountPrincipal(id));
    assertThat(context.getAuthentication().getCredentials()).isNull();

    assertProblem(login(browser, before, email, PASSWORD), 403, "CSRF_INVALID");
    Csrf after = csrf(browser);
    assertThat(after.token()).isNotEqualTo(before.token());
  }

  @Test
  void unknownWrongUnverifiedAndSuspendedAccountsHaveTheSameFailure() throws Exception {
    String wrongEmail = uniqueEmail();
    String unverifiedEmail = uniqueEmail();
    String suspendedEmail = uniqueEmail();
    String deactivatedEmail = uniqueEmail();
    insertUser(wrongEmail, PASSWORD, "ACTIVE", true);
    insertUser(unverifiedEmail, PASSWORD, "ACTIVE", false);
    insertUser(suspendedEmail, PASSWORD, "SUSPENDED", true);
    insertUser(deactivatedEmail, PASSWORD, "DEACTIVATED", true);

    for (String[] attempt :
        new String[][] {
          {uniqueEmail(), PASSWORD},
          {wrongEmail, "IncorrectPassword123!"},
          {unverifiedEmail, PASSWORD},
          {suspendedEmail, PASSWORD},
          {deactivatedEmail, PASSWORD}
        }) {
      Browser browser = browser();
      var response = login(browser, csrf(browser), attempt[0], attempt[1]);
      assertProblem(response, 401, "INVALID_CREDENTIALS");
      assertThat((String) JsonPath.read(response.body(), "$.detail"))
          .isEqualTo("Invalid credentials.");
      assertThat(get(browser, "/api/v1/weddings").statusCode()).isEqualTo(401);
    }
  }

  @Test
  void loginUsesRegistrationEmailAndUnicodePasswordNormalization() throws Exception {
    String email = uniqueEmail();
    String storedPassword = "Caf\u00e9-and-a-long-password";
    insertUser(email, storedPassword, "ACTIVE", true);
    Browser browser = browser();

    var response =
        login(
            browser,
            csrf(browser),
            "  " + email.toUpperCase() + "  ",
            "Cafe\u0301-and-a-long-password");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat((String) JsonPath.read(response.body(), "$.user.email")).isEqualTo(email);
  }

  @Test
  void separateBrowsersKeepIndependentAuthenticatedSessions() throws Exception {
    String email = uniqueEmail();
    insertUser(email, PASSWORD, "ACTIVE", true);
    Browser first = browser();
    Browser second = browser();

    assertThat(login(first, csrf(first), email, PASSWORD).statusCode()).isEqualTo(200);
    assertThat(login(second, csrf(second), email, PASSWORD).statusCode()).isEqualTo(200);

    assertThat(sessionId(first)).isNotEqualTo(sessionId(second));
    assertThat(get(first, "/api/v1/weddings").statusCode()).isEqualTo(404);
    assertThat(get(second, "/api/v1/weddings").statusCode()).isEqualTo(404);
  }

  @Test
  void malformedInvalidAndMissingCsrfAreRejected() throws Exception {
    Browser browser = browser();
    Csrf csrf = csrf(browser);
    assertProblem(post(browser, csrf, "{broken"), 400, "MALFORMED_JSON");
    assertProblem(
        post(browser, csrf, "{\"email\":\"\",\"password\":\"\"}"), 422, "VALIDATION_ERROR");
    assertProblem(login(browser, null, uniqueEmail(), PASSWORD), 403, "CSRF_INVALID");
  }

  @Test
  void rateLimitCountsUnknownEmailsAndUsesHmacOnlyKeys() throws Exception {
    String email = uniqueEmail();
    Browser browser = browser();
    Csrf csrf = csrf(browser);
    for (int attempt = 0; attempt < 10; attempt++) {
      assertProblem(login(browser, csrf, email, PASSWORD), 401, "INVALID_CREDENTIALS");
    }
    var limited = login(browser, csrf, email, PASSWORD);
    assertProblem(limited, 429, "AUTHENTICATION_RATE_LIMITED");
    assertThat(limited.headers().firstValue("Retry-After").orElseThrow()).matches("[1-9][0-9]*");
    assertThat(redis.keys("authentication:login:*"))
        .allSatisfy(key -> assertThat(key).doesNotContain(email));
  }

  @Test
  void concurrentAttemptsCannotBypassEmailLimit() {
    String email = uniqueEmail();
    String ip = "test-ip-" + UUID.randomUUID();
    try (var executor = Executors.newFixedThreadPool(20)) {
      var attempts =
          java.util.stream.IntStream.range(0, 20)
              .mapToObj(
                  ignored ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            try {
                              limiter.check(email, ip);
                              return true;
                            } catch (LoginRateLimitException exception) {
                              assertThat(exception.isUnavailable()).isFalse();
                              return false;
                            }
                          },
                          executor))
              .toList();
      assertThat(attempts.stream().filter(future -> future.join()).count()).isEqualTo(10);
    }
  }

  @Test
  void directIpLimitAppliesAcrossDifferentEmails() {
    String ip = "test-ip-" + UUID.randomUUID();
    LoginRateLimiter strictIpLimiter = new LoginRateLimiter(redis, randomKey(), 10, 3, 900);
    for (int attempt = 0; attempt < 3; attempt++) {
      strictIpLimiter.check(uniqueEmail(), ip);
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> strictIpLimiter.check(uniqueEmail(), ip))
        .isInstanceOfSatisfying(
            LoginRateLimitException.class,
            exception -> assertThat(exception.isUnavailable()).isFalse());
  }

  @Test
  void currentUserReadsFreshSafeAccountDetailsAndRequiresAuthentication() throws Exception {
    Browser anonymous = browser();
    assertProblem(get(anonymous, "/api/v1/users/me"), 401, "AUTHENTICATION_REQUIRED");

    String email = uniqueEmail();
    UUID id = insertUser(email, PASSWORD, "ACTIVE", true);
    Browser browser = browser();
    assertThat(login(browser, csrf(browser), email, PASSWORD).statusCode()).isEqualTo(200);
    jdbc.update("update users set display_name = ? where id = ?", "Updated Anjali", id);

    var current = get(browser, "/api/v1/users/me");
    assertThat(current.statusCode()).isEqualTo(200);
    assertThat(current.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat((String) JsonPath.read(current.body(), "$.id")).isEqualTo(id.toString());
    assertThat((String) JsonPath.read(current.body(), "$.displayName")).isEqualTo("Updated Anjali");
    assertThat((String) JsonPath.read(current.body(), "$.email")).isEqualTo(email);
    assertThat((Boolean) JsonPath.read(current.body(), "$.emailVerified")).isTrue();
    assertThat((String) JsonPath.read(current.body(), "$.status")).isEqualTo("ACTIVE");
    assertThat(current.body()).doesNotContain("password", "SESSION", "token");
  }

  @Test
  void logoutRevokesOnlyCurrentSessionAndRequiresFreshCsrfForRepeat() throws Exception {
    String email = uniqueEmail();
    insertUser(email, PASSWORD, "ACTIVE", true);
    Browser first = browser();
    Browser second = browser();
    assertThat(login(first, csrf(first), email, PASSWORD).statusCode()).isEqualTo(200);
    assertThat(login(second, csrf(second), email, PASSWORD).statusCode()).isEqualTo(200);
    String firstSessionId = sessionId(first);
    String secondSessionId = sessionId(second);
    Csrf firstToken = csrf(first);

    assertProblem(logout(first, null), 403, "CSRF_INVALID");
    assertThat(get(first, "/api/v1/users/me").statusCode()).isEqualTo(200);
    var loggedOut = logout(first, firstToken);
    assertThat(loggedOut.statusCode()).isEqualTo(204);
    assertThat(loggedOut.body()).isEmpty();
    assertThat(loggedOut.headers().allValues("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("SESSION=", "Max-Age=0"));
    assertThat(sessions.findById(decodeSessionId(firstSessionId))).isNull();
    assertProblem(
        getWithSession(firstSessionId, "/api/v1/users/me"), 401, "AUTHENTICATION_REQUIRED");
    assertThat(get(second, "/api/v1/users/me").statusCode()).isEqualTo(200);
    assertThat(sessions.findById(decodeSessionId(secondSessionId))).isNotNull();

    assertProblem(logout(first, firstToken), 403, "CSRF_INVALID");
    Csrf renewed = csrf(first);
    assertThat(renewed.token()).isNotEqualTo(firstToken.token());
    assertThat(logout(first, renewed).statusCode()).isEqualTo(204);
  }

  @Test
  void expiredSessionCannotRestoreCurrentUser() throws Exception {
    String email = uniqueEmail();
    insertUser(email, PASSWORD, "ACTIVE", true);
    Browser browser = browser();
    assertThat(login(browser, csrf(browser), email, PASSWORD).statusCode()).isEqualTo(200);
    String oldSessionId = sessionId(browser);
    Session stored = sessions.findById(decodeSessionId(oldSessionId));
    assertThat(stored).isNotNull();
    stored.setLastAccessedTime(Instant.now().minus(Duration.ofMinutes(31)));
    saveSession(stored);

    assertProblem(getWithSession(oldSessionId, "/api/v1/users/me"), 401, "AUTHENTICATION_REQUIRED");
  }

  @Test
  void ineligibleOrDeletedAccountRevokesItsExistingSession() throws Exception {
    for (String change : new String[] {"SUSPENDED", "DEACTIVATED", "UNVERIFIED", "DELETED"}) {
      String email = uniqueEmail();
      UUID id = insertUser(email, PASSWORD, "ACTIVE", true);
      Browser browser = browser();
      assertThat(login(browser, csrf(browser), email, PASSWORD).statusCode()).isEqualTo(200);
      String oldSessionId = sessionId(browser);
      switch (change) {
        case "SUSPENDED", "DEACTIVATED" ->
            jdbc.update("update users set status = ? where id = ?", change, id);
        case "UNVERIFIED" ->
            jdbc.update("update users set email_verified_at = null where id = ?", id);
        case "DELETED" -> jdbc.update("delete from users where id = ?", id);
        default -> throw new AssertionError(change);
      }

      String protectedPath = change.equals("SUSPENDED") ? "/api/v1/weddings" : "/api/v1/users/me";
      assertProblem(get(browser, protectedPath), 401, "AUTHENTICATION_REQUIRED");
      assertThat(sessions.findById(decodeSessionId(oldSessionId))).isNull();
      assertProblem(
          getWithSession(oldSessionId, "/api/v1/users/me"), 401, "AUTHENTICATION_REQUIRED");
    }
  }

  @Test
  void unavailableRedisDoesNotGrantAccessFromAnExistingSession() throws Exception {
    String email = uniqueEmail();
    insertUser(email, PASSWORD, "ACTIVE", true);
    Browser browser = browser();
    assertThat(login(browser, csrf(browser), email, PASSWORD).statusCode()).isEqualTo(200);
    Csrf logoutToken = csrf(browser);
    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    try {
      assertProblem(
          get(browser, "/api/v1/users/me"), 503, "AUTHENTICATION_TEMPORARILY_UNAVAILABLE");
      assertProblem(logout(browser, logoutToken), 503, "AUTHENTICATION_TEMPORARILY_UNAVAILABLE");
    } finally {
      REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
    assertThat(REDIS.execInContainer("redis-cli", "ping").getStdout()).contains("PONG");
  }

  @SuppressWarnings("unchecked")
  private void saveSession(Session session) {
    ((SessionRepository<Session>) sessions).save(session);
  }

  private static String decodeSessionId(String cookieValue) {
    return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.US_ASCII);
  }

  private UUID insertUser(String email, String password, String status, boolean verified) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into users (id, email, password_hash, display_name, status, email_verified_at)"
            + " values (?, ?, ?, ?, ?, ?)",
        id,
        email,
        encoder.encode(password),
        "Anjali",
        status,
        verified ? Timestamp.from(Instant.now()) : null);
    return id;
  }

  private Browser browser() {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .cookieHandler(cookies)
            .build();
    return new Browser(http, cookies);
  }

  private Csrf csrf(Browser browser) throws Exception {
    var response = get(browser, "/api/v1/auth/csrf");
    assertThat(response.statusCode()).isEqualTo(200);
    return new Csrf(
        JsonPath.read(response.body(), "$.token"), JsonPath.read(response.body(), "$.headerName"));
  }

  private String sessionId(Browser browser) {
    return browser.cookies().getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("SESSION"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private HttpResponse<String> login(Browser browser, Csrf csrf, String email, String password)
      throws Exception {
    return post(browser, csrf, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
  }

  private HttpResponse<String> post(Browser browser, Csrf csrf, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(uri("/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (csrf != null) request.header(csrf.headerName(), csrf.token());
    return browser.http().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> logout(Browser browser, Csrf csrf) throws Exception {
    var request =
        HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (csrf != null) request.header(csrf.headerName(), csrf.token());
    return browser.http().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(Browser browser, String path) throws Exception {
    return browser
        .http()
        .send(
            HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getWithSession(String sessionId, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", "SESSION=" + sessionId)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String uniqueEmail() {
    return "login-" + UUID.randomUUID() + "@example.com";
  }

  private static String randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private static void assertProblem(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElseThrow())
        .contains("application/problem+json");
    assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo(code);
    assertThat((String) JsonPath.read(response.body(), "$.correlationId"))
        .isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
  }

  private record Browser(HttpClient http, CookieManager cookies) {}

  private record Csrf(String token, String headerName) {}
}
