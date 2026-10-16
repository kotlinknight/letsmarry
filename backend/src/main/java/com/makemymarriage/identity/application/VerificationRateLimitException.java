package com.makemymarriage.identity.application;

public class VerificationRateLimitException extends RuntimeException {
  private final boolean unavailable;

  public VerificationRateLimitException(boolean unavailable) {
    super(
        unavailable
            ? "Verification rate limiting is unavailable"
            : "Too many verification requests");
    this.unavailable = unavailable;
  }

  public boolean isUnavailable() {
    return unavailable;
  }
}
