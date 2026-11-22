package com.dealshield.security;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/** Construct only after server-side authentication has verified the Supabase user. */
public record VerifiedBuyer(UUID authUserId) implements Principal {
    public VerifiedBuyer { Objects.requireNonNull(authUserId); }
    @Override public String getName() { return authUserId.toString(); }
}
