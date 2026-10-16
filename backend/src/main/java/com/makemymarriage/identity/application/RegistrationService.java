package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
  private final UserAccountRepository users;
  private final PasswordEncoder passwordEncoder;
  private final EmailVerificationService emailVerification;

  public RegistrationService(
      UserAccountRepository users,
      PasswordEncoder passwordEncoder,
      EmailVerificationService emailVerification) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.emailVerification = emailVerification;
  }

  @Transactional
  public RegisteredUser register(RegistrationCommand command) {
    String email = command.email().trim().toLowerCase(Locale.ROOT);
    String displayName = command.displayName().trim();
    String password = PasswordPolicy.normalizeAndValidate(command.password());
    UserAccount user = new UserAccount(email, passwordEncoder.encode(password), displayName);
    try {
      users.saveAndFlush(user);
    } catch (DataIntegrityViolationException exception) {
      if (isEmailUniqueViolation(exception)) {
        throw new DuplicateEmailException();
      }
      throw exception;
    }
    emailVerification.issueForUser(user);
    return new RegisteredUser(user.getId(), user.getDisplayName(), user.getEmail(), false);
  }

  private static boolean isEmailUniqueViolation(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "ux_users_email_ci".equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }
}
