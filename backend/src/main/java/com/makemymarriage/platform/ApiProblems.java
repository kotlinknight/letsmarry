package com.makemymarriage.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

public final class ApiProblems {
  private ApiProblems() {}

  public static ProblemDetail create(
      HttpServletRequest request, HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setType(
        URI.create(
            "https://api.makemymarriage.com/problems/" + code.toLowerCase().replace('_', '-')));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId(request));
    return problem;
  }

  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      ObjectMapper mapper,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CorrelationIdFilter.HEADER, correlationId(request));
    mapper.writeValue(response.getOutputStream(), create(request, status, code, detail));
  }

  private static String correlationId(HttpServletRequest request) {
    Object existing = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
    if (existing instanceof String id) {
      return id;
    }
    String created = UUID.randomUUID().toString();
    request.setAttribute(CorrelationIdFilter.ATTRIBUTE, created);
    return created;
  }
}
