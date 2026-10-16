package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.LoginRateLimitException;
import com.makemymarriage.platform.ApiProblems;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LoginController.class)
class LoginExceptionHandler {
  @ExceptionHandler(BadCredentialsException.class)
  ResponseEntity<ProblemDetail> invalidCredentials(HttpServletRequest request) {
    return problem(request, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials.");
  }

  @ExceptionHandler(LoginRateLimitException.class)
  ResponseEntity<ProblemDetail> rateLimited(
      LoginRateLimitException exception, HttpServletRequest request) {
    if (exception.isUnavailable()) {
      return problem(
          request,
          HttpStatus.SERVICE_UNAVAILABLE,
          "AUTHENTICATION_TEMPORARILY_UNAVAILABLE",
          "Authentication is temporarily unavailable.");
    }
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .cacheControl(CacheControl.noStore())
        .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            ApiProblems.create(
                request,
                HttpStatus.TOO_MANY_REQUESTS,
                "AUTHENTICATION_RATE_LIMITED",
                "Too many login attempts. Try again later."));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ProblemDetail> storageUnavailable(HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTHENTICATION_TEMPORARILY_UNAVAILABLE",
        "Authentication is temporarily unavailable.");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidFields(HttpServletRequest request) {
    return problem(
        request, HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "Invalid login fields.");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> malformedJson(HttpServletRequest request) {
    return problem(
        request, HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is not valid JSON.");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpServletRequest request, HttpStatus status, String code, String detail) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ApiProblems.create(request, status, code, detail));
  }
}
