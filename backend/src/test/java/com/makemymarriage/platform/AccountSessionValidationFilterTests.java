package com.makemymarriage.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class AccountSessionValidationFilterTests {
  @Test
  void publicAuthenticationAndHealthRequestsDoNotQueryAccountStanding() throws Exception {
    AccountSessionValidator validator = org.mockito.Mockito.mock(AccountSessionValidator.class);
    var filter =
        new AccountSessionValidationFilter(validator, org.mockito.Mockito.mock(ObjectMapper.class));

    for (String path :
        new String[] {"/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health"}) {
      var request = new MockHttpServletRequest("GET", path);
      request.setServletPath(path);
      var chain = new MockFilterChain();
      filter.doFilter(request, new MockHttpServletResponse(), chain);
      assertThat(chain.getRequest()).isSameAs(request);
    }
    verifyNoInteractions(validator);
  }
}
