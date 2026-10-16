package com.makemymarriage.notification;

import java.util.UUID;

public record EmailDelivery(
    UUID deliveryId, String recipientEmail, String templateCode, String payloadJson) {
  @Override
  public String toString() {
    return "EmailDelivery[deliveryId=" + deliveryId + ", templateCode=" + templateCode + "]";
  }
}
