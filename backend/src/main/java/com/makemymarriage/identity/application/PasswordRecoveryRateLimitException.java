package com.makemymarriage.identity.application;

public class PasswordRecoveryRateLimitException extends RuntimeException {
  private final boolean unavailable;

  public PasswordRecoveryRateLimitException(boolean unavailable) {
    super(
        unavailable
            ? "Password recovery is temporarily unavailable"
            : "Too many recovery requests");
    this.unavailable = unavailable;
  }

  public boolean isUnavailable() {
    return unavailable;
  }
}
