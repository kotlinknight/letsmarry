package com.makemymarriage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {"server.address=127.0.0.1", "spring.session.timeout=30m"})
@ActiveProfiles("test")
@Testcontainers
class FoundationIntegrationTests {

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
  }

  @LocalServerPort int port;

  @Autowired Flyway flyway;

  @Autowired JdbcTemplate jdbc;

  @Autowired SessionRepository<? extends Session> sessions;

  @Autowired StringRedisTemplate redis;

  @Autowired Environment environment;
  @Autowired WebApplicationContext webApplicationContext;

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  void healthAndProbesArePublicWithoutExposingDetailsOrCreatingSessions() throws Exception {
    for (String path :
        new String[] {
          "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
        }) {
      var response = get(path);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body())
          .contains("\"status\":\"UP\"")
          .doesNotContain("\"components\"", "\"details\"");
      assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }
  }

  @Test
  void applicationAndManagementRoutesRequireAuthenticationWithoutRedirecting() throws Exception {
    for (String path : new String[] {"/", "/api/v1/weddings", "/actuator/env", "/actuator"}) {
      var response = get(path);
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(response.headers().allValues("Location")).isEmpty();
      assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }
  }

  @Test
  void writesWithoutCsrfAreRejected() throws Exception {
    var response =
        client.send(
            request("/api/v1/weddings")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.headers().allValues("Set-Cookie"))
        .anySatisfy(
            cookie -> {
              assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax");
            });
  }

  @Test
  void scaffoldDoesNotExposeDefaultLoginOrLogout() throws Exception {
    assertThat(get("/login").statusCode()).isEqualTo(401);
    assertThat(get("/logout").statusCode()).isEqualTo(401);
  }

  @Test
  void flywayCreatesAndValidatesIdentityTables() {
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    assertThat(flyway.info().all()).hasSize(5);
    assertThat(
            jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class))
        .containsExactlyInAnyOrder(
            "flyway_schema_history",
            "users",
            "email_verification_tokens",
            "email_outbox",
            "password_reset_tokens");
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
  }

  @Test
  void csrfIsIndependentWhileRegistrationAndLoginStayDisabled() throws Exception {
    var csrfResponse = get("/api/v1/auth/csrf");
    assertThat(csrfResponse.statusCode()).isEqualTo(200);
    assertThat(csrfResponse.body()).contains("X-CSRF-TOKEN");
    assertThat(csrfResponse.headers().firstValue("Cache-Control").orElseThrow())
        .contains("no-store");
    assertThat(csrfResponse.headers().allValues("Set-Cookie"))
        .anySatisfy(
            cookie ->
                assertThat(cookie).contains("SESSION=", "HttpOnly", "SameSite=Lax", "Secure"));
    MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(springSecurity())
        .build()
        .perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
    for (String path :
        new String[] {
          "/api/v1/auth/email/verify",
          "/api/v1/auth/email/verification-requests",
          "/api/v1/auth/password/forgot",
          "/api/v1/auth/password/reset"
        }) {
      MockMvcBuilders.webAppContextSetup(webApplicationContext)
          .apply(springSecurity())
          .build()
          .perform(post(path).with(csrf()).contentType("application/json").content("{}"))
          .andExpect(status().isNotFound());
    }
    MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(springSecurity())
        .build()
        .perform(
            post("/api/v1/auth/login").with(csrf()).contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void sessionsRoundTripThroughRedisAndCanBeInvalidated() {
    assertThat(sessions).isInstanceOf(RedisIndexedSessionRepository.class);
    assertSessionRoundTrip(sessions);
  }

  private <S extends Session> void assertSessionRoundTrip(SessionRepository<S> repository) {
    S session = repository.createSession();
    session.setAttribute("foundation-check", "persisted");
    try {
      repository.save(session);
      assertThat(redis.hasKey("makemymarriage:session:sessions:" + session.getId())).isTrue();
      assertThat(redis.hasKey("spring:session:sessions:" + session.getId())).isFalse();
      S loaded = repository.findById(session.getId());
      assertThat(loaded).isNotNull();
      assertThat(loaded.<String>getAttribute("foundation-check")).isEqualTo("persisted");
      assertThat(loaded.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
    } finally {
      repository.deleteById(session.getId());
    }
    assertThat(repository.findById(session.getId())).isNull();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10));
  }
}
