package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.RegisteredUser;
import java.util.UUID;

public record RegistrationResponse(
    UUID id, String displayName, String email, boolean emailVerified) {
  public static RegistrationResponse from(RegisteredUser user) {
    return new RegistrationResponse(
        user.id(), user.displayName(), user.email(), user.emailVerified());
  }
}
