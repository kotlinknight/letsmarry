package com.makemymarriage.platform;

import java.util.UUID;

public interface PlannerPrincipal {
  UUID id();

  /** Null marks a session created before version capture was introduced. */
  Long authVersion();
}
