package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import com.makemymarriage.platform.AccountSessionValidator;
import com.makemymarriage.platform.CurrentAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentAccountService implements AccountSessionValidator {
  private final UserAccountRepository users;

  public CurrentAccountService(UserAccountRepository users) {
    this.users = users;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CurrentAccount> findEligibleAccount(UUID id) {
    return users.findById(id).filter(UserAccount::canAuthenticate).map(this::toCurrentAccount);
  }

  private CurrentAccount toCurrentAccount(UserAccount user) {
    return new CurrentAccount(
        user.getId(),
        user.getDisplayName(),
        user.getEmail(),
        user.getEmailVerifiedAt() != null,
        user.getStatus().name(),
        user.getAuthVersion());
  }
}
