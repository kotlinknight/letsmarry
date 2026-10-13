package com.makemymarriage.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegistrationRequest(
    @NotBlank @Size(max = 150) String displayName,
    @NotBlank @Email @Size(max = 320) String email,
    @NotNull String password) {
  public RegistrationRequest {
    if (displayName != null) {
      displayName = displayName.trim();
    }
    if (email != null) {
      email = email.trim().toLowerCase(Locale.ROOT);
    }
  }
}
