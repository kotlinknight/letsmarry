package com.makemymarriage.identity.infrastructure;

import com.makemymarriage.platform.PlannerPrincipal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetSessionCleanup {
  private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetSessionCleanup.class);

  private final RedisIndexedSessionRepository sessions;

  public PasswordResetSessionCleanup(RedisIndexedSessionRepository sessions) {
    this.sessions = sessions;
  }

  public void removeSessions(UUID userId, long newAuthVersion) {
    try {
      for (var entry : sessions.findByPrincipalName(userId.toString()).entrySet()) {
        if (isOlderSession(entry.getValue(), newAuthVersion)) {
          sessions.deleteById(entry.getKey());
        }
      }
    } catch (RuntimeException exception) {
      // PostgreSQL auth_version is the access boundary; Redis deletion is best effort.
      LOGGER.warn("Password reset session cleanup could not complete");
    }
  }

  private static boolean isOlderSession(Session session, long newAuthVersion) {
    SecurityContext context = session.getAttribute("SPRING_SECURITY_CONTEXT");
    Object principal =
        context == null || context.getAuthentication() == null
            ? null
            : context.getAuthentication().getPrincipal();
    return !(principal instanceof PlannerPrincipal planner)
        || planner.authVersion() == null
        || planner.authVersion() < newAuthVersion;
  }
}
