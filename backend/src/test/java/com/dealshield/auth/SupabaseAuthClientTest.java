package com.dealshield.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SupabaseAuthClientTest {
    private static final String USER="11111111-1111-4111-8111-111111111111";
    @Test void acceptsOnlyMatchingIdentityAndProjectClaimsAfterServerVerification() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var client=new SupabaseAuthClient("https://example.supabase.co","public-test-key",builder);
        server.expect(once(),requestTo("https://example.supabase.co/auth/v1/user"))
              .andExpect(header("apikey","public-test-key"))
              .andRespond(withSuccess("{\"id\":\""+USER+"\"}",MediaType.APPLICATION_JSON));
        assertEquals(USER,client.verifyUser(token("https://example.supabase.co/auth/v1",USER)).toString()); server.verify();
    }
    @Test void rejectsWrongIssuerEvenWhenAuthServerReturnsAUser() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var client=new SupabaseAuthClient("https://example.supabase.co","public-test-key",builder);
        server.expect(requestTo("https://example.supabase.co/auth/v1/user")).andRespond(withSuccess("{\"id\":\""+USER+"\"}",MediaType.APPLICATION_JSON));
        assertThrows(SupabaseAuthClient.AuthFailure.class,()->client.verifyUser(token("https://wrong.example/auth/v1",USER))); server.verify();
    }
    @Test void neverIncludesTokensInResponseString() {
        assertEquals("TokenResponse[redacted]",new SupabaseAuthClient.TokenResponse("a","b",3600L).toString());
        assertEquals("RegistrationResult[redacted]",new SupabaseAuthClient.RegistrationResult(null,true).toString());
    }
    @Test void registrationCanRequireEmailConfirmationWithoutExposingTokens() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var client=new SupabaseAuthClient("https://example.supabase.co","public-test-key",builder);
        server.expect(requestTo("https://example.supabase.co/auth/v1/signup"))
              .andExpect(method(org.springframework.http.HttpMethod.POST))
              .andRespond(withSuccess("{\"user\":{\"id\":\""+USER+"\"}}",MediaType.APPLICATION_JSON));
        var result=client.register("buyer@example.test","long-test-password");
        assertTrue(result.confirmationRequired()); assertNull(result.tokens()); server.verify();
    }
    private String token(String issuer,String user) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience("authenticated").subject(user).expirationTime(Date.from(Instant.now().plusSeconds(60))).build();
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);
        jwt.sign(new MACSigner(new byte[32])); return jwt.serialize();
    }
}
