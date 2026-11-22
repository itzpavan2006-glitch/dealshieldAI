package com.dealshield.database;

import com.dealshield.config.DatabaseRoleGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named = "DEALSHIELD_LIVE_RUNTIME_VERIFY", matches = "true")
class LiveRuntimeConnectionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseRoleGuard roleGuard;

    @Test void restrictedRuntimeConnectsThroughSpringBoot() {
        assertNotNull(roleGuard);
        assertEquals("dealshield_runtime", jdbc.queryForObject("select current_user", String.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("select rolsuper or rolcreaterole or rolcreatedb or rolbypassrls from pg_roles where rolname=current_user", Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select pg_has_role(current_user, 'dealshield_app', 'member')", Boolean.class));
    }
}
