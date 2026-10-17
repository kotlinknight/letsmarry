package com.makemymarriage.identity.application;

public class InvalidPasswordResetTokenException extends RuntimeException {
  public InvalidPasswordResetTokenException() {
    super("Password reset link is invalid or has expired");
  }
}
