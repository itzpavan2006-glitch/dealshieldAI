package com.dealshield.buyer;

import com.dealshield.security.BuyerTransaction;
import com.dealshield.security.VerifiedBuyer;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("database")
@RequestMapping("/api/v1/buyer")
public class BuyerDashboardController {
    private final BuyerTransaction transactions;
    private final JdbcTemplate jdbc;

    public BuyerDashboardController(BuyerTransaction transactions, JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof VerifiedBuyer buyer)) {
            throw new org.springframework.security.access.AccessDeniedException("Verified buyer required");
        }
        return transactions.execute(() -> jdbc.queryForObject("""
            SELECT b.id, b.display_name,
                   (SELECT count(*) FROM dealshield.comparisons) AS comparison_count,
                   (SELECT count(*) FROM dealshield.quotations) AS quotation_count
            FROM dealshield.buyers b
            WHERE b.id = ?
            """, (rs, row) -> new Dashboard(
                rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getInt("comparison_count"), rs.getInt("quotation_count")),
            buyer.authUserId()));
    }

    public record Dashboard(UUID buyerId, String displayName, int comparisonCount, int quotationCount) {}
}
