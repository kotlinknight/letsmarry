package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountAuthenticationProvider implements AuthenticationProvider {
  private final UserAccountRepository users;
  private final PasswordEncoder passwordEncoder;
  private final String absentAccountHash;

  public AccountAuthenticationProvider(
      UserAccountRepository users, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.absentAccountHash = passwordEncoder.encode("unavailable-account-password");
  }

  @Override
  @Transactional(readOnly = true)
  public Authentication authenticate(Authentication authentication) {
    String email = EmailAddressPolicy.normalize(authentication.getName());
    String password =
        PasswordPolicy.normalizeForAuthentication((String) authentication.getCredentials());
    UserAccount user = users.findByEmail(email).orElse(null);
    String hash = user == null ? absentAccountHash : user.getPasswordHash();
    boolean passwordMatches = passwordEncoder.matches(password, hash);
    if (!passwordMatches || user == null || !user.canAuthenticate()) {
      throw new BadCredentialsException("Invalid credentials");
    }
    return UsernamePasswordAuthenticationToken.authenticated(
        new AccountPrincipal(user.getId(), user.getAuthVersion()),
        null,
        List.of(new SimpleGrantedAuthority("FACTOR_PASSWORD")));
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
