package com.makemymarriage.identity.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class VerificationClockConfiguration {
  @Bean
  Clock verificationClock() {
    return Clock.systemUTC();
  }
}
