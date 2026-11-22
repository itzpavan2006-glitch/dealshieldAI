package com.dealshield.database;

import org.flywaydb.core.Flyway;
import com.dealshield.DealShieldApplication;
import com.dealshield.security.BuyerTransaction;
import com.dealshield.security.VerifiedBuyer;
import com.dealshield.auth.BuyerProvisioner;
import com.dealshield.buyer.BuyerDashboardController;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly local-only disposable PostgreSQL verification; never point this at a real database. */
@EnabledIfEnvironmentVariable(named="DEALSHIELD_DISPOSABLE_PORT", matches="[0-9]{4,5}")
class PortableDatabaseTest {
    @Test void migrationAndIsolation() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getenv("DEALSHIELD_DISPOSABLE_PORT") + "/postgres";
        String password = System.getenv("DEALSHIELD_DISPOSABLE_PASSWORD");
        assertNotNull(password);
        try (var c = DriverManager.getConnection(url, "postgres", password); var s = c.createStatement()) {
            s.execute("CREATE ROLE anon NOLOGIN; CREATE ROLE authenticated NOLOGIN");
        }
        Flyway.configure().dataSource(url,"postgres",password).schemas("dealshield").defaultSchema("dealshield").target("1").load().migrate();
        var proposalPath = Files.createTempDirectory("dealshield-v2-test-");
        Files.copy(java.nio.file.Path.of("src/main/resources/db/migration/V1__database_foundation.sql"), proposalPath.resolve("V1__database_foundation.sql"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(java.nio.file.Path.of("../docs/proposals/V2__buyer_provisioning.sql"), proposalPath.resolve("V2__buyer_provisioning.sql"), StandardCopyOption.REPLACE_EXISTING);
        var proposalResult = Flyway.configure().dataSource(url,"postgres",password).schemas("dealshield").defaultSchema("dealshield").locations("filesystem:" + proposalPath.toAbsolutePath()).load().migrate();
        assertEquals(1, proposalResult.migrationsExecuted);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), comparison = UUID.randomUUID(), supplier = UUID.randomUUID();
        try (var c = DriverManager.getConnection(url,"postgres",password); var s = c.createStatement()) {
            String[] tables = {"buyers","comparisons","suppliers","quotations","quotation_items","quotation_files","extraction_results","risk_findings","comparison_results","buyer_decisions","audit_events"};
            for (String table : tables) { try (var r = s.executeQuery("SELECT to_regclass('dealshield."+table+"') IS NOT NULL")) { assertTrue(r.next()); assertTrue(r.getBoolean(1),table); } }
            String[] indexes = {"comparisons_buyer_created_idx","suppliers_buyer_idx","quotations_supplier_owner_idx","quotations_comparison_owner_idx","quotation_items_parent_idx","quotation_files_parent_idx","extraction_results_parent_idx","risk_findings_parent_idx","comparison_results_parent_idx","buyer_decisions_quote_idx","audit_events_buyer_created_idx"};
            for (String index : indexes) { try (var r = s.executeQuery("SELECT to_regclass('dealshield."+index+"') IS NOT NULL")) { assertTrue(r.next()); assertTrue(r.getBoolean(1),index); } }
            try (var r = s.executeQuery("SELECT count(*), count(*) FILTER (WHERE relrowsecurity AND relforcerowsecurity) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='dealshield' AND c.relkind='r' AND c.relname <> 'flyway_schema_history'")) { assertTrue(r.next()); assertEquals(11,r.getInt(1)); assertEquals(11,r.getInt(2)); }
            try (var r = s.executeQuery("SELECT rolcanlogin, rolsuper, rolbypassrls FROM pg_roles WHERE rolname='dealshield_app'")) { assertTrue(r.next()); assertFalse(r.getBoolean(1)); assertFalse(r.getBoolean(2)); assertFalse(r.getBoolean(3)); }
            try (var r = s.executeQuery("SELECT count(*) FROM pg_policies WHERE schemaname='dealshield' AND policyname='buyer_isolation'")) { assertTrue(r.next()); assertEquals(11,r.getInt(1)); }
            try (var r = s.executeQuery("SELECT count(*) FILTER (WHERE contype='f'), count(*) FILTER (WHERE contype='c'), count(*) FILTER (WHERE contype='p') FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='dealshield'")) { assertTrue(r.next()); assertTrue(r.getInt(1)>=12); assertTrue(r.getInt(2)>=15); assertEquals(12,r.getInt(3)); }
            try (var r = s.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='dealshield'")) { assertTrue(r.next()); assertTrue(r.getInt(1)>=20); }
            try (var r = s.executeQuery("SELECT has_schema_privilege('anon','dealshield','USAGE'), has_schema_privilege('authenticated','dealshield','USAGE')")) { assertTrue(r.next()); assertFalse(r.getBoolean(1)); assertFalse(r.getBoolean(2)); }
            s.execute("CREATE ROLE dealshield_test_login LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+password+"'");
            s.execute("GRANT dealshield_app TO dealshield_test_login");
            try (var r = s.executeQuery("SELECT rolcanlogin, rolsuper, rolbypassrls, pg_has_role('dealshield_test_login','dealshield_app','MEMBER') FROM pg_roles WHERE rolname='dealshield_test_login'")) { assertTrue(r.next()); assertTrue(r.getBoolean(1)); assertFalse(r.getBoolean(2)); assertFalse(r.getBoolean(3)); assertTrue(r.getBoolean(4)); }
            try (var context = new SpringApplicationBuilder(DealShieldApplication.class).profiles("database").run("--spring.datasource.url="+url,"--spring.datasource.username=dealshield_test_login","--spring.datasource.password="+password,"--server.port=0","--spring.main.log-startup-info=false")) { assertNotNull(context.getBean(com.dealshield.config.DatabaseRoleGuard.class)); }
            s.execute("INSERT INTO dealshield.buyers (id,auth_user_id,display_name) VALUES ('"+a+"',gen_random_uuid(),'A'),('"+b+"',gen_random_uuid(),'B')");
            s.execute("INSERT INTO dealshield.comparisons (id,buyer_id,title) VALUES ('"+comparison+"','"+a+"','A'),(gen_random_uuid(),'"+b+"','B')");
            s.execute("INSERT INTO dealshield.suppliers (id,buyer_id,supplier_name) VALUES ('"+supplier+"','"+b+"','B')");
            c.setAutoCommit(false); s.execute("SET LOCAL ROLE dealshield_test_login");
            try (var r = s.executeQuery("SELECT count(*) FROM dealshield.comparisons")) { assertTrue(r.next()); assertEquals(0,r.getInt(1)); }
            s.execute("SELECT set_config('dealshield.buyer_id','"+a+"',true)");
            try (var r = s.executeQuery("SELECT title FROM dealshield.comparisons")) { assertTrue(r.next()); assertEquals("A",r.getString(1)); assertFalse(r.next()); }
            assertEquals(0,s.executeUpdate("UPDATE dealshield.comparisons SET title='bad' WHERE buyer_id='"+b+"'"));
            var sp=c.setSavepoint();
            assertThrows(SQLException.class,()->s.execute("INSERT INTO dealshield.quotations (buyer_id,comparison_id,supplier_id,slot) VALUES ('"+a+"','"+comparison+"','"+supplier+"',1)")); c.rollback(sp);
            assertThrows(SQLException.class,()->s.execute("INSERT INTO dealshield.comparisons (buyer_id,title) VALUES ('"+b+"','forbidden')")); c.rollback();
            c.setAutoCommit(false); s.execute("SET LOCAL ROLE dealshield_test_login");
            try (var r = s.executeQuery("SELECT count(*) FROM dealshield.comparisons")) { assertTrue(r.next()); assertEquals(0,r.getInt(1)); } c.rollback();
            c.setAutoCommit(true);
            try (var pool = new HikariDataSource()) {
                pool.setJdbcUrl(url); pool.setUsername("dealshield_test_login"); pool.setPassword(password); pool.setMaximumPoolSize(1);
                var jdbc = new JdbcTemplate(pool);
                var buyerTransaction = new BuyerTransaction(jdbc, new DataSourceTransactionManager(pool));
                var buyerProvisioner = new BuyerProvisioner(buyerTransaction, jdbc);
                assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> buyerTransaction.execute(() -> 1));
                UUID provisioned = UUID.randomUUID();
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new VerifiedBuyer(provisioned), null, java.util.List.of()));
                buyerProvisioner.ensureBuyer(provisioned);
                assertEquals(provisioned, buyerTransaction.execute(() -> jdbc.queryForObject("SELECT id FROM dealshield.buyers", UUID.class)));
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new VerifiedBuyer(a), null, java.util.List.of()));
                assertEquals("A", buyerTransaction.execute(() -> jdbc.queryForObject("SELECT title FROM dealshield.comparisons",String.class)));
                var dashboard = new BuyerDashboardController(buyerTransaction, jdbc).dashboard(SecurityContextHolder.getContext().getAuthentication());
                assertEquals(a, dashboard.buyerId());
                assertEquals("A", dashboard.displayName());
                assertEquals(1, dashboard.comparisonCount());
                assertEquals(0, dashboard.quotationCount());
                assertThrows(IllegalStateException.class, () -> buyerTransaction.execute(() -> buyerTransaction.execute(() -> 1)));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dealshield.comparisons",Integer.class));
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new VerifiedBuyer(b), null, java.util.List.of()));
                assertEquals("B", buyerTransaction.execute(() -> jdbc.queryForObject("SELECT title FROM dealshield.comparisons",String.class)));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dealshield.comparisons",Integer.class));
                SecurityContextHolder.clearContext();
            }
            s.execute("CREATE ROLE flyway_test_owner LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + password + "'");
            s.execute("ALTER SCHEMA dealshield OWNER TO flyway_test_owner");
            s.execute("ALTER TABLE dealshield.flyway_schema_history OWNER TO flyway_test_owner");
            s.execute("ALTER TABLE dealshield.flyway_schema_history ENABLE ROW LEVEL SECURITY");
            var migrationPath = Files.createTempDirectory("dealshield-flyway-test-");
            Files.copy(java.nio.file.Path.of("src/main/resources/db/migration/V1__database_foundation.sql"), migrationPath.resolve("V1__database_foundation.sql"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(java.nio.file.Path.of("../docs/proposals/V2__buyer_provisioning.sql"), migrationPath.resolve("V2__buyer_provisioning.sql"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(java.nio.file.Path.of("../docs/proposals/V3__product_requirements.sql"), migrationPath.resolve("V3__product_requirements.sql"), StandardCopyOption.REPLACE_EXISTING);
            var result = Flyway.configure().dataSource(url,"postgres",password).schemas("dealshield").defaultSchema("dealshield").locations("filesystem:" + migrationPath.toAbsolutePath()).load().migrate();
            assertEquals(1, result.migrationsExecuted);

            // Phase 5 V3 remains proposal-only: validate it here against this disposable database.
            try (var r = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='dealshield' AND table_name IN ('comparison_requirements','requirement_specs')")) { assertTrue(r.next()); assertEquals(2,r.getInt(1)); }
            try (var r = s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='dealshield' AND table_name='quotation_items' AND column_name IN ('product_name','model','specifications','extraction_confidence')")) { assertTrue(r.next()); assertEquals(4,r.getInt(1)); }
            try (var r = s.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='dealshield' AND c.relname IN ('comparison_requirements','requirement_specs') AND c.relrowsecurity AND c.relforcerowsecurity")) { assertTrue(r.next()); assertEquals(2,r.getInt(1)); }
            try (var r = s.executeQuery("SELECT count(*) FROM pg_policies WHERE schemaname='dealshield' AND tablename IN ('comparison_requirements','requirement_specs') AND policyname='buyer_isolation' AND roles='{dealshield_app}'")) { assertTrue(r.next()); assertEquals(2,r.getInt(1)); }
            try (var r = s.executeQuery("SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='dealshield' AND c.conrelid IN ('dealshield.comparison_requirements'::regclass,'dealshield.requirement_specs'::regclass) AND c.contype='f'")) { assertTrue(r.next()); assertEquals(2,r.getInt(1)); }
            try (var r = s.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='dealshield' AND indexname IN ('comparison_requirements_buyer_idx','requirement_specs_parent_idx')")) { assertTrue(r.next()); assertEquals(2,r.getInt(1)); }
            c.setAutoCommit(false); s.execute("SET LOCAL ROLE dealshield_test_login"); s.execute("SELECT set_config('dealshield.buyer_id','"+a+"',true)");
            UUID requirements = UUID.randomUUID();
            assertEquals(1,s.executeUpdate("INSERT INTO dealshield.comparison_requirements (id,buyer_id,comparison_id,product_name,required_quantity,currency) VALUES ('"+requirements+"','"+a+"','"+comparison+"','Laptop',2,'INR')"));
            assertEquals(1,s.executeUpdate("INSERT INTO dealshield.requirement_specs (buyer_id,requirements_id,spec_key,operator,expected_value) VALUES ('"+a+"','"+requirements+"','RAM','MINIMUM','16 GB')"));
            assertEquals(0,s.executeUpdate("INSERT INTO dealshield.comparison_requirements (buyer_id,comparison_id,product_name,required_quantity,currency) SELECT '"+b+"',id,'Forbidden',1,'INR' FROM dealshield.comparisons WHERE buyer_id='"+b+"'"));
            try (var r = s.executeQuery("SELECT count(*) FROM dealshield.comparison_requirements")) { assertTrue(r.next()); assertEquals(1,r.getInt(1)); }
            c.rollback(); c.setAutoCommit(true);
        }
    }
}
