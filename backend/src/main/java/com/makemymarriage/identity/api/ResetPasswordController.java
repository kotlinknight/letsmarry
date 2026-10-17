package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.PasswordRecoveryRateLimiter;
import com.makemymarriage.identity.application.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
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
class ResetPasswordController {
  private final PasswordResetService service;
  private final PasswordRecoveryRateLimiter limiter;

  ResetPasswordController(PasswordResetService service, PasswordRecoveryRateLimiter limiter) {
    this.service = service;
    this.limiter = limiter;
  }

  @PostMapping("/reset")
  ResponseEntity<Void> reset(@RequestBody ResetPasswordRequest body, HttpServletRequest request) {
    limiter.checkResetAttempt(request.getRemoteAddr());
    service.reset(body.token(), body.newPassword());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
