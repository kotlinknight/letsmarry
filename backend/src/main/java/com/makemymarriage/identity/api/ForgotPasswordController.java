package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.PasswordRecoveryEmailService;
import com.makemymarriage.identity.application.PasswordRecoveryRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password")
@Profile("(local | test) & !production")
@ConditionalOnProperty(
    name = {"app.password-recovery.enabled", "app.email-outbox.enabled"},
    havingValue = "true")
class ForgotPasswordController {
  private final PasswordRecoveryEmailService service;
  private final PasswordRecoveryRateLimiter rateLimiter;
  private final long responseFloorNanos;

  ForgotPasswordController(
      PasswordRecoveryEmailService service,
      PasswordRecoveryRateLimiter rateLimiter,
      @Value("${app.password-recovery.response-floor-ms:250}") int responseFloorMillis) {
    if (responseFloorMillis < 0 || responseFloorMillis > 5000) {
      throw new IllegalStateException("Invalid password recovery response floor");
    }
    this.service = service;
    this.rateLimiter = rateLimiter;
    this.responseFloorNanos = responseFloorMillis * 1_000_000L;
  }

  @PostMapping("/forgot")
  ResponseEntity<Void> forgot(
      @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest servletRequest) {
    long started = System.nanoTime();
    if (rateLimiter.allowRequest(request.email(), servletRequest.getRemoteAddr())) {
      service.request(request.email());
    }
    long remaining = responseFloorNanos - (System.nanoTime() - started);
    if (remaining > 0) LockSupport.parkNanos(remaining);
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
  }
}
