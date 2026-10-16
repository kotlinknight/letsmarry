package com.makemymarriage.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.makemymarriage.notification.application.OutboxWorker;
import com.makemymarriage.notification.infrastructure.ClaimedEmail;
import com.makemymarriage.notification.infrastructure.EmailOutboxClaims;
import com.makemymarriage.notification.infrastructure.EmailPayloadCipher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = "app.email-outbox.enabled=true")
@Testcontainers
class EmailOutboxIntegrationTests {
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

  @Autowired EmailOutbox outbox;
  @Autowired EmailOutboxClaims claims;
  @Autowired EmailPayloadCipher cipher;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired ApplicationContext applicationContext;

  @Test
  void v3ValidatesAndEnqueueRequiresAndJoinsCallerTransaction() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '3' and success",
                Integer.class))
        .isEqualTo(1);
    assertThat(applicationContext.getBeansOfType(OutboxWorker.class)).isEmpty();
    EmailDraft draft = draft();
    assertThatThrownBy(() -> outbox.enqueue(draft))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    assertThatThrownBy(
            () ->
                transaction()
                    .execute(
                        status ->
                            outbox.enqueue(
                                new EmailDraft(
                                    draft.recipientEmail(),
                                    draft.templateCode(),
                                    "not-json",
                                    draft.expiresAt()))))
        .isInstanceOf(IllegalArgumentException.class);

    UUID id = enqueue(draft);
    var row =
        jdbc.queryForMap(
            "select encrypted_payload, nonce, key_version, status, attempt_count"
                + " from email_outbox where id = ?",
            id);
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("attempt_count")).isEqualTo(0);
    assertThat((byte[]) row.get("nonce")).hasSize(12);
    assertThat(row.get("key_version")).isEqualTo("v1");
    assertThat((byte[]) row.get("encrypted_payload")).isNotEmpty();
    assertThat(new String((byte[]) row.get("encrypted_payload")))
        .doesNotContain("raw-token-example");

    long before = countRows();
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          outbox.enqueue(draft());
                          throw new PlannedRollback();
                        }))
        .isInstanceOf(PlannedRollback.class);
    assertThat(countRows()).isEqualTo(before);
    failClaimFor(id);
  }

  @Test
  void workerDeliversWithStableIdAndPurgesSensitivePayload() {
    UUID id = enqueue(draft());
    AtomicInteger sends = new AtomicInteger();
    EmailSender sender =
        delivery -> {
          sends.incrementAndGet();
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(delivery.deliveryId()).isEqualTo(id);
          assertThat(delivery.payloadJson()).contains("raw-token-example");
          assertThat(delivery.toString()).doesNotContain("raw-token-example");
          return new EmailSender.SendReceipt("provider-123");
        };

    worker(sender).runOnce();

    assertThat(sends).hasValue(1);
    var row =
        jdbc.queryForMap(
            "select status, attempt_count, encrypted_payload, nonce, key_version,"
                + " provider_message_id, sent_at from email_outbox where id = ?",
            id);
    assertThat(row.get("status")).isEqualTo("SENT");
    assertThat(row.get("attempt_count")).isEqualTo(1);
    assertThat(row.get("provider_message_id")).isEqualTo("provider-123");
    assertThat(row.get("sent_at")).isNotNull();
    assertThat(row.get("encrypted_payload")).isNull();
    assertThat(row.get("nonce")).isNull();
    assertThat(row.get("key_version")).isNull();
  }

  @Test
  void temporaryFailureBacksOffAndStopsAfterFiveAttempts() {
    UUID id = enqueue(draft());
    OutboxWorker worker =
        worker(
            delivery -> {
              throw new EmailSendException(false);
            });

    for (int attempt = 1; attempt <= 5; attempt++) {
      worker.runOnce();
      var row =
          jdbc.queryForMap(
              "select status, attempt_count, next_attempt_at, encrypted_payload, failure_code"
                  + " from email_outbox where id = ?",
              id);
      assertThat(row.get("attempt_count")).isEqualTo(attempt);
      if (attempt < 5) {
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("failure_code")).isEqualTo("SENDER_TEMPORARY");
        Instant next = ((java.sql.Timestamp) row.get("next_attempt_at")).toInstant();
        assertThat(next).isAfter(Instant.now().plusSeconds((60L << (attempt - 1)) - 5));
        jdbc.update("update email_outbox set next_attempt_at = now() where id = ?", id);
      } else {
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(row.get("encrypted_payload")).isNull();
      }
    }
  }

  @Test
  void permanentFailureAndTamperingBecomeTerminalWithoutLeakingPayload() {
    UUID permanentId = enqueue(draft());
    worker(
            delivery -> {
              throw new EmailSendException(true);
            })
        .runOnce();
    assertThat(statusOf(permanentId)).isEqualTo("FAILED");
    assertThat(payloadOf(permanentId)).isNull();
    assertThat(failureOf(permanentId)).isEqualTo("SENDER_PERMANENT");

    UUID tamperedId = enqueue(draft());
    byte[] ciphertext = payloadOf(tamperedId);
    ciphertext[0] ^= 1;
    jdbc.update(
        "update email_outbox set encrypted_payload = ? where id = ?", ciphertext, tamperedId);
    AtomicInteger sends = new AtomicInteger();
    worker(
            delivery -> {
              sends.incrementAndGet();
              return new EmailSender.SendReceipt(null);
            })
        .runOnce();
    assertThat(sends).hasValue(0);
    assertThat(statusOf(tamperedId)).isEqualTo("FAILED");
    assertThat(failureOf(tamperedId)).isEqualTo("PAYLOAD_AUTH_FAILED");
    assertThat(payloadOf(tamperedId)).isNull();
  }

  @Test
  void twoWorkersClaimDifferentRows() throws Exception {
    UUID firstId = enqueue(draft());
    UUID secondId = enqueue(draft());
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = CompletableFuture.supplyAsync(() -> claimAfter(start), executor);
      var second = CompletableFuture.supplyAsync(() -> claimAfter(start), executor);
      start.countDown();
      ClaimedEmail firstClaim = first.join().getFirst();
      ClaimedEmail secondClaim = second.join().getFirst();
      assertThat(firstClaim.id()).isNotEqualTo(secondClaim.id());
      assertThat(List.of(firstClaim.id(), secondClaim.id()))
          .containsExactlyInAnyOrder(firstId, secondId);
      assertThat(claims.fail(firstClaim, "TEST_FINISHED")).isTrue();
      assertThat(claims.fail(secondClaim, "TEST_FINISHED")).isTrue();
    }
  }

  @Test
  void crashedWorkerLeaseRecoversAndStaleClaimCannotComplete() {
    UUID id = enqueue(draft());
    ClaimedEmail first = claims.claim(1).getFirst();
    jdbc.update(
        "update email_outbox set lease_until = now() - interval '1 second' where id = ?", id);
    ClaimedEmail recovered = claims.claim(1).getFirst();

    assertThat(recovered.id()).isEqualTo(id);
    assertThat(recovered.claimId()).isNotEqualTo(first.claimId());
    assertThat(recovered.attemptCount()).isEqualTo(2);
    assertThat(claims.markSent(first, "stale-provider-id")).isFalse();
    assertThat(claims.markSent(recovered, "accepted-id")).isTrue();
    assertThat(statusOf(id)).isEqualTo("SENT");
  }

  @Test
  void exhaustedAbandonedLeaseBecomesFailedAndPurgesPayload() {
    UUID id = enqueue(draft());
    for (int attempt = 1; attempt <= 5; attempt++) {
      ClaimedEmail claim = claims.claim(1).getFirst();
      assertThat(claim.attemptCount()).isEqualTo(attempt);
      jdbc.update(
          "update email_outbox set lease_until = now() - interval '1 second' where id = ?", id);
    }

    assertThat(claims.claim(1)).isEmpty();
    assertThat(statusOf(id)).isEqualTo("FAILED");
    assertThat(failureOf(id)).isEqualTo("ATTEMPTS_EXHAUSTED");
    assertThat(payloadOf(id)).isNull();
  }

  @Test
  void deliveryMayRepeatAfterAcceptanceButUsesSameDeliveryId() {
    UUID id = enqueue(draft());
    ClaimedEmail first = claims.claim(1).getFirst();
    AtomicInteger sends = new AtomicInteger();
    EmailSender sender =
        delivery -> {
          assertThat(delivery.deliveryId()).isEqualTo(id);
          sends.incrementAndGet();
          return new EmailSender.SendReceipt(null);
        };
    sender.send(decryptedDelivery(first));
    jdbc.update(
        "update email_outbox set lease_until = now() - interval '1 second' where id = ?", id);

    worker(sender).runOnce();

    assertThat(sends).hasValue(2);
    assertThat(claims.markSent(first, null)).isFalse();
    assertThat(statusOf(id)).isEqualTo("SENT");
  }

  @Test
  void expiredJobsArePurgedBeforeDelivery() {
    UUID id = enqueue(draft());
    jdbc.update(
        "update email_outbox set created_at = now() - interval '2 days',"
            + " expires_at = now() - interval '1 day' where id = ?",
        id);
    AtomicInteger sends = new AtomicInteger();

    worker(
            delivery -> {
              sends.incrementAndGet();
              return new EmailSender.SendReceipt(null);
            })
        .runOnce();

    assertThat(sends).hasValue(0);
    assertThat(statusOf(id)).isEqualTo("EXPIRED");
    assertThat(payloadOf(id)).isNull();
  }

  @Test
  void missingOldKeyDefersWithoutConsumingAnAttemptOrPurging() {
    UUID id = enqueue(draft());
    byte[] differentKey = new byte[32];
    differentKey[0] = 1;
    EmailPayloadCipher withoutOldKey =
        new EmailPayloadCipher("v2", "v2:" + Base64.getEncoder().encodeToString(differentKey));
    AtomicInteger sends = new AtomicInteger();

    new OutboxWorker(
            claims,
            withoutOldKey,
            delivery -> {
              sends.incrementAndGet();
              return new EmailSender.SendReceipt(null);
            },
            (template, purpose) -> true,
            Clock.systemUTC())
        .runOnce();

    assertThat(sends).hasValue(0);
    assertThat(statusOf(id)).isEqualTo("PENDING");
    assertThat(failureOf(id)).isEqualTo("KEY_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "select attempt_count from email_outbox where id = ?", Integer.class, id))
        .isZero();
    assertThat(payloadOf(id)).isNotNull();
    jdbc.update(
        "update email_outbox set created_at = now() - interval '2 days',"
            + " expires_at = now() - interval '1 day' where id = ?",
        id);
    claims.expireEligibleRows();
    assertThat(payloadOf(id)).isNull();
  }

  private EmailDraft draft() {
    return new EmailDraft(
        "recipient-" + UUID.randomUUID() + "@example.com",
        "VERIFY_EMAIL",
        "{\"token\":\"raw-token-example\"}",
        Instant.now().plusSeconds(3600));
  }

  private UUID enqueue(EmailDraft draft) {
    return transaction().execute(status -> outbox.enqueue(draft));
  }

  private TransactionTemplate transaction() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    return template;
  }

  private OutboxWorker worker(EmailSender sender) {
    return new OutboxWorker(claims, cipher, sender, (template, purpose) -> true, Clock.systemUTC());
  }

  private EmailDelivery decryptedDelivery(ClaimedEmail claim) {
    return new EmailDelivery(
        claim.id(),
        claim.recipientEmail(),
        claim.templateCode(),
        cipher.decrypt(
            claim.id(),
            claim.recipientEmail(),
            claim.templateCode(),
            claim.expiresAt(),
            claim.encryptedPayload(),
            claim.nonce(),
            claim.keyVersion()));
  }

  private List<ClaimedEmail> claimAfter(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
    return claims.claim(1);
  }

  private void failClaimFor(UUID id) {
    for (ClaimedEmail claim : claims.claim(5)) {
      if (claim.id().equals(id)) {
        assertThat(claims.fail(claim, "TEST_FINISHED")).isTrue();
      }
    }
  }

  private long countRows() {
    return jdbc.queryForObject("select count(*) from email_outbox", Long.class);
  }

  private String statusOf(UUID id) {
    return jdbc.queryForObject("select status from email_outbox where id = ?", String.class, id);
  }

  private byte[] payloadOf(UUID id) {
    return jdbc.queryForObject(
        "select encrypted_payload from email_outbox where id = ?", byte[].class, id);
  }

  private String failureOf(UUID id) {
    return jdbc.queryForObject(
        "select failure_code from email_outbox where id = ?", String.class, id);
  }

  private static String testKeys() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return "v1:" + Base64.getEncoder().encodeToString(key);
  }

  private static class PlannedRollback extends RuntimeException {}
}
