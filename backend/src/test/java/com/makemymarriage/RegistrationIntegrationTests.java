package com.makemymarriage;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
      "app.registration.enabled=true",
      "app.email-outbox.enabled=true"
    })
@ActiveProfiles("test")
@Testcontainers
class RegistrationIntegrationTests {
  private static final String TEST_KEYS = testKeys();

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
    properties.add("app.email-outbox.active-key-id", () -> "v1");
    properties.add("app.email-outbox.keys", () -> TEST_KEYS);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void registersUnverifiedAccountWithUuidV7HashAndNoAuthenticatedAccess() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    String email = uniqueEmail();

    var response =
        register(
            client, csrf, "  Anjali  ", "  " + email.toUpperCase() + "  ", "SecurePassword123!");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location")).isEmpty();
    assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
    assertThat((String) JsonPath.read(response.body(), "$.email")).isEqualTo(email);
    assertThat((String) JsonPath.read(response.body(), "$.displayName")).isEqualTo("Anjali");
    assertThat((Boolean) JsonPath.read(response.body(), "$.emailVerified")).isFalse();
    assertThat(response.body()).doesNotContain("password", "passwordHash", "SecurePassword123!");

    UUID id = UUID.fromString(JsonPath.read(response.body(), "$.id"));
    assertThat(id.version()).isEqualTo(7);
    var row =
        jdbc.queryForMap(
            "select email, password_hash, display_name, phone, status, email_verified_at,"
                + " created_at, updated_at, version from users where id = ?",
            id);
    assertThat(row.get("email")).isEqualTo(email);
    assertThat(row.get("display_name")).isEqualTo("Anjali");
    assertThat(row.get("password_hash"))
        .asString()
        .startsWith("{pbkdf2@SpringSecurity_v5_8}")
        .doesNotContain("SecurePassword123!");
    assertThat(passwordEncoder.matches("SecurePassword123!", row.get("password_hash").toString()))
        .isTrue();
    assertThat(passwordEncoder.matches("wrong password", row.get("password_hash").toString()))
        .isFalse();
    assertThat(row.get("phone")).isNull();
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(row.get("email_verified_at")).isNull();
    assertThat(row.get("created_at")).isNotNull();
    assertThat(row.get("updated_at")).isNotNull();
    assertThat(row.get("version")).isEqualTo(0L);
    assertThat(get(client, "/api/v1/weddings").statusCode()).isEqualTo(401);
  }

  @Test
  void duplicateEmailIsCaseInsensitiveAndLeavesOneRow() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    String email = uniqueEmail();
    assertThat(register(client, csrf, "Anjali", email, "SecurePassword123!").statusCode())
        .isEqualTo(201);

    var duplicate = register(client, csrf, "Another", email.toUpperCase(), "SecurePassword123!");

    assertProblem(duplicate, 409, "EMAIL_ALREADY_REGISTERED");
    assertThat(countUsers(email)).isEqualTo(1);
  }

  @Test
  void concurrentRegistrationReliesOnTheDatabaseUniqueIndex() throws Exception {
    HttpClient firstClient = client();
    HttpClient secondClient = client();
    Csrf firstCsrf = csrf(firstClient);
    Csrf secondCsrf = csrf(secondClient);
    String email = uniqueEmail();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          CompletableFuture.supplyAsync(
              () -> registerUnchecked(firstClient, firstCsrf, "Anjali", email), executor);
      var second =
          CompletableFuture.supplyAsync(
              () -> registerUnchecked(secondClient, secondCsrf, "Anjali", email.toUpperCase()),
              executor);
      assertThat(first.join().statusCode() + second.join().statusCode()).isEqualTo(201 + 409);
    }
    assertThat(countUsers(email)).isEqualTo(1);
  }

  @Test
  void invalidRequestsReturnProblemDetailsAndDoNotInsertUsers() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    String email = uniqueEmail();

    var missingName = register(client, csrf, " ", email, "SecurePassword123!");
    assertProblem(missingName, 422, "VALIDATION_ERROR");
    assertThat(missingName.body()).contains("displayName");
    var shortPassword = register(client, csrf, "Anjali", email, "short");
    assertProblem(shortPassword, 422, "VALIDATION_ERROR");
    assertThat(shortPassword.body()).contains("password");
    var malformed = post(client, csrf, "{not-json");
    assertProblem(malformed, 400, "MALFORMED_JSON");
    assertThat(countUsers(email)).isZero();
  }

  @Test
  void anonymousRegistrationRequiresTheSessionCookieAndCsrfHeader() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    assertThat(csrf.headerName()).isEqualTo("X-CSRF-TOKEN");

    var missingToken = post(client, null, payload("Anjali", uniqueEmail(), "SecurePassword123!"));
    assertProblem(missingToken, 403, "CSRF_INVALID");
    var valid = register(client, csrf, "Anjali", uniqueEmail(), "SecurePassword123!");
    assertThat(valid.statusCode()).isEqualTo(201);

    HttpClient differentClient = client();
    var wrongSession =
        post(differentClient, csrf, payload("Anjali", uniqueEmail(), "SecurePassword123!"));
    assertProblem(wrongSession, 403, "CSRF_INVALID");
  }

  private HttpClient client() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  private Csrf csrf(HttpClient client) throws Exception {
    var response = get(client, "/api/v1/auth/csrf");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(response.headers().allValues("Set-Cookie"))
        .anySatisfy(cookie -> assertThat(cookie).contains("HttpOnly", "SameSite=Lax"));
    return new Csrf(
        JsonPath.read(response.body(), "$.token"), JsonPath.read(response.body(), "$.headerName"));
  }

  private HttpResponse<String> register(
      HttpClient client, Csrf csrf, String name, String email, String password) throws Exception {
    return post(client, csrf, payload(name, email, password));
  }

  private HttpResponse<String> registerUnchecked(
      HttpClient client, Csrf csrf, String name, String email) {
    try {
      return register(client, csrf, name, email, "SecurePassword123!");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private HttpResponse<String> post(HttpClient client, Csrf csrf, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (csrf != null) {
      request.header(csrf.headerName(), csrf.token());
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  private String payload(String name, String email, String password) {
    return """
        {"displayName":"%s","email":"%s","password":"%s"}
        """
        .formatted(name, email, password);
  }

  private int countUsers(String email) {
    return jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
  }

  private void assertProblem(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElseThrow())
        .contains("application/problem+json");
    assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo(code);
    assertThat((String) JsonPath.read(response.body(), "$.correlationId"))
        .isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
  }

  private record Csrf(String token, String headerName) {}

  private static String testKeys() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return "v1:" + Base64.getEncoder().encodeToString(key);
  }
}
