package com.makemymarriage.notification.domain;

public enum OutboxStatus {
  PENDING,
  PROCESSING,
  SENT,
  FAILED,
  EXPIRED
}
