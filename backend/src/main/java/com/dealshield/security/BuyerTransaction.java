package com.dealshield.security;

import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Binds only a server-verified buyer to one new database transaction. */
@Component
@Profile("database")
public class BuyerTransaction {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public BuyerTransaction(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }
    public <T> T execute(Supplier<T> work) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof VerifiedBuyer buyer)) {
            throw new AccessDeniedException("A verified buyer is required");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Buyer transactions cannot join an existing transaction");
        }
        return transaction.execute(status -> {
            jdbc.queryForObject("select set_config('dealshield.buyer_id', ?, true)", String.class, buyer.authUserId().toString());
            return work.get();
        });
    }
}
