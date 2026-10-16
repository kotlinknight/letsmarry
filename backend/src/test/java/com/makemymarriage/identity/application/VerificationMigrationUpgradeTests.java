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
class VerificationMigrationUpgradeTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));

  @Test
  void v2PreservesAnExistingV1UnverifiedAccount() throws Exception {
    Flyway v1 = flywayAt("1");
    v1.migrate();
    UUID userId = UUID.randomUUID();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var insert =
            connection.prepareStatement(
                "insert into users (id, email, password_hash, display_name, status)"
                    + " values (?, ?, ?, ?, ?)")) {
      insert.setObject(1, userId);
      insert.setString(2, "retained@example.com");
      insert.setString(3, "existing-hash");
      insert.setString(4, "Existing User");
      insert.setString(5, "ACTIVE");
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }

    Flyway v2 = flywayAt("2");
    v2.migrate();
    assertThat(v2.validateWithResult().validationSuccessful).isTrue();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var select =
            connection.prepareStatement(
                "select email, email_verified_at, status from users where id = ?")) {
      select.setObject(1, userId);
      try (var rows = select.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("email")).isEqualTo("retained@example.com");
        assertThat(rows.getObject("email_verified_at")).isNull();
        assertThat(rows.getString("status")).isEqualTo("ACTIVE");
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
