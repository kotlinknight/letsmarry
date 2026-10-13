package com.makemymarriage.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class RegistrationGateTests {
  @Test
  void productionProfileCannotEnableRegistrationEvenWithPropertySet() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("production");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("test", Map.of("app.registration.enabled", "true")));
      context.register(RegistrationController.class);
      context.refresh();
      assertThat(context.getBeansOfType(RegistrationController.class)).isEmpty();
    }
  }
}
