package com.makemymarriage.platform;

import java.util.Optional;
import java.util.UUID;

public interface AccountSessionValidator {
  Optional<CurrentAccount> findEligibleAccount(UUID id);
}
