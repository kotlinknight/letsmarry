package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.EmailVerificationService;
import com.makemymarriage.identity.application.RegistrationCommand;
import com.makemymarriage.identity.application.RegistrationService;
import com.makemymarriage.identity.application.VerificationRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("(local | test) & !production")
@ConditionalOnProperty(
    name = {"app.registration.enabled", "app.email-outbox.enabled"},
    havingValue = "true")
class RegistrationController {
  private final RegistrationService registrationService;
  private final EmailVerificationService emailVerification;
  private final ObjectProvider<VerificationRateLimiter> rateLimiter;

  RegistrationController(
      RegistrationService registrationService,
      EmailVerificationService emailVerification,
      ObjectProvider<VerificationRateLimiter> rateLimiter) {
    this.registrationService = registrationService;
    this.emailVerification = emailVerification;
    this.rateLimiter = rateLimiter;
  }

  @GetMapping("/csrf")
  ResponseEntity<CsrfResponse> csrf(HttpServletRequest request) {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new CsrfResponse(token.getToken(), token.getHeaderName()));
  }

  @PostMapping("/register")
  ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
    var user =
        registrationService.register(
            new RegistrationCommand(request.displayName(), request.email(), request.password()));
    return ResponseEntity.status(HttpStatus.CREATED).body(RegistrationResponse.from(user));
  }

  @PostMapping("/email/verify")
  ResponseEntity<Void> verify(
      @RequestBody VerificationRequest request, HttpServletRequest servletRequest) {
    VerificationRateLimiter limiter = rateLimiter.getIfAvailable();
    if (limiter != null) limiter.checkVerificationAttempt(servletRequest.getRemoteAddr());
    emailVerification.verify(request.token());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @PostMapping("/email/verification-requests")
  ResponseEntity<Void> requestVerification(
      @Valid @RequestBody VerificationEmailRequest request, HttpServletRequest servletRequest) {
    VerificationRateLimiter limiter = rateLimiter.getIfAvailable();
    if (limiter == null || limiter.allowResend(request.email(), servletRequest.getRemoteAddr())) {
      emailVerification.requestResend(request.email());
    }
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
  }

  record CsrfResponse(String token, String headerName) {}
}
