package com.makemymarriage.identity.infrastructure;

import com.makemymarriage.identity.domain.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {
  Optional<EmailVerificationToken> findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(UUID userId);

  @Query("select t.userId from EmailVerificationToken t where t.tokenHash = :tokenHash")
  Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") byte[] tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from EmailVerificationToken t where t.tokenHash = :tokenHash")
  Optional<EmailVerificationToken> lockByTokenHash(@Param("tokenHash") byte[] tokenHash);
}
