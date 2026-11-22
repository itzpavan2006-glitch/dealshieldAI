package com.dealshield.security;

import jakarta.servlet.http.HttpServletResponse;
import com.dealshield.auth.SessionVerificationFilter;
import com.dealshield.auth.SessionCredentials;
import com.dealshield.auth.SupabaseAuthClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** Fail closed: login providers will be added only after their identity verification is ready. */
@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<SupabaseAuthClient> clients) throws Exception {
        var csrfRepository = new HttpSessionCsrfTokenRepository();
        http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health", "/api/v1/auth/csrf", "/api/v1/auth/callback", "/api/v1/auth/confirm", "/api/v1/auth/recovery").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/google", "/api/v1/auth/password-recovery").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .addFilterBefore(new SessionVerificationFilter(clients), AuthorizationFilter.class)
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").addLogoutHandler((request,response,authentication) -> {
                        var session = request.getSession(false);
                        var client = clients.getIfAvailable();
                        if (session != null && client != null && session.getAttribute(SessionCredentials.KEY) instanceof SessionCredentials credentials) client.logout(credentials.accessToken());
                    }).invalidateHttpSession(true).deleteCookies("JSESSIONID")
                    .logoutSuccessHandler((request, response, auth) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
            .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
            .headers(Customizer.withDefaults());
        return http.build();
    }
}
