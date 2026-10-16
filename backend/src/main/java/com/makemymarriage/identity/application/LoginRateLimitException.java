package com.makemymarriage.identity.application;

public final class LoginRateLimitException extends RuntimeException {
  private final boolean unavailable;
  private final long retryAfterSeconds;

  public LoginRateLimitException(boolean unavailable, long retryAfterSeconds) {
    super(unavailable ? "Login is temporarily unavailable" : "Too many login attempts");
    this.unavailable = unavailable;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public boolean isUnavailable() {
    return unavailable;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
