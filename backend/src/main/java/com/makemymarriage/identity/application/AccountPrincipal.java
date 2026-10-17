package com.makemymarriage.identity.application;

import com.makemymarriage.platform.PlannerPrincipal;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record AccountPrincipal(UUID id, Long authVersion)
    implements PlannerPrincipal, Principal, Serializable {
  public AccountPrincipal(UUID id) {
    this(id, null);
  }

  @Override
  public String getName() {
    return id.toString();
  }
}
