package com.makemymarriage.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.makemymarriage.identity.application.AccountPrincipal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;

class PasswordResetSessionCleanupTests {
  @Test
  void indexedRedisFailureDoesNotOverrideCommittedReset() {
    RedisIndexedSessionRepository unavailable = mock(RedisIndexedSessionRepository.class);
    when(unavailable.findByPrincipalName(anyString()))
        .thenThrow(new IllegalStateException("offline"));
    assertThatCode(
            () ->
                new PasswordResetSessionCleanup(unavailable).removeSessions(UUID.randomUUID(), 1L))
        .doesNotThrowAnyException();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void cleanupKeepsNewSessionsWhileDeletingOlderOnes() {
    RedisIndexedSessionRepository repository = mock(RedisIndexedSessionRepository.class);
    UUID id = UUID.randomUUID();
    Session old = mock(Session.class);
    Session fresh = mock(Session.class);
    var oldContext = SecurityContextHolder.createEmptyContext();
    oldContext.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AccountPrincipal(id, 0L), null, java.util.List.of()));
    var freshContext = SecurityContextHolder.createEmptyContext();
    freshContext.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AccountPrincipal(id, 1L), null, java.util.List.of()));
    when(old.getAttribute("SPRING_SECURITY_CONTEXT")).thenReturn(oldContext);
    when(fresh.getAttribute("SPRING_SECURITY_CONTEXT")).thenReturn(freshContext);
    when(repository.findByPrincipalName(id.toString()))
        .thenReturn((Map) Map.of("old", old, "fresh", fresh));
    new PasswordResetSessionCleanup(repository).removeSessions(id, 1L);
    verify(repository).deleteById("old");
    verify(repository, never()).deleteById("fresh");
  }
}
