package com.dealshield.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** Server-only transport; tokens must never be returned to the browser or logged. */
public final class SupabaseAuthClient {
    private final String projectUrl;
    private final RestClient client;
    public SupabaseAuthClient(String projectUrl, String publishableKey, RestClient.Builder builder) {
        URI uri = URI.create(projectUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new IllegalArgumentException("Invalid Supabase project URL");
        if (publishableKey == null || publishableKey.isBlank()) throw new IllegalArgumentException("Publishable key required");
        this.projectUrl = projectUrl.replaceAll("/+$", "");
        this.client = builder.baseUrl(this.projectUrl + "/auth/v1").defaultHeader("apikey", publishableKey).build();
    }
    public TokenResponse passwordLogin(String email, String password) {
        return exchange("password", Map.of("email",email,"password",password));
    }
    public RegistrationResult register(String email, String password) {
        try {
            var result = client.post().uri("/signup").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", password)).retrieve().body(TokenResponse.class);
            if (result == null) throw new AuthFailure();
            if (result.accessToken() == null || result.accessToken().isBlank()) return new RegistrationResult(null, true);
            return new RegistrationResult(result, false);
        } catch (RestClientException e) { throw new AuthFailure(); }
    }
    public void requestPasswordRecovery(String email, String redirectTo) {
        try {
            client.post().uri("/recover").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "redirect_to", redirectTo)).retrieve().toBodilessEntity();
        } catch (RestClientException e) { throw new AuthFailure(); }
    }
    public TokenResponse verifyOtp(String tokenHash, String type) {
        try {
            var result = client.post().uri("/verify").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token_hash", tokenHash, "type", type)).retrieve().body(TokenResponse.class);
            if (result == null || result.accessToken() == null || result.accessToken().isBlank()) throw new AuthFailure();
            return result;
        } catch (RestClientException e) { throw new AuthFailure(); }
    }
    public void updatePassword(String accessToken, String password) {
        try {
            client.put().uri("/user").contentType(MediaType.APPLICATION_JSON).headers(h -> h.setBearerAuth(accessToken))
                    .body(Map.of("password", password)).retrieve().toBodilessEntity();
        } catch (RestClientException e) { throw new AuthFailure(); }
    }
    public TokenResponse exchangeCode(String code, String verifier) {
        return exchange("pkce", Map.of("auth_code",code,"code_verifier",verifier));
    }
    private TokenResponse exchange(String grant, Map<String,String> body) {
        try {
            var result = client.post().uri("/token?grant_type=" + grant).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(TokenResponse.class);
            if (result == null || result.accessToken() == null || result.accessToken().isBlank()) throw new AuthFailure();
            return result;
        } catch (RestClientException e) { throw new AuthFailure(); }
    }
    public UUID verifyUser(String accessToken) {
        try {
            var user = client.get().uri("/user").headers(h -> h.setBearerAuth(accessToken)).retrieve().body(UserResponse.class);
            if (user == null || user.id() == null) throw new AuthFailure();
            // Supabase Auth verifies the signature before returning /user. Check the token's
            // intended project, audience, expiry and subject against that verified response.
            var claims = SignedJWT.parse(accessToken).getJWTClaimsSet();
            if (!(projectUrl + "/auth/v1").equals(claims.getIssuer()) || !claims.getAudience().contains("authenticated") || claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(Instant.now()) || !user.id().equals(claims.getSubject())) throw new AuthFailure();
            return UUID.fromString(user.id());
        } catch (RestClientException | java.text.ParseException | IllegalArgumentException e) { throw new AuthFailure(); }
    }
    public URI googleAuthorizationUrl(String callback, String challenge) {
        return UriComponentsBuilder.fromUriString(projectUrl + "/auth/v1/authorize")
                .queryParam("provider","google").queryParam("redirect_to",callback)
                .queryParam("code_challenge",challenge).queryParam("code_challenge_method","s256").build().encode().toUri();
    }
    public void logout(String accessToken) {
        try { client.post().uri("/logout?scope=local").headers(h -> h.setBearerAuth(accessToken)).retrieve().toBodilessEntity(); }
        catch (RestClientException ignored) { /* Always invalidate the local session. */ }
    }
    public record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("refresh_token") String refreshToken, @JsonProperty("expires_in") Long expiresIn) {
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }
    public record RegistrationResult(TokenResponse tokens, boolean confirmationRequired) {
        @Override public String toString() { return "RegistrationResult[redacted]"; }
    }
    private record UserResponse(String id) { }
    public static final class AuthFailure extends RuntimeException {
        public AuthFailure() { super("Authentication failed"); }
    }
}
