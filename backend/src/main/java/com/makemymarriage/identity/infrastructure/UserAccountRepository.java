package com.makemymarriage.identity.infrastructure;

import com.makemymarriage.identity.domain.UserAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {}
