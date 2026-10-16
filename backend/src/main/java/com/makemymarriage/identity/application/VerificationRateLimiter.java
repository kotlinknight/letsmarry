package com.makemymarriage.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@Profile("(local | test) & !production")
@ConditionalOnProperty(name = "app.verification.rate-limit.enabled", havingValue = "true")
public class VerificationRateLimiter {
  private static final String RESEND_SCRIPT =
      """
      local ip = redis.call('INCR', KEYS[1])
      if ip == 1 then redis.call('EXPIRE', KEYS[1], 3600) end
      if ip > tonumber(ARGV[1]) then return 3 end
      local cooldown = redis.call('SET', KEYS[2], '1', 'NX', 'EX', 300)
      if not cooldown then return 2 end
      local daily = redis.call('INCR', KEYS[3])
      if daily == 1 then redis.call('EXPIRE', KEYS[3], 86400) end
      if daily > 3 then return 2 end
      return 1
      """;
  private static final String VERIFY_SCRIPT =
      """
      local attempts = redis.call('INCR', KEYS[1])
      if attempts == 1 then redis.call('EXPIRE', KEYS[1], 3600) end
      if attempts > tonumber(ARGV[1]) then return 3 end
      return 1
      """;

  private final StringRedisTemplate redis;
  private final byte[] key;
  private final int resendIpHourly;
  private final int verifyIpHourly;

  public VerificationRateLimiter(
      StringRedisTemplate redis,
      @Value("${app.verification.rate-limit.key:}") String encodedKey,
      @Value("${app.verification.rate-limit.resend-ip-hourly:30}") int resendIpHourly,
      @Value("${app.verification.rate-limit.verify-ip-hourly:20}") int verifyIpHourly) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encodedKey);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Verification rate-limit key must be Base64-encoded");
    }
    if (decoded.length < 32 || resendIpHourly < 1 || verifyIpHourly < 1) {
      throw new IllegalStateException("Verification rate-limit configuration is incomplete");
    }
    this.redis = redis;
    this.key = decoded;
    this.resendIpHourly = resendIpHourly;
    this.verifyIpHourly = verifyIpHourly;
  }

  /** Returns false for email-specific limits so the caller can still return generic 202. */
  public boolean allowResend(String normalizedEmail, String remoteAddress) {
    String emailKey = hash(normalizedEmail);
    Long result =
        execute(
            RESEND_SCRIPT,
            List.of(
                "verification:resend:ip:" + hash(remoteAddress),
                "verification:resend:cooldown:" + emailKey,
                "verification:resend:daily:" + emailKey),
            resendIpHourly);
    if (result == 3) throw new VerificationRateLimitException(false);
    return result == 1;
  }

  public void checkVerificationAttempt(String remoteAddress) {
    Long result =
        execute(
            VERIFY_SCRIPT,
            List.of("verification:verify:ip:" + hash(remoteAddress)),
            verifyIpHourly);
    if (result != 1) throw new VerificationRateLimitException(false);
  }

  private Long execute(String script, List<String> keys, int limit) {
    try {
      Long result =
          redis.execute(
              new DefaultRedisScript<>(script, Long.class), keys, Integer.toString(limit));
      if (result == null) throw new VerificationRateLimitException(true);
      return result;
    } catch (VerificationRateLimitException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new VerificationRateLimitException(true);
    }
  }

  private String hash(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC unavailable");
    }
  }
}
