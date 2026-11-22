package com.dealshield.auth;

import java.time.Instant;
import java.util.UUID;

/** Stored only in the server-side HTTP session. */
public record SessionCredentials(String accessToken, UUID buyerId, Instant expiresAt) {
    public static final String KEY = SessionCredentials.class.getName();
    @Override public String toString() { return "SessionCredentials[redacted]"; }
}
