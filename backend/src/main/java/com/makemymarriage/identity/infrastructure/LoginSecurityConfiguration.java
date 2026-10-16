package com.makemymarriage.identity.infrastructure;

import com.makemymarriage.identity.application.AccountAuthenticationProvider;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.authentication.enabled", havingValue = "true")
class LoginSecurityConfiguration {
  @Bean
  AuthenticationManager accountAuthenticationManager(AccountAuthenticationProvider provider) {
    return new ProviderManager(provider);
  }

  @Bean
  SessionAuthenticationStrategy loginSessionAuthenticationStrategy() {
    return new CompositeSessionAuthenticationStrategy(
        List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())));
  }

  @Bean
  SecurityContextRepository loginSecurityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }
}
