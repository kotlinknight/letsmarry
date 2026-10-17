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
import org.springframework.stereotype.Component;

@Component
@Profile("(local | test) & !production")
@ConditionalOnProperty(name = "app.password-recovery.enabled", havingValue = "true")
public class PasswordRecoveryRateLimiter {
  private static final String SCRIPT =
      """
      local ip = redis.call('INCR', KEYS[1])
      if ip == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
      if ip > tonumber(ARGV[2]) then return 3 end
      local cooldown = redis.call('SET', KEYS[2], '1', 'NX', 'EX', tonumber(ARGV[3]))
      if not cooldown then return 2 end
      local daily = redis.call('INCR', KEYS[3])
      if daily == 1 then redis.call('EXPIRE', KEYS[3], 86400) end
      if daily > tonumber(ARGV[4]) then return 2 end
      return 1
      """;

  private final StringRedisTemplate redis;
  private final byte[] key;
  private final int cooldownSeconds;
  private final int emailDaily;
  private final int ipHourly;
  private final int resetIpHourly;

  public PasswordRecoveryRateLimiter(
      StringRedisTemplate redis,
      @Value("${app.password-recovery.rate-limit.key:}") String encodedKey,
      @Value("${app.password-recovery.rate-limit.cooldown-seconds:300}") int cooldownSeconds,
      @Value("${app.password-recovery.rate-limit.email-daily:3}") int emailDaily,
      @Value("${app.password-recovery.rate-limit.ip-hourly:30}") int ipHourly,
      @Value("${app.password-recovery.rate-limit.reset-ip-hourly:20}") int resetIpHourly) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encodedKey);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Password recovery rate-limit key must be Base64-encoded");
    }
    if (decoded.length < 32
        || cooldownSeconds < 1
        || emailDaily < 1
        || ipHourly < 1
        || resetIpHourly < 1) {
      throw new IllegalStateException("Password recovery rate-limit configuration is incomplete");
    }
    this.redis = redis;
    this.key = decoded;
    this.cooldownSeconds = cooldownSeconds;
    this.emailDaily = emailDaily;
    this.ipHourly = ipHourly;
    this.resetIpHourly = resetIpHourly;
  }

  /** Count every syntactically valid reset attempt, including rejected tokens. */
  public void checkResetAttempt(String remoteAddress) {
    try {
      Long count =
          redis.execute(
              new DefaultRedisScript<>(
                  "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],3600) end; return n",
                  Long.class),
              List.of("recovery:reset:ip:" + hash(remoteAddress)));
      if (count == null) throw new PasswordRecoveryRateLimitException(true);
      if (count > resetIpHourly) throw new PasswordRecoveryRateLimitException(false);
    } catch (PasswordRecoveryRateLimitException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new PasswordRecoveryRateLimitException(true);
    }
  }

  /** Email-specific limits return false so the HTTP response stays generic. */
  public boolean allowRequest(String normalizedEmail, String remoteAddress) {
    String emailKey = hash(normalizedEmail);
    try {
      Long result =
          redis.execute(
              new DefaultRedisScript<>(SCRIPT, Long.class),
              List.of(
                  "recovery:forgot:ip:" + hash(remoteAddress),
                  "recovery:forgot:cooldown:" + emailKey,
                  "recovery:forgot:daily:" + emailKey),
              "3600",
              Integer.toString(ipHourly),
              Integer.toString(cooldownSeconds),
              Integer.toString(emailDaily));
      if (result == null) throw new PasswordRecoveryRateLimitException(true);
      if (result == 3) throw new PasswordRecoveryRateLimitException(false);
      return result == 1;
    } catch (PasswordRecoveryRateLimitException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new PasswordRecoveryRateLimitException(true);
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
