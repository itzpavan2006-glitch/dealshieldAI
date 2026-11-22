package com.dealshield.auth;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="dealshield.auth.callback-url=http://localhost:5173/api/v1/auth/callback")
@AutoConfigureMockMvc
class AuthFlowTest {
    @Autowired MockMvc mvc;
    @MockBean SupabaseAuthClient client;
    @MockBean BuyerProvisioner provisioner;
    private final UUID id=UUID.fromString("11111111-1111-4111-8111-111111111111");
    @Test void loginRequiresCsrfAndCreatesVerifiedServerSession() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.test\",\"password\":\"test-only\"}"))
           .andExpect(status().isForbidden());
        when(client.passwordLogin(anyString(),anyString())).thenReturn(new SupabaseAuthClient.TokenResponse("test-token",null,3600L));
        when(client.verifyUser("test-token")).thenReturn(id);
        var session=new MockHttpSession(); String oldId=session.getId();
        var result=mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.test\",\"password\":\"test-only\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buyerId").value(id.toString())).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("test-token"));
        verify(provisioner).ensureBuyer(id);
        assertNotEquals(oldId,session.getId());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.buyerId").value(id.toString()));
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        verify(client).logout("test-token"); assertTrue(session.isInvalid());
    }
    @Test void rejectedOrExpiredRemoteIdentityFailsClosed() throws Exception {
        when(client.passwordLogin(anyString(),anyString())).thenThrow(new SupabaseAuthClient.AuthFailure());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.test\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void registrationSupportsConfirmationAndImmediateSessionFlows() throws Exception {
        when(client.register(anyString(),anyString())).thenReturn(new SupabaseAuthClient.RegistrationResult(null,true));
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.test\",\"password\":\"long-test-password\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("confirmation_required"));

        when(client.register(anyString(),anyString())).thenReturn(new SupabaseAuthClient.RegistrationResult(new SupabaseAuthClient.TokenResponse("registration-token",null,3600L),false));
        when(client.verifyUser("registration-token")).thenReturn(id);
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"b@example.test\",\"password\":\"long-test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("signed_in")).andExpect(jsonPath("$.buyerId").value(id.toString()));
    }
    @Test void expiredServerSessionFailsClosed() throws Exception {
        when(client.passwordLogin(anyString(),anyString())).thenReturn(new SupabaseAuthClient.TokenResponse("test-token",null,3600L));
        when(client.verifyUser("test-token")).thenReturn(id);
        var session=new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.test\",\"password\":\"long-test-password\"}"))
                .andExpect(status().isOk());
        session.setAttribute(SessionCredentials.KEY,new SessionCredentials("test-token",id,java.time.Instant.now().minusSeconds(1)));
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }
    @Test void googleCallbackRequiresSameSessionPkceFlow() throws Exception {
        when(client.googleAuthorizationUrl(anyString(),anyString())).thenReturn(URI.create("https://example.supabase.co/auth/v1/authorize"));
        when(client.exchangeCode(eq("code"),anyString())).thenReturn(new SupabaseAuthClient.TokenResponse("test-token",null,3600L));
        when(client.verifyUser("test-token")).thenReturn(id);
        mvc.perform(get("/api/v1/auth/callback").param("code","code")).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error=signin"));
        var session=new MockHttpSession();
        mvc.perform(post("/api/v1/auth/google").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/callback").session(session).param("code","code")).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/dashboard"));
        mvc.perform(get("/api/v1/auth/callback").session(session).param("code","code")).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error=signin"));
    }

    @Test void provisioningFailureDoesNotCreateSession() throws Exception {
        when(client.passwordLogin(anyString(),anyString())).thenReturn(new SupabaseAuthClient.TokenResponse("test-token",null,3600L));
        when(client.verifyUser("test-token")).thenReturn(id);
        doThrow(new IllegalStateException("migration missing")).when(provisioner).ensureBuyer(id);
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.test\",\"password\":\"test-only\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void recoveryIsGenericAndVerifiedTokenCanEstablishServerSession() throws Exception {
        mvc.perform(post("/api/v1/auth/password-recovery").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"buyer@example.test\"}"))
                .andExpect(status().isAccepted());
        verify(client).requestPasswordRecovery(eq("buyer@example.test"), contains("/api/v1/auth/recovery"));
        when(client.verifyOtp(eq("token-hash"),eq("recovery"))).thenReturn(new SupabaseAuthClient.TokenResponse("recovery-token",null,3600L));
        when(client.verifyUser("recovery-token")).thenReturn(id);
        var session=new MockHttpSession();
        mvc.perform(get("/api/v1/auth/recovery").session(session).param("token_hash","token-hash").param("type","recovery"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/reset-password"))
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/auth/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"new-secure-password\"}"))
                .andExpect(status().isNoContent());
        verify(client).updatePassword("recovery-token","new-secure-password");
    }
}
