package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@Testcontainers
class RegistrationTokenFailureIntegrationTests {
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

  @Autowired RegistrationService registration;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean VerificationTokenSecrets secrets;

  @Test
  void tokenCreationFailureRollsBackNewUser() {
    when(secrets.generate()).thenThrow(new IllegalStateException("planned token failure"));
    String email = "token-failure-" + UUID.randomUUID() + "@example.com";

    assertThatThrownBy(
            () ->
                registration.register(
                    new RegistrationCommand("Rollback", email, "SecurePassword123!")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from email_verification_tokens", Integer.class))
        .isZero();
  }
}
