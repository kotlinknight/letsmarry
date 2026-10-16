package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.AccountPrincipal;
import com.makemymarriage.identity.application.CurrentAccountService;
import com.makemymarriage.identity.application.EmailAddressPolicy;
import com.makemymarriage.identity.application.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "app.authentication.enabled", havingValue = "true")
class LoginController {
  private final AuthenticationManager authenticationManager;
  private final LoginRateLimiter rateLimiter;
  private final CurrentAccountService currentAccounts;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final SecurityContextRepository contextRepository;

  LoginController(
      AuthenticationManager authenticationManager,
      LoginRateLimiter rateLimiter,
      CurrentAccountService currentAccounts,
      SessionAuthenticationStrategy sessionStrategy,
      SecurityContextRepository contextRepository) {
    this.authenticationManager = authenticationManager;
    this.rateLimiter = rateLimiter;
    this.currentAccounts = currentAccounts;
    this.sessionStrategy = sessionStrategy;
    this.contextRepository = contextRepository;
  }

  @PostMapping("/login")
  ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest login,
      HttpServletRequest request,
      HttpServletResponse response) {
    String email = EmailAddressPolicy.normalize(login.email());
    rateLimiter.check(email, request.getRemoteAddr());
    Authentication authentication =
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(email, login.password()));
    var account =
        currentAccounts
            .findEligibleAccount(((AccountPrincipal) authentication.getPrincipal()).id())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    sessionStrategy.onAuthentication(authentication, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, request, response);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(LoginResponse.from(account));
  }
}
