package com.makemymarriage.notification;

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
class OutboxPurposeMigrationUpgradeTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));

  @Test
  void v4PreservesPendingOutboxJobs() throws Exception {
    flywayAt("3").migrate();
    UUID id = UUID.randomUUID();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var insert =
            connection.prepareStatement(
                "insert into email_outbox (id, recipient_email, template_code, encrypted_payload,"
                    + " nonce, key_version, expires_at) values (?, 'legacy@example.com',"
                    + " 'VERIFY_EMAIL', ?, ?, 'v1', now() + interval '1 day')")) {
      insert.setObject(1, id);
      insert.setBytes(2, new byte[] {1, 2, 3});
      insert.setBytes(3, new byte[12]);
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
    Flyway v4 = flywayAt("4");
    v4.migrate();
    assertThat(v4.validateWithResult().validationSuccessful).isTrue();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var select =
            connection.prepareStatement(
                "select status, purpose_id, encrypted_payload from email_outbox where id = ?")) {
      select.setObject(1, id);
      try (var rows = select.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("status")).isEqualTo("PENDING");
        assertThat(rows.getObject("purpose_id")).isNull();
        assertThat(rows.getBytes("encrypted_payload")).containsExactly(1, 2, 3);
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
