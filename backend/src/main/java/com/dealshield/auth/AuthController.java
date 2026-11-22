package com.dealshield.auth;

import com.dealshield.security.VerifiedBuyer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String FLOW = AuthController.class.getName()+".flow";
    private final ObjectProvider<SupabaseAuthClient> clients;
    private final ObjectProvider<BuyerProvisioner> provisioners;
    private final String callback;
    private final String recoveryCallback;
    public AuthController(ObjectProvider<SupabaseAuthClient> clients, ObjectProvider<BuyerProvisioner> provisioners,
                          @Value("${dealshield.auth.callback-url:}") String callback,
                          @Value("${dealshield.auth.recovery-callback-url:}") String recoveryCallback) {
        this.clients=clients; this.provisioners=provisioners; this.callback=callback;
        this.recoveryCallback=recoveryCallback==null || recoveryCallback.isBlank() ? callback.replace("/callback", "/recovery") : recoveryCallback;
    }
    @PostMapping("/login")
    public Map<String,String> login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        validateCredentials(input);
        var client = client();
        try { return establish(client,client.passwordLogin(input.email(),input.password()),request,response); }
        catch (SupabaseAuthClient.AuthFailure e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Unable to sign in"); }
    }
    @PostMapping("/register")
    public ResponseEntity<Map<String,String>> register(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        validateCredentials(input);
        try {
            var client = client();
            var registration = client.register(input.email(), input.password());
            if (registration.confirmationRequired()) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "confirmation_required"));
            }
            var body = new java.util.HashMap<>(establish(client, registration.tokens(), request, response));
            body.put("status", "signed_in");
            return ResponseEntity.ok(body);
        } catch (SupabaseAuthClient.AuthFailure e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unable to register");
        }
    }
    @PostMapping("/password-recovery")
    public ResponseEntity<Void> passwordRecovery(@RequestBody EmailRequest input) {
        validateEmail(input);
        try {
            var target = actionCallback(recoveryCallback, "/api/v1/auth/recovery");
            client().requestPasswordRecovery(input.email(), target.toString());
        } catch (SupabaseAuthClient.AuthFailure e) {
            // Do not reveal whether an account exists or which identity it has.
        }
        return ResponseEntity.accepted().build();
    }
    @GetMapping("/recovery")
    public ResponseEntity<Void> recovery(@RequestParam(name="token_hash", required=false) String tokenHash,
                                         @RequestParam(required=false) String type, HttpServletRequest request, HttpServletResponse response) {
        return finishEmailAction(tokenHash, type, "recovery", "/reset-password", request, response);
    }
    @GetMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestParam(name="token_hash", required=false) String tokenHash,
                                        @RequestParam(required=false) String type, HttpServletRequest request, HttpServletResponse response) {
        return finishEmailAction(tokenHash, type, "email", "/dashboard", request, response);
    }
    @PostMapping("/password")
    public ResponseEntity<Void> updatePassword(@RequestBody PasswordRequest input, HttpServletRequest request) {
        if (input.password()==null || input.password().length()<8 || input.password().length()>1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request");
        var session=request.getSession(false);
        if (session==null || !(session.getAttribute(SessionCredentials.KEY) instanceof SessionCredentials credentials)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { client().updatePassword(credentials.accessToken(), input.password()); return ResponseEntity.noContent().build(); }
        catch (SupabaseAuthClient.AuthFailure e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Password update could not be completed"); }
    }
    @PostMapping("/google")
    public Map<String,String> google(HttpServletRequest request) throws Exception {
        var client=client();
        URI uri=actionCallback(callback, "/api/v1/auth/callback");
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        request.getSession(true).setAttribute(FLOW,new GoogleFlow(verifier,Instant.now().plusSeconds(600)));
        return Map.of("url",client.googleAuthorizationUrl(callback,challenge).toString());
    }
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required=false) String code, HttpServletRequest request, HttpServletResponse response) {
        var session=request.getSession(false); Object item=session==null?null:session.getAttribute(FLOW);
        if (session!=null) session.removeAttribute(FLOW);
        try {
            if (!(item instanceof GoogleFlow flow) || !flow.expiresAt().isAfter(Instant.now()) || code==null || code.isBlank() || code.length()>1024) throw new SupabaseAuthClient.AuthFailure();
            var client=client(); establish(client,client.exchangeCode(code,flow.verifier()),request,response);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/dashboard")).build();
        } catch (SupabaseAuthClient.AuthFailure | ResponseStatusException e) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/login?error=signin")).build();
        }
    }
    @GetMapping("/me") public Map<String,String> me(org.springframework.security.core.Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof VerifiedBuyer buyer)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return Map.of("buyerId",buyer.authUserId().toString());
    }
    private ResponseEntity<Void> finishEmailAction(String tokenHash, String type, String expectedType, String destination, HttpServletRequest request, HttpServletResponse response) {
        try {
            if (tokenHash==null || tokenHash.isBlank() || tokenHash.length()>2048 || !expectedType.equals(type)) throw new SupabaseAuthClient.AuthFailure();
            establish(client(), client().verifyOtp(tokenHash, expectedType), request, response);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").location(URI.create(destination)).build();
        } catch (SupabaseAuthClient.AuthFailure | ResponseStatusException e) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").location(URI.create("/login?error=email-action")).build();
        }
    }
    private Map<String,String> establish(SupabaseAuthClient client, SupabaseAuthClient.TokenResponse tokens, HttpServletRequest request, HttpServletResponse response) {
        UUID id=client.verifyUser(tokens.accessToken());
        if (tokens.expiresIn()==null || tokens.expiresIn()<1) throw new SupabaseAuthClient.AuthFailure();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new VerifiedBuyer(id),null,List.of()));
        SecurityContextHolder.setContext(context);
        var provisioner=provisioners.getIfAvailable();
        if (provisioner==null) {
            SecurityContextHolder.clearContext();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Buyer database is not configured");
        }
        try { provisioner.ensureBuyer(id); }
        catch (RuntimeException failure) {
            SecurityContextHolder.clearContext();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Buyer profile is not ready");
        }
        var session=request.getSession(false);
        if (session==null) session=request.getSession(true); else request.changeSessionId();
        session.setMaxInactiveInterval(1800);
        session.setAttribute(SessionCredentials.KEY,new SessionCredentials(tokens.accessToken(),id,Instant.now().plusSeconds(Math.min(1800,tokens.expiresIn()))));
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        new HttpSessionCsrfTokenRepository().saveToken(null,request,response);
        return Map.of("buyerId",id.toString());
    }
    private SupabaseAuthClient client() { var client=clients.getIfAvailable(); if (client==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Sign-in is not configured"); return client; }
    private void validateCredentials(LoginRequest input) {
        if (input.email()==null || input.email().isBlank() || input.email().length()>254 || input.password()==null || input.password().length()<8 || input.password().length()>1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request");
    }
    private void validateEmail(EmailRequest input) {
        if (input.email()==null || input.email().isBlank() || input.email().length()>254) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request");
    }
    private URI actionCallback(String value, String expectedPath) {
        URI uri;
        try { uri=URI.create(value); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Email sign-in is not configured"); }
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !expectedPath.equals(uri.getPath()) || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Email sign-in is not configured");
        return uri;
    }
    public record LoginRequest(String email,String password) { @Override public String toString(){return "LoginRequest[redacted]";} }
    public record EmailRequest(String email) { @Override public String toString(){return "EmailRequest[redacted]";} }
    public record PasswordRequest(String password) { @Override public String toString(){return "PasswordRequest[redacted]";} }
    private record GoogleFlow(String verifier,Instant expiresAt) { @Override public String toString(){return "GoogleFlow[redacted]";} }
}
