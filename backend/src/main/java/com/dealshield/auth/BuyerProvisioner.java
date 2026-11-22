package com.dealshield.auth;

import com.dealshield.security.BuyerTransaction;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Creates the local buyer boundary only after Supabase has verified the subject. */
@Service
@Profile("database")
public class BuyerProvisioner {
    private final BuyerTransaction transactions;
    private final JdbcTemplate jdbc;

    public BuyerProvisioner(BuyerTransaction transactions, JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public void ensureBuyer(UUID verifiedSubject) {
        transactions.execute(() -> {
            jdbc.update("""
                INSERT INTO dealshield.buyers (id, auth_user_id, display_name)
                VALUES (?, ?, 'Buyer')
                ON CONFLICT (auth_user_id) DO NOTHING
                """, verifiedSubject, verifiedSubject);
            Boolean matches = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM dealshield.buyers
                  WHERE id = ? AND auth_user_id = ?
                )
                """, Boolean.class, verifiedSubject, verifiedSubject);
            if (!Boolean.TRUE.equals(matches)) {
                throw new IllegalStateException("Verified subject is not mapped to the active buyer");
            }
            return null;
        });
    }
}
