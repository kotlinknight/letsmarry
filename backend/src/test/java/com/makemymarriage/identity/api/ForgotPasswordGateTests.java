package com.makemymarriage.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ForgotPasswordGateTests {
  @Test
  void localNeedsIndependentRecoveryAndOutboxFlags() {
    assertThat(enabled("local", false, true)).isFalse();
    assertThat(enabled("local", true, false)).isFalse();
  }

  @Test
  void productionCannotEnableRecoveryEvenWhenLocalIsAlsoActive() {
    assertThat(enabled("production", true, true)).isFalse();
    assertThat(enabled("local,production", true, true)).isFalse();
  }

  private boolean enabled(String profiles, boolean recovery, boolean outbox) {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles(profiles.split(","));
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "app.password-recovery.enabled", Boolean.toString(recovery),
                      "app.email-outbox.enabled", Boolean.toString(outbox))));
      context.register(ForgotPasswordController.class);
      context.refresh();
      return !context.getBeansOfType(ForgotPasswordController.class).isEmpty();
    }
  }
}
