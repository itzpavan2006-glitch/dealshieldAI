package com.dealshield.auth;

import com.dealshield.security.VerifiedBuyer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fail closed if a stored session cannot be revalidated with Supabase. */
public class SessionVerificationFilter extends OncePerRequestFilter {
    private final ObjectProvider<SupabaseAuthClient> clients;
    public SessionVerificationFilter(ObjectProvider<SupabaseAuthClient> clients) { this.clients = clients; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            var session = request.getSession(false);
            var credentials = session == null ? null : session.getAttribute(SessionCredentials.KEY);
            var client = clients.getIfAvailable();
            try {
                if (!(authentication.getPrincipal() instanceof VerifiedBuyer buyer) || !(credentials instanceof SessionCredentials c) || client == null || !c.expiresAt().isAfter(Instant.now()) || !buyer.authUserId().equals(c.buyerId()) || !buyer.authUserId().equals(client.verifyUser(c.accessToken()))) throw new SupabaseAuthClient.AuthFailure();
            } catch (SupabaseAuthClient.AuthFailure e) {
                SecurityContextHolder.clearContext();
                if (session != null) session.invalidate();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED); return;
            }
        }
        chain.doFilter(request,response);
    }
}
