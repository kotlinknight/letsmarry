package com.makemymarriage.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

record VerificationEmailRequest(@NotBlank @Email @Size(max = 320) String email) {
  VerificationEmailRequest {
    if (email != null) {
      email = email.trim().toLowerCase(Locale.ROOT);
    }
  }
}
