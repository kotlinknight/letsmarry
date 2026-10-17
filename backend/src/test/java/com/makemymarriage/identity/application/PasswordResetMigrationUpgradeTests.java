package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PasswordResetMigrationUpgradeTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));

  @Test
  void v5PreservesV4UsersVerificationTokensAndOutboxRows() throws Exception {
    flywayAt("4").migrate();
    UUID userId = UUID.randomUUID();
    UUID verificationTokenId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (var insert =
          connection.prepareStatement(
              "insert into users (id, email, password_hash, display_name, status, email_verified_at)"
                  + " values (?, ?, ?, ?, ?, now())")) {
        insert.setObject(1, userId);
        insert.setString(2, "retained-reset@example.com");
        insert.setString(3, "existing-hash");
        insert.setString(4, "Existing User");
        insert.setString(5, "ACTIVE");
        assertThat(insert.executeUpdate()).isEqualTo(1);
      }
      try (var insert =
          connection.prepareStatement(
              "insert into email_verification_tokens (id, user_id, token_hash, expires_at,"
                  + " consumed_at) values (?, ?, ?, now() + interval '1 day', now())")) {
        insert.setObject(1, verificationTokenId);
        insert.setObject(2, userId);
        insert.setBytes(3, new byte[32]);
        assertThat(insert.executeUpdate()).isEqualTo(1);
      }
      try (var insert =
          connection.prepareStatement(
              "insert into email_outbox (id, recipient_email, template_code, status,"
                  + " expires_at, attempt_count, failure_code, purpose_id)"
                  + " values (?, ?, 'VERIFY_EMAIL', 'FAILED', now() + interval '1 day',"
                  + " 1, 'SENDER_PERMANENT', ?)")) {
        insert.setObject(1, outboxId);
        insert.setString(2, "retained-reset@example.com");
        insert.setObject(3, verificationTokenId);
        assertThat(insert.executeUpdate()).isEqualTo(1);
      }
    }

    Flyway v5 = flywayAt("5");
    v5.migrate();
    assertThat(v5.validateWithResult().validationSuccessful).isTrue();
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (var select =
          connection.prepareStatement(
              "select email, password_hash, status, email_verified_at, auth_version"
                  + " from users where id = ?")) {
        select.setObject(1, userId);
        try (var rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("email")).isEqualTo("retained-reset@example.com");
          assertThat(rows.getString("password_hash")).isEqualTo("existing-hash");
          assertThat(rows.getString("status")).isEqualTo("ACTIVE");
          assertThat(rows.getObject("email_verified_at")).isNotNull();
          assertThat(rows.getLong("auth_version")).isZero();
        }
      }
      try (var select =
          connection.prepareStatement(
              "select count(*) from email_verification_tokens where id = ? and user_id = ?")) {
        select.setObject(1, verificationTokenId);
        select.setObject(2, userId);
        try (var rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
      }
      try (var select =
          connection.prepareStatement("select status, purpose_id from email_outbox where id = ?")) {
        select.setObject(1, outboxId);
        try (var rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("status")).isEqualTo("FAILED");
          assertThat(rows.getObject("purpose_id")).isEqualTo(verificationTokenId);
        }
      }
      try (var select = connection.prepareStatement("select count(*) from password_reset_tokens")) {
        try (var rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isZero();
        }
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
