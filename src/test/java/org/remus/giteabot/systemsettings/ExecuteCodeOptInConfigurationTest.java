package org.remus.giteabot.systemsettings;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code execution must not be seeded into the Default tool configuration, and no migration may change
 * that. The Default configuration cannot be edited afterwards ({@code BotToolSelectionService}
 * rejects it), so a seeded row is permanent there — it would make a program that runs as this
 * service user inside this container, able to read its start-time environment, opt-out rather than
 * opt-in for every bot that never configured its own tools.
 *
 * <p>Running code stays an operator decision: the bot's tool selection is the per-bot opt-in and
 * {@code agent.code-execution.enabled} (default {@code false}) is the deployment switch.</p>
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway disabled) that migrates the
 * whole chain. No version is pinned, so the test cannot rot when the next migration is added.</p>
 */
class ExecuteCodeOptInConfigurationTest {

    private static final String URL = "jdbc:h2:mem:execute-code-opt-in;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    @Test
    void defaultToolConfigurationDoesNotSelectExecuteCode() throws Exception {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations(LOCATIONS)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            assertThat(selectedInDefault(connection))
                    .as("execute-code is not seeded into the Default configuration")
                    .isZero();
        }
    }

    private static int selectedInDefault(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM bot_tool_selections s "
                             + "JOIN bot_tool_configurations c ON s.configuration_id = c.id "
                             + "WHERE c.default_entry = TRUE AND s.tool_name = 'execute-code'")) {
            result.next();
            return result.getInt(1);
        }
    }
}
