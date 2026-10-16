package com.makemymarriage.notification;

import java.util.UUID;

public interface EmailOutbox {
  UUID enqueue(EmailDraft draft);

  int cancelPending(UUID purposeId);
}
