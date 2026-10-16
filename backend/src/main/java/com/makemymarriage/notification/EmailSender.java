package com.makemymarriage.notification;

public interface EmailSender {
  SendReceipt send(EmailDelivery delivery);

  record SendReceipt(String providerMessageId) {}
}
