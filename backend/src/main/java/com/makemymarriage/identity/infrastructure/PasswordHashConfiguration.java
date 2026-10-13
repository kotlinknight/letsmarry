package com.makemymarriage.identity.infrastructure;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordHashConfiguration {
  private static final String ENCODER_ID = "pbkdf2@SpringSecurity_v5_8";

  @Bean
  PasswordEncoder passwordEncoder() {
    return new DelegatingPasswordEncoder(
        ENCODER_ID, Map.of(ENCODER_ID, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
  }
}
