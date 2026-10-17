package com.makemymarriage.identity.infrastructure;

import com.makemymarriage.identity.domain.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
  Optional<PasswordResetToken> findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(UUID userId);

  @Query("select t.userId from PasswordResetToken t where t.tokenHash = :tokenHash")
  Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") byte[] tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
  Optional<PasswordResetToken> lockByTokenHash(@Param("tokenHash") byte[] tokenHash);
}
