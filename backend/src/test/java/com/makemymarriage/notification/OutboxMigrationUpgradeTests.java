package com.makemymarriage.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class OutboxMigrationUpgradeTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));

  @Test
  void v3PreservesExistingUsersAndVerificationTokens() throws Exception {
    flywayAt("2").migrate();
    UUID userId = UUID.randomUUID();
    UUID tokenId = UUID.randomUUID();
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (var user =
          connection.prepareStatement(
              "insert into users (id, email, password_hash, display_name, status)"
                  + " values (?, ?, ?, ?, ?)")) {
        user.setObject(1, userId);
        user.setString(2, "existing@example.com");
        user.setString(3, "existing-hash");
        user.setString(4, "Existing User");
        user.setString(5, "ACTIVE");
        user.executeUpdate();
      }
      try (var token =
          connection.prepareStatement(
              "insert into email_verification_tokens"
                  + " (id, user_id, token_hash, expires_at) values (?, ?, ?, ?)")) {
        token.setObject(1, tokenId);
        token.setObject(2, userId);
        token.setBytes(3, new byte[32]);
        token.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(3600)));
        token.executeUpdate();
      }
    }

    Flyway v3 = flywayAt("3");
    v3.migrate();
    assertThat(v3.validateWithResult().validationSuccessful).isTrue();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var users = connection.prepareStatement("select count(*) from users where id = ?");
        var tokens =
            connection.prepareStatement(
                "select count(*) from email_verification_tokens where id = ?")) {
      users.setObject(1, userId);
      tokens.setObject(1, tokenId);
      try (var rows = users.executeQuery()) {
        rows.next();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
      try (var rows = tokens.executeQuery()) {
        rows.next();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
    }
  }

  private Flyway flywayAt(String version) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target(version)
        .load();
  }
}
