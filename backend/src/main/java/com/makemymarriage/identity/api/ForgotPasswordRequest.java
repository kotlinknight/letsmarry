package com.makemymarriage.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

record ForgotPasswordRequest(@NotBlank @Email @Size(max = 320) String email) {
  ForgotPasswordRequest {
    if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
  }
}
