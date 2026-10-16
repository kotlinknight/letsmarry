package com.makemymarriage.notification;

import java.util.UUID;

/** Checks whether the purpose of an outbox message is still valid immediately before delivery. */
public interface EmailDeliveryEligibility {
  boolean maySend(String templateCode, UUID purposeId);
}
