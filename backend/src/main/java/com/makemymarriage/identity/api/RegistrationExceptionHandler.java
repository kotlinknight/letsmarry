package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.DuplicateEmailException;
import com.makemymarriage.identity.application.InvalidRegistrationException;
import com.makemymarriage.platform.ApiProblems;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = RegistrationController.class)
class RegistrationExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidFields(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    var problem =
        ApiProblems.create(
            request,
            HttpStatus.UNPROCESSABLE_CONTENT,
            "VALIDATION_ERROR",
            "Invalid registration fields.");
    problem.setProperty(
        "errors",
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    Map.of(
                        "field",
                        error.getField(),
                        "code",
                        "INVALID_FIELD",
                        "message",
                        error.getDefaultMessage() == null
                            ? "Invalid value."
                            : error.getDefaultMessage()))
            .sorted(Comparator.comparing(error -> error.get("field")))
            .toList());
    return response(HttpStatus.UNPROCESSABLE_CONTENT, problem);
  }

  @ExceptionHandler(InvalidRegistrationException.class)
  ResponseEntity<ProblemDetail> invalidRegistration(
      InvalidRegistrationException exception, HttpServletRequest request) {
    var problem =
        ApiProblems.create(
            request,
            HttpStatus.UNPROCESSABLE_CONTENT,
            "VALIDATION_ERROR",
            "Invalid registration fields.");
    problem.setProperty(
        "errors",
        java.util.List.of(
            Map.of(
                "field", exception.getField(),
                "code", "INVALID_FIELD",
                "message", exception.getMessage())));
    return response(HttpStatus.UNPROCESSABLE_CONTENT, problem);
  }

  @ExceptionHandler(DuplicateEmailException.class)
  ResponseEntity<ProblemDetail> duplicateEmail(
      DuplicateEmailException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        ApiProblems.create(
            request, HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> malformedJson(HttpServletRequest request) {
    return response(
        HttpStatus.BAD_REQUEST,
        ApiProblems.create(
            request, HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is not valid JSON."));
  }

  private static ResponseEntity<ProblemDetail> response(HttpStatus status, ProblemDetail problem) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
