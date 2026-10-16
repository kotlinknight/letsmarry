package com.makemymarriage.notification.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.email-outbox.enabled", havingValue = "true")
class EmailOutboxConfiguration {
  @Bean
  EmailPayloadCipher emailPayloadCipher(
      @Value("${app.email-outbox.active-key-id:}") String activeKeyId,
      @Value("${app.email-outbox.keys:}") String keys) {
    return new EmailPayloadCipher(activeKeyId, keys);
  }
}
