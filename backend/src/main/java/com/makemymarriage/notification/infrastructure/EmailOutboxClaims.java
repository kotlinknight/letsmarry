package com.makemymarriage.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "app.email-outbox.enabled", havingValue = "true")
public class EmailOutboxClaims {
  private static final int MAX_ATTEMPTS = 5;
  private static final int LEASE_SECONDS = 120;

  private final JdbcTemplate jdbc;

  public EmailOutboxClaims(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public List<ClaimedEmail> claim(int batchSize) {
    if (batchSize < 1 || batchSize > 5) {
      throw new IllegalArgumentException("Outbox batch size must be between 1 and 5");
    }
    expireEligibleRows();
    failExhaustedLeases();
    UUID claimId = UUID.randomUUID();
    String sql =
        """
        WITH candidates AS (
            SELECT id FROM email_outbox
            WHERE ((status = 'PENDING' AND next_attempt_at <= now())
                OR (status = 'PROCESSING' AND lease_until <= now()))
              AND expires_at > now() AND attempt_count < ?
            ORDER BY next_attempt_at, id
            LIMIT ? FOR UPDATE SKIP LOCKED
        )
        UPDATE email_outbox AS o
        SET status = 'PROCESSING', claim_id = ?,
            lease_until = now() + (? * interval '1 second'),
            attempt_count = o.attempt_count + 1, failure_code = NULL
        FROM candidates AS c WHERE o.id = c.id
        RETURNING o.id, o.purpose_id, o.recipient_email, o.template_code, o.encrypted_payload,
                  o.nonce, o.key_version, o.expires_at, o.attempt_count
        """;
    return jdbc.query(
        sql,
        (rs, row) ->
            new ClaimedEmail(
                rs.getObject("id", UUID.class),
                claimId,
                rs.getObject("purpose_id", UUID.class),
                rs.getString("recipient_email"),
                rs.getString("template_code"),
                rs.getBytes("encrypted_payload"),
                rs.getBytes("nonce"),
                rs.getString("key_version"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("attempt_count")),
        MAX_ATTEMPTS,
        batchSize,
        claimId,
        LEASE_SECONDS);
  }

  @Transactional
  public boolean markSent(ClaimedEmail claim, String providerMessageId) {
    if (providerMessageId != null && providerMessageId.length() > 255) {
      providerMessageId = null;
    }
    return jdbc.update(
            """
            UPDATE email_outbox SET status = 'SENT', sent_at = now(),
                encrypted_payload = NULL, nonce = NULL, key_version = NULL,
                claim_id = NULL, lease_until = NULL, provider_message_id = ?, failure_code = NULL
            WHERE id = ? AND claim_id = ? AND status = 'PROCESSING' AND lease_until > now()
            """,
            providerMessageId,
            claim.id(),
            claim.claimId())
        == 1;
  }

  @Transactional
  public boolean retry(ClaimedEmail claim, int delaySeconds, String safeCode) {
    return jdbc.update(
            """
            UPDATE email_outbox SET status = 'PENDING',
                next_attempt_at = now() + (? * interval '1 second'),
                claim_id = NULL, lease_until = NULL, failure_code = ?
            WHERE id = ? AND claim_id = ? AND status = 'PROCESSING' AND lease_until > now()
            """,
            delaySeconds,
            safeCode,
            claim.id(),
            claim.claimId())
        == 1;
  }

  @Transactional
  public boolean deferForMissingKey(ClaimedEmail claim) {
    return jdbc.update(
            """
            UPDATE email_outbox SET status = 'PENDING', attempt_count = attempt_count - 1,
                next_attempt_at = now() + interval '5 minutes',
                claim_id = NULL, lease_until = NULL, failure_code = 'KEY_UNAVAILABLE'
            WHERE id = ? AND claim_id = ? AND status = 'PROCESSING' AND lease_until > now()
            """,
            claim.id(),
            claim.claimId())
        == 1;
  }

  @Transactional
  public boolean fail(ClaimedEmail claim, String safeCode) {
    return jdbc.update(
            """
            UPDATE email_outbox SET status = 'FAILED',
                encrypted_payload = NULL, nonce = NULL, key_version = NULL,
                claim_id = NULL, lease_until = NULL, failure_code = ?
            WHERE id = ? AND claim_id = ? AND status = 'PROCESSING' AND lease_until > now()
            """,
            safeCode,
            claim.id(),
            claim.claimId())
        == 1;
  }

  @Transactional
  public boolean expire(ClaimedEmail claim) {
    return jdbc.update(
            """
            UPDATE email_outbox SET status = 'EXPIRED',
                encrypted_payload = NULL, nonce = NULL, key_version = NULL,
                claim_id = NULL, lease_until = NULL, failure_code = 'JOB_EXPIRED'
            WHERE id = ? AND claim_id = ? AND status = 'PROCESSING' AND lease_until > now()
            """,
            claim.id(),
            claim.claimId())
        == 1;
  }

  @Transactional
  public void expireEligibleRows() {
    jdbc.update(
        """
        WITH expired AS (
            SELECT id FROM email_outbox
            WHERE status IN ('PENDING', 'PROCESSING') AND expires_at <= now()
              AND (status = 'PENDING' OR lease_until <= now())
            ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
        )
        UPDATE email_outbox SET status = 'EXPIRED',
            encrypted_payload = NULL, nonce = NULL, key_version = NULL,
            claim_id = NULL, lease_until = NULL, failure_code = 'JOB_EXPIRED'
        FROM expired WHERE email_outbox.id = expired.id
        """);
  }

  @Transactional
  public void failExhaustedLeases() {
    jdbc.update(
        """
        WITH exhausted AS (
            SELECT id FROM email_outbox
            WHERE status = 'PROCESSING' AND lease_until <= now()
              AND attempt_count >= 5 AND expires_at > now()
            ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
        )
        UPDATE email_outbox SET status = 'FAILED',
            encrypted_payload = NULL, nonce = NULL, key_version = NULL,
            claim_id = NULL, lease_until = NULL, failure_code = 'ATTEMPTS_EXHAUSTED'
        FROM exhausted WHERE email_outbox.id = exhausted.id
        """);
  }

  public static int maximumAttempts() {
    return MAX_ATTEMPTS;
  }
}
