package com.makemymarriage.identity.api;

import com.makemymarriage.platform.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CurrentUserController {
  @GetMapping("/api/v1/users/me")
  ResponseEntity<CurrentUserResponse> me(HttpServletRequest request) {
    CurrentAccount account =
        (CurrentAccount) request.getAttribute(CurrentAccount.REQUEST_ATTRIBUTE);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(CurrentUserResponse.from(account));
  }

  record CurrentUserResponse(
      java.util.UUID id, String displayName, String email, boolean emailVerified, String status) {
    static CurrentUserResponse from(CurrentAccount account) {
      return new CurrentUserResponse(
          account.id(),
          account.displayName(),
          account.email(),
          account.emailVerified(),
          account.status());
    }
  }
}
