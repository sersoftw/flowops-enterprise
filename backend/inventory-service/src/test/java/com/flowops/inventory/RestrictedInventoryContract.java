package com.flowops.inventory;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

abstract class RestrictedInventoryContract extends InventoryPersistenceContract {
    @Test void migrationsAndQueriesUseOnlyTheConfiguredOwnedSchema() {
        assertEquals(schemaName, jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(schemaName + "_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(false, jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls "
                + "FROM pg_roles WHERE rolname = current_user", Boolean.class));
        assertEquals(false, jdbc.queryForObject(
                "SELECT has_database_privilege(current_user, current_database(), 'CREATE')", Boolean.class));
        assertEquals(true, jdbc.queryForObject(
                "SELECT pg_get_userbyid(nspowner) = current_user FROM pg_namespace WHERE nspname = ?",
                Boolean.class, schemaName));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname = ? "
                + "AND tableowner = current_user AND tablename IN "
                + "('product','stock','reservation','reservation_line','bootstrap_marker','flyway_schema_history')",
                Integer.class, schemaName));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname <> ? "
                + "AND tablename IN ('product','stock','reservation','reservation_line')", Integer.class, schemaName));
    }

    @Test void foreignSchemasAndPublicAreInaccessible() {
        // These identifiers are fixed test constants, not externally supplied configuration.
        var foreignInventory = schemaName.equals("inventory") ? "inventory_alt" : "inventory";
        for (var foreign : List.of(foreignInventory, "orders", "payments", "public")) {
            assertEquals(false, jdbc.queryForObject(
                    "SELECT has_schema_privilege(current_user, ?, 'USAGE') OR has_schema_privilege(current_user, ?, 'CREATE')",
                    Boolean.class, foreign, foreign));
            assertPermissionDenied("SELECT * FROM " + foreign + ".denied_probe");
            assertPermissionDenied("CREATE TABLE " + foreign + ".unexpected_write (id INTEGER)");
        }
    }

    private void assertPermissionDenied(String sql) {
        var failure = assertThrows(DataAccessException.class, () -> jdbc.execute(sql));
        var cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals("42501", cause.getSQLState(), "Must fail for insufficient privilege, not a missing object/connection");
    }
}
