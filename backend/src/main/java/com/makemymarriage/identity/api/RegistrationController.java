package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.RegistrationCommand;
import com.makemymarriage.identity.application.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@Profile({"local", "test"})
@ConditionalOnProperty(name = "app.registration.enabled", havingValue = "true")
class RegistrationController {
  private final RegistrationService registrationService;

  RegistrationController(RegistrationService registrationService) {
    this.registrationService = registrationService;
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

  record CsrfResponse(String token, String headerName) {}
}
