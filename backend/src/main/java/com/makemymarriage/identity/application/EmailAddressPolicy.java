package com.makemymarriage.identity.application;

import java.util.Locale;

public final class EmailAddressPolicy {
  private EmailAddressPolicy() {}

  public static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
