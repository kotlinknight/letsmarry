package com.makemymarriage.identity.application;

import java.util.UUID;

public record RegisteredUser(UUID id, String displayName, String email, boolean emailVerified) {}
