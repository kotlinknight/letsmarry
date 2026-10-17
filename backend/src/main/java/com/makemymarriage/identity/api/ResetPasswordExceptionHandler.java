package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.InvalidPasswordResetTokenException;
import com.makemymarriage.identity.application.InvalidRegistrationException;
import com.makemymarriage.identity.application.PasswordRecoveryRateLimitException;
import com.makemymarriage.platform.ApiProblems;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ResetPasswordController.class)
class ResetPasswordExceptionHandler {
  @ExceptionHandler(InvalidPasswordResetTokenException.class)
  ResponseEntity<ProblemDetail> invalidToken(HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.BAD_REQUEST,
        "PASSWORD_RESET_TOKEN_INVALID",
        "This reset link is invalid or expired.");
  }

  @ExceptionHandler(InvalidRegistrationException.class)
  ResponseEntity<ProblemDetail> invalidPassword(
      InvalidRegistrationException exception, HttpServletRequest request) {
    var detail =
        ApiProblems.create(
            request, HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "Invalid password.");
    detail.setProperty(
        "errors",
        List.of(
            Map.of(
                "field", "newPassword",
                "code", "INVALID_FIELD",
                "message", exception.getMessage())));
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(detail);
  }

  @ExceptionHandler(PasswordRecoveryRateLimitException.class)
  ResponseEntity<ProblemDetail> rateLimited(
      PasswordRecoveryRateLimitException exception, HttpServletRequest request) {
    return exception.isUnavailable()
        ? unavailable(request)
        : problem(
            request,
            HttpStatus.TOO_MANY_REQUESTS,
            "PASSWORD_RECOVERY_RATE_LIMITED",
            "Too many requests. Try again later.");
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ProblemDetail> storageUnavailable(HttpServletRequest request) {
    return unavailable(request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> malformedJson(HttpServletRequest request) {
    return problem(
        request, HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is not valid JSON.");
  }

  private static ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "PASSWORD_RECOVERY_TEMPORARILY_UNAVAILABLE",
        "Password recovery is temporarily unavailable.");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpServletRequest request, HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ApiProblems.create(request, status, code, message));
  }
}
