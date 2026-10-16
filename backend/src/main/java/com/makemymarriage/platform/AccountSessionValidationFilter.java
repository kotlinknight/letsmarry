package com.makemymarriage.platform;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

final class AccountSessionValidationFilter extends OncePerRequestFilter {
  private final AccountSessionValidator validator;
  private final ObjectMapper mapper;

  AccountSessionValidationFilter(AccountSessionValidator validator, ObjectMapper mapper) {
    this.validator = validator;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
    return request.getDispatcherType() == DispatcherType.ERROR
        || path.equals("/error")
        || path.equals("/api/v1/auth/csrf")
        || path.equals("/api/v1/auth/login")
        || path.equals("/api/v1/auth/logout")
        || path.equals("/api/v1/auth/register")
        || path.equals("/api/v1/auth/email/verify")
        || path.equals("/api/v1/auth/email/verification-requests")
        || path.equals("/actuator/health")
        || path.startsWith("/actuator/health/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Object principal = authentication == null ? null : authentication.getPrincipal();
    CurrentAccount account =
        principal instanceof PlannerPrincipal planner
            ? validator.findEligibleAccount(planner.id()).orElse(null)
            : null;
    if (account == null) {
      HttpSession session = request.getSession(false);
      if (session != null) session.invalidate();
      SecurityContextHolder.clearContext();
      response.setHeader("Cache-Control", "no-store");
      ApiProblems.write(
          request,
          response,
          mapper,
          HttpStatus.UNAUTHORIZED,
          "AUTHENTICATION_REQUIRED",
          "Authentication is required.");
      return;
    }
    request.setAttribute(CurrentAccount.REQUEST_ATTRIBUTE, account);
    chain.doFilter(request, response);
  }
}
