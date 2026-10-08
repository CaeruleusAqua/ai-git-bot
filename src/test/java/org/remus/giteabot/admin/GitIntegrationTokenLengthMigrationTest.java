package org.remus.giteabot.admin;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitIntegrationTokenLengthMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void tokenMigration_fitsEncryptedBitbucketTokensAndPreservesExistingTokens(String dialect) throws Exception {
        String url = "jdbc:h2:mem:git-integration-token-length-" + dialect + ";DB_CLOSE_DELAY=-1";
        migrateTo(url, "57");
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO git_integrations (name, provider_type, url, token, created_at, updated_at)
                    VALUES ('Existing', 'BITBUCKET', 'https://bitbucket.org', 'stored-token',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/" + dialect + "/V58__git_integration_token_length.sql"));

            try (var result = statement.executeQuery("SELECT token FROM git_integrations WHERE name = 'Existing'")) {
                result.next();
                assertEquals("stored-token", result.getString(1));
            }

            String longToken = "t".repeat(1000);
            try (var update = connection.prepareStatement(
                    "UPDATE git_integrations SET token = ? WHERE name = 'Existing'")) {
                update.setString(1, longToken);
                update.executeUpdate();
            }
            try (var result = statement.executeQuery("SELECT token FROM git_integrations WHERE name = 'Existing'")) {
                result.next();
                assertEquals(longToken, result.getString(1));
            }

            try (var update = connection.prepareStatement(
                    "UPDATE git_integrations SET token = ? WHERE name = 'Existing'")) {
                update.setString(1, "t".repeat(1001));
                assertThrows(SQLException.class, update::executeUpdate);
            }
        }
    }

    private static void migrateTo(String url, String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("filesystem:src/main/resources/db/migration/h2")
                .target(target)
                .load()
                .migrate();
    }
}
