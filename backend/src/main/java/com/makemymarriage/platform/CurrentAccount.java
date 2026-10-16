package com.makemymarriage.platform;

import java.util.UUID;

public record CurrentAccount(
    UUID id, String displayName, String email, boolean emailVerified, String status) {
  public static final String REQUEST_ATTRIBUTE = CurrentAccount.class.getName();
}
