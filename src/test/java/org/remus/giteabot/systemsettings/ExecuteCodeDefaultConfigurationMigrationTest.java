package org.remus.giteabot.systemsettings;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration gate for V56: the Default tool configuration must select {@code execute-code}.
 * A missing row means the tool is invisible to the model, and the Default configuration cannot
 * be edited afterwards ({@code BotToolSelectionService} rejects it), so no admin can repair it
 * from the UI — the migration is the only chance to get it right.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway disabled) that migrates
 * the whole chain and asserts the selection row. No version is pinned, so the test cannot rot when
 * the next migration is added.</p>
 */
class ExecuteCodeDefaultConfigurationMigrationTest {

    private static final String URL = "jdbc:h2:mem:execute-code-default-config;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    @Test
    void defaultToolConfigurationSelectsExecuteCodeExactlyOnce() throws Exception {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations(LOCATIONS)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            assertThat(selectedKinds(connection))
                    .as("execute-code is selected in the Default configuration, exactly once, "
                            + "under its own kind")
                    .containsExactly("AGENT_CONTROL");
        }
    }

    private static List<String> selectedKinds(Connection connection) throws Exception {
        List<String> kinds = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT s.tool_kind FROM bot_tool_selections s "
                             + "JOIN bot_tool_configurations c ON s.configuration_id = c.id "
                             + "WHERE c.default_entry = TRUE AND s.tool_name = 'execute-code'")) {
            while (result.next()) {
                kinds.add(result.getString(1));
            }
        }
        return kinds;
    }
}
