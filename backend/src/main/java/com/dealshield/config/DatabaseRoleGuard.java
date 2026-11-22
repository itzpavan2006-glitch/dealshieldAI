package com.dealshield.config;

import java.sql.Connection;
import java.sql.ResultSet;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** Prevent the application from running with an owner, superuser or RLS-bypassing account. */
@Component
@Profile("database")
public class DatabaseRoleGuard implements ApplicationRunner {
    private final DataSource dataSource;
    public DatabaseRoleGuard(DataSource dataSource) { this.dataSource = dataSource; }
    @Override public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select r.rolsuper, r.rolbypassrls, pg_has_role(current_user, 'dealshield_app', 'MEMBER'), exists (select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='dealshield' and c.relkind in ('r','p') and pg_has_role(current_user,c.relowner,'USAGE')) from pg_roles r where r.rolname=current_user")) {
            if (!rs.next() || rs.getBoolean(1) || rs.getBoolean(2) || !rs.getBoolean(3) || rs.getBoolean(4)) {
                throw new IllegalStateException("Database runtime role must be a non-owner member of dealshield_app without superuser or BYPASSRLS privileges");
            }
        }
    }
}
