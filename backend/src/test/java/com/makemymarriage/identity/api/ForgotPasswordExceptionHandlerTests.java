package com.makemymarriage.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.makemymarriage.identity.application.PasswordRecoveryRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ForgotPasswordExceptionHandlerTests {
  private final ForgotPasswordExceptionHandler handler = new ForgotPasswordExceptionHandler();

  @Test
  void ipLimitAndRedisFailureUseSafeProblemCodes() {
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot");
    var limited = handler.rateLimited(new PasswordRecoveryRateLimitException(false), request);
    assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(limited.getBody().getProperties().get("code"))
        .isEqualTo("PASSWORD_RECOVERY_RATE_LIMITED");
    assertThat(limited.getBody().getProperties().get("correlationId")).isNotNull();

    var unavailable = handler.rateLimited(new PasswordRecoveryRateLimitException(true), request);
    assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(unavailable.getBody().getProperties().get("code"))
        .isEqualTo("PASSWORD_RECOVERY_TEMPORARILY_UNAVAILABLE");
  }
}
