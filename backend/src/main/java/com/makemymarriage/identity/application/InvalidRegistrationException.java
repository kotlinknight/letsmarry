package com.makemymarriage.identity.application;

public class InvalidRegistrationException extends RuntimeException {
  private final String field;

  public InvalidRegistrationException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
