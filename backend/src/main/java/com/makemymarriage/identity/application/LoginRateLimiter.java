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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.authentication.enabled", havingValue = "true")
public class LoginRateLimiter {
  private static final String SCRIPT =
      """
      local email = redis.call('INCR', KEYS[1])
      if email == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3])) end
      local ip = redis.call('INCR', KEYS[2])
      if ip == 1 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3])) end
      if email > tonumber(ARGV[1]) or ip > tonumber(ARGV[2]) then
        local ttl = math.max(redis.call('TTL', KEYS[1]), redis.call('TTL', KEYS[2]))
        return math.max(ttl, 1)
      end
      return 0
      """;

  private final StringRedisTemplate redis;
  private final byte[] key;
  private final int emailLimit;
  private final int ipLimit;
  private final int windowSeconds;

  public LoginRateLimiter(
      StringRedisTemplate redis,
      @Value("${app.authentication.rate-limit.key:}") String encodedKey,
      @Value("${app.authentication.rate-limit.email-attempts:10}") int emailLimit,
      @Value("${app.authentication.rate-limit.ip-attempts:60}") int ipLimit,
      @Value("${app.authentication.rate-limit.window-seconds:900}") int windowSeconds) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encodedKey);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Authentication rate-limit key must be Base64-encoded");
    }
    if (decoded.length < 32 || emailLimit < 1 || ipLimit < 1 || windowSeconds < 1) {
      throw new IllegalStateException("Authentication rate-limit configuration is incomplete");
    }
    this.redis = redis;
    this.key = decoded;
    this.emailLimit = emailLimit;
    this.ipLimit = ipLimit;
    this.windowSeconds = windowSeconds;
  }

  public void check(String normalizedEmail, String remoteAddress) {
    try {
      Long retryAfter =
          redis.execute(
              new DefaultRedisScript<>(SCRIPT, Long.class),
              List.of(
                  "authentication:login:email:" + hash(normalizedEmail),
                  "authentication:login:ip:" + hash(remoteAddress)),
              Integer.toString(emailLimit),
              Integer.toString(ipLimit),
              Integer.toString(windowSeconds));
      if (retryAfter == null) throw new LoginRateLimitException(true, 0);
      if (retryAfter > 0) throw new LoginRateLimitException(false, retryAfter);
    } catch (LoginRateLimitException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new LoginRateLimitException(true, 0);
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
