package com.makemymarriage.notification;

public class EmailSendException extends RuntimeException {
  private final boolean permanent;

  public EmailSendException(boolean permanent) {
    super(permanent ? "Permanent email delivery failure" : "Temporary email delivery failure");
    this.permanent = permanent;
  }

  public boolean isPermanent() {
    return permanent;
  }
}
