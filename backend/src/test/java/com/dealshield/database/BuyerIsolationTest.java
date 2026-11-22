package com.dealshield.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Executes Flyway in a disposable PostgreSQL container. Run only after migration approval. */
@Testcontainers(disabledWithoutDocker = true)
class BuyerIsolationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void missingAndDifferentBuyerContextsCannotSeeOrWriteOtherBuyers() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE anon NOLOGIN; CREATE ROLE authenticated NOLOGIN");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("dealshield").defaultSchema("dealshield").load().migrate();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), comparisonA = UUID.randomUUID(), supplierB = UUID.randomUUID();
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var s = c.createStatement()) {
            s.execute("INSERT INTO dealshield.buyers (id,auth_user_id,display_name) VALUES ('" + a + "',gen_random_uuid(),'A'),('" + b + "',gen_random_uuid(),'B')");
            s.execute("INSERT INTO dealshield.comparisons (id,buyer_id,title) VALUES ('" + comparisonA + "','" + a + "','A comparison'),(gen_random_uuid(),'" + b + "','B comparison')");
            s.execute("INSERT INTO dealshield.suppliers (id,buyer_id,supplier_name) VALUES ('" + supplierB + "','" + b + "','Supplier B')");
            c.setAutoCommit(false);
            s.execute("SET LOCAL ROLE dealshield_app");
            try (var rows = s.executeQuery("SELECT count(*) FROM dealshield.comparisons")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            s.execute("SELECT set_config('dealshield.buyer_id','" + a + "',true)");
            try (var rows = s.executeQuery("SELECT title FROM dealshield.comparisons")) { assertTrue(rows.next()); assertEquals("A comparison", rows.getString(1)); assertFalse(rows.next()); }
            assertEquals(0, s.executeUpdate("UPDATE dealshield.comparisons SET title='changed' WHERE buyer_id='" + b + "'"));
            var savepoint = c.setSavepoint();
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO dealshield.quotations (buyer_id,comparison_id,supplier_id,slot) VALUES ('" + a + "','" + comparisonA + "','" + supplierB + "',1)"));
            c.rollback(savepoint);
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO dealshield.comparisons (buyer_id,title) VALUES ('" + b + "','forbidden')"));
            c.rollback();
            c.setAutoCommit(false);
            s.execute("SET LOCAL ROLE dealshield_app");
            try (var rows = s.executeQuery("SELECT count(*) FROM dealshield.comparisons")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            c.rollback();
        }
    }
}
