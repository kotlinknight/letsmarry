package com.makemymarriage.notification.infrastructure;

import com.makemymarriage.notification.EmailDeliveryEligibility;
import com.makemymarriage.notification.EmailSender;
import com.makemymarriage.notification.application.OutboxWorker;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.email-outbox.worker.enabled", havingValue = "true")
@Profile("(local | test) & !production")
@EnableScheduling
class OutboxWorkerConfiguration {
  @Bean
  OutboxWorker outboxWorker(
      EmailOutboxClaims claims,
      EmailPayloadCipher cipher,
      EmailSender sender,
      EmailDeliveryEligibility eligibility) {
    return new OutboxWorker(claims, cipher, sender, eligibility, Clock.systemUTC());
  }
}
