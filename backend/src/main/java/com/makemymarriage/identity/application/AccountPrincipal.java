package com.makemymarriage.identity.application;

import com.makemymarriage.platform.PlannerPrincipal;
import java.io.Serializable;
import java.util.UUID;

public record AccountPrincipal(UUID id) implements PlannerPrincipal, Serializable {}
