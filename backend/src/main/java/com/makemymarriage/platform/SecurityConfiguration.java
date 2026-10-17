package com.makemymarriage.platform;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ObjectMapper mapper, AccountSessionValidator validator) throws Exception {
    return http.authorizeHttpRequests(
            authorize ->
                authorize
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/email/verify",
                        "/api/v1/auth/email/verification-requests",
                        "/api/v1/auth/password/forgot")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/password/reset")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .requestCache(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("SESSION")
                    .logoutSuccessHandler(
                        (request, response, authentication) -> {
                          response.setStatus(HttpStatus.NO_CONTENT.value());
                          response.setHeader("Cache-Control", "no-store");
                        }))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            ApiProblems.write(
                                request,
                                response,
                                mapper,
                                HttpStatus.UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED",
                                "Authentication is required."))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            ApiProblems.write(
                                request,
                                response,
                                mapper,
                                HttpStatus.FORBIDDEN,
                                exception instanceof CsrfException
                                    ? "CSRF_INVALID"
                                    : "ACCESS_DENIED",
                                exception instanceof CsrfException
                                    ? "A valid CSRF token is required."
                                    : "Access is denied.")))
        .addFilterAfter(
            new AccountSessionValidationFilter(validator, mapper), AuthorizationFilter.class)
        .build();
  }
}
