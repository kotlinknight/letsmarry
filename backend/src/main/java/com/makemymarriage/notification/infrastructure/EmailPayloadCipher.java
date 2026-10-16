package com.makemymarriage.notification.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EmailPayloadCipher {
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final Map<String, SecretKeySpec> keys;
  private final String activeKeyId;
  private final SecureRandom random = new SecureRandom();

  public EmailPayloadCipher(String activeKeyId, String configuredKeys) {
    this.activeKeyId = activeKeyId;
    this.keys = parseKeys(configuredKeys);
    if (activeKeyId == null || !keys.containsKey(activeKeyId)) {
      throw new IllegalStateException("Active email outbox encryption key is unavailable");
    }
  }

  public EncryptedPayload encrypt(
      UUID id, String recipient, String templateCode, Instant expiresAt, String payloadJson) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(id, recipient, templateCode, expiresAt, activeKeyId));
      return new EncryptedPayload(
          cipher.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8)), nonce, activeKeyId);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Email outbox encryption failed", exception);
    }
  }

  public String decrypt(
      UUID id,
      String recipient,
      String templateCode,
      Instant expiresAt,
      byte[] ciphertext,
      byte[] nonce,
      String keyVersion) {
    SecretKeySpec key = keys.get(keyVersion);
    if (key == null) {
      throw new KeyUnavailableException();
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(id, recipient, templateCode, expiresAt, keyVersion));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new PayloadAuthenticationException();
    }
  }

  private static byte[] aad(
      UUID id, String recipient, String templateCode, Instant expiresAt, String keyVersion) {
    try (var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes)) {
      out.writeLong(id.getMostSignificantBits());
      out.writeLong(id.getLeastSignificantBits());
      out.writeUTF(recipient);
      out.writeUTF(templateCode);
      out.writeLong(expiresAt.getEpochSecond());
      out.writeInt(expiresAt.getNano() / 1_000);
      out.writeUTF(keyVersion);
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Email outbox metadata encoding failed", exception);
    }
  }

  private static Map<String, SecretKeySpec> parseKeys(String configuredKeys) {
    Map<String, SecretKeySpec> parsed = new HashMap<>();
    if (configuredKeys == null || configuredKeys.isBlank()) {
      return Map.of();
    }
    for (String entry : configuredKeys.split(",", -1)) {
      int separator = entry.indexOf(':');
      if (separator < 1 || separator == entry.length() - 1) {
        throw new IllegalStateException("Invalid email outbox key configuration");
      }
      String keyId = entry.substring(0, separator);
      if (!keyId.matches("[A-Za-z0-9_-]{1,32}") || parsed.containsKey(keyId)) {
        throw new IllegalStateException("Invalid email outbox key configuration");
      }
      try {
        byte[] raw = Base64.getDecoder().decode(entry.substring(separator + 1));
        if (raw.length != 32) {
          throw new IllegalStateException("Invalid email outbox key configuration");
        }
        parsed.put(keyId, new SecretKeySpec(raw, "AES"));
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("Invalid email outbox key configuration");
      }
    }
    return Map.copyOf(parsed);
  }

  public record EncryptedPayload(byte[] ciphertext, byte[] nonce, String keyVersion) {
    @Override
    public String toString() {
      return "EncryptedPayload[keyVersion=" + keyVersion + "]";
    }
  }

  public static class KeyUnavailableException extends RuntimeException {
    public KeyUnavailableException() {
      super("Email outbox decryption key is unavailable");
    }
  }

  public static class PayloadAuthenticationException extends RuntimeException {
    public PayloadAuthenticationException() {
      super("Email outbox payload authentication failed");
    }
  }
}
