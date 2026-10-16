package com.makemymarriage.identity.api;

import com.makemymarriage.identity.application.EmailAddressPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record LoginRequest(@NotBlank @Email @Size(max = 320) String email, @NotBlank String password) {
  LoginRequest {
    if (email != null) email = EmailAddressPolicy.normalize(email);
  }
}
