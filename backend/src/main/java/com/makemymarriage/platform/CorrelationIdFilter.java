package com.makemymarriage.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {
  static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
  static final String HEADER = "X-Correlation-ID";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, correlationId);
    response.setHeader(HEADER, correlationId);
    filterChain.doFilter(request, response);
  }
}
