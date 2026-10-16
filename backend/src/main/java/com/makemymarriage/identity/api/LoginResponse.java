package com.makemymarriage.identity.api;

import com.makemymarriage.platform.CurrentAccount;
import java.util.UUID;

record LoginResponse(LoginUser user) {
  static LoginResponse from(CurrentAccount account) {
    return new LoginResponse(
        new LoginUser(
            account.id(),
            account.displayName(),
            account.email(),
            account.emailVerified(),
            account.status()));
  }

  record LoginUser(
      UUID id, String displayName, String email, boolean emailVerified, String status) {}
}
