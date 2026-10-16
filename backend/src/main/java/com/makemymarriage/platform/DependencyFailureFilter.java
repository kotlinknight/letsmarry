package com.makemymarriage.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class DependencyFailureFilter extends OncePerRequestFilter {
  private final ObjectMapper mapper;

  DependencyFailureFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (DataAccessException exception) {
      if (response.isCommitted()) throw exception;
      response.reset();
      response.setHeader("Cache-Control", "no-store");
      ApiProblems.write(
          request,
          response,
          mapper,
          HttpStatus.SERVICE_UNAVAILABLE,
          "AUTHENTICATION_TEMPORARILY_UNAVAILABLE",
          "Authentication is temporarily unavailable.");
    }
  }
}
