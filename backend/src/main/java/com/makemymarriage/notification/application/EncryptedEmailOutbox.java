package com.makemymarriage.notification.application;

import com.makemymarriage.notification.EmailDraft;
import com.makemymarriage.notification.EmailOutbox;
import com.makemymarriage.notification.domain.EmailOutboxEntry;
import com.makemymarriage.notification.infrastructure.EmailOutboxEntryRepository;
import com.makemymarriage.notification.infrastructure.EmailPayloadCipher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "app.email-outbox.enabled", havingValue = "true")
class EncryptedEmailOutbox implements EmailOutbox {
  private final EmailOutboxEntryRepository entries;
  private final EmailPayloadCipher cipher;
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;

  EncryptedEmailOutbox(
      EmailOutboxEntryRepository entries,
      EmailPayloadCipher cipher,
      ObjectMapper mapper,
      JdbcTemplate jdbc) {
    this.entries = entries;
    this.cipher = cipher;
    this.mapper = mapper;
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID enqueue(EmailDraft draft) {
    validate(draft);
    try {
      if (!mapper.readTree(draft.payloadJson()).isObject()) {
        throw new IllegalArgumentException("Invalid email draft");
      }
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid email draft");
    }
    UUID id = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant expiresAt = draft.expiresAt().truncatedTo(ChronoUnit.MICROS);
    if (!expiresAt.isAfter(now.plusSeconds(1))) {
      throw new IllegalArgumentException("Email job must expire in the future");
    }
    var encrypted =
        cipher.encrypt(
            id, draft.recipientEmail(), draft.templateCode(), expiresAt, draft.payloadJson());
    entries.saveAndFlush(
        new EmailOutboxEntry(
            id,
            draft.recipientEmail(),
            draft.templateCode(),
            encrypted.ciphertext(),
            encrypted.nonce(),
            encrypted.keyVersion(),
            expiresAt,
            now,
            draft.purposeId()));
    return id;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int cancelPending(UUID purposeId) {
    if (purposeId == null) {
      throw new IllegalArgumentException("Purpose ID is required");
    }
    return jdbc.update(
        """
        UPDATE email_outbox SET status = 'EXPIRED', encrypted_payload = NULL,
            nonce = NULL, key_version = NULL, failure_code = 'SUPERSEDED'
        WHERE purpose_id = ? AND status = 'PENDING'
        """,
        purposeId);
  }

  private static void validate(EmailDraft draft) {
    if (draft == null
        || draft.recipientEmail() == null
        || draft.recipientEmail().isBlank()
        || draft.recipientEmail().length() > 320
        || draft.recipientEmail().chars().anyMatch(Character::isISOControl)
        || draft.templateCode() == null
        || !draft.templateCode().matches("[A-Z0-9_]{1,100}")
        || draft.payloadJson() == null
        || draft.payloadJson().isBlank()
        || draft.payloadJson().getBytes(StandardCharsets.UTF_8).length > 65_536
        || draft.expiresAt() == null) {
      throw new IllegalArgumentException("Invalid email draft");
    }
  }
}
