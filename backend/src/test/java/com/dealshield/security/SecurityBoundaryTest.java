package com.dealshield.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @Test void protectedEndpointRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/comparisons")).andExpect(status().isUnauthorized());
    }
    @Test void csrfTokenCanBeObtainedWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }
    @Test void logoutRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").with(csrf())).andExpect(status().isNoContent());
    }
}
