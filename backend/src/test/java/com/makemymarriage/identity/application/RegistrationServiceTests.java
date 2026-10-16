package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class RegistrationServiceTests {
  private final UserAccountRepository users = mock(UserAccountRepository.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final EmailVerificationService emailVerification = mock(EmailVerificationService.class);
  private final RegistrationService service =
      new RegistrationService(users, encoder, emailVerification);

  @Test
  void normalizesInputAndPersistsOnlyTheEncodedPassword() {
    when(encoder.encode(" a long café phrase ")).thenReturn("{pbkdf2}encoded");
    when(users.saveAndFlush(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.register(
            new RegistrationCommand(
                "  Anjali  ", "  Anjali@Example.COM  ", " a long cafe\u0301 phrase "));

    ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
    verify(users).saveAndFlush(saved.capture());
    verify(emailVerification).issueForUser(saved.getValue());
    verify(encoder).encode(" a long café phrase ");
    assertThat(saved.getValue().getEmail()).isEqualTo("anjali@example.com");
    assertThat(ReflectionTestUtils.getField(saved.getValue(), "passwordHash"))
        .isEqualTo("{pbkdf2}encoded");
    assertThat(result.displayName()).isEqualTo("Anjali");
    assertThat(result.emailVerified()).isFalse();
  }

  @Test
  void mapsOnlyTheEmailUniqueConstraintToDuplicateEmail() {
    var unique =
        new ConstraintViolationException(
            "duplicate", new SQLException("duplicate", "23505"), "ux_users_email_ci");
    when(encoder.encode(any())).thenReturn("hash");
    when(users.saveAndFlush(any(UserAccount.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate", unique));

    assertThatThrownBy(
            () ->
                service.register(
                    new RegistrationCommand("Anjali", "anjali@example.com", "a long password")))
        .isInstanceOf(DuplicateEmailException.class);
  }
}
