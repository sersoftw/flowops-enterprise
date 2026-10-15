package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** V2's unnamed check has database-generated names; discover it instead of editing a merged migration. */
public class V4__OrderWorkflowStatus extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String schema = context.getConfiguration().getDefaultSchema();
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid schema");
        String query = """
                SELECT tc.constraint_name FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog
                  AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
                WHERE tc.table_schema=? AND tc.table_name='purchase_order' AND tc.constraint_type='CHECK'
                  AND LOWER(cc.check_clause) LIKE '%status%'
                  AND LOWER(cc.check_clause) LIKE '%pending%'
                """;
        var constraints = new ArrayList<String>();
        try (var statement = connection.prepareStatement(query)) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) { while (rows.next()) constraints.add(rows.getString(1)); }
        }
        if (constraints.size() != 1) throw new IllegalStateException("Expected exactly one previous order status check");
        try (var statement = connection.createStatement()) {
            String table = "\"" + schema + "\".purchase_order";
            statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + constraints.getFirst().replace("\"", "\"\"") + "\"");
            statement.execute("ALTER TABLE " + table + " ADD CONSTRAINT order_workflow_status CHECK "
                    + "(status IN ('PENDING','INVENTORY_RESERVED','INVENTORY_REJECTED'))");
        }
    }
}
