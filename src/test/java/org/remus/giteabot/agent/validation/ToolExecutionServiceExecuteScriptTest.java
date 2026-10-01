package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code execute} validation tool: a script committed inside the
 * repository is the validation step, addressed by its repository-relative path,
 * with the exit code as the success contract and the workspace path guard as the
 * security boundary.
 */
class ToolExecutionServiceExecuteScriptTest {

    @TempDir
    Path workspace;

    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        service = newService(new AgentConfigProperties());
    }

    private static ToolExecutionService newService(AgentConfigProperties config) {
        return new ToolExecutionService(config, new ToolCatalog(config), new WorkspaceService());
    }

    private void writeScript(String relativePath, String content, boolean executable) throws IOException {
        Path script = workspace.resolve(relativePath);
        Files.createDirectories(script.getParent());
        Files.writeString(script, content);
        assertThat(script.toFile().setExecutable(executable))
                .as("could not set the executable bit on the test script")
                .isTrue();
    }

    @Test
    void execute_scriptExitsZero_reportsSuccessWithCapturedOutput() throws IOException {
        // The marker file only resolves when the script's working directory is the workspace.
        Files.writeString(workspace.resolve("marker.txt"), "present");
        writeScript("scripts/validate.sh",
                "#!/bin/sh\ntest -f marker.txt && echo 'cwd-ok; validated docs'\nexit 0\n", true);

        ToolResult result = service.executeTool(workspace, "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("cwd-ok; validated docs");
    }

    @Test
    void execute_scriptExitsNonZero_reportsFailureWithCapturedStderr() throws IOException {
        writeScript("scripts/validate.sh",
                "#!/bin/sh\necho 'lint error: README.md:12' 1>&2\nexit 3\n", true);

        ToolResult result = service.executeTool(workspace, "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).contains("lint error: README.md:12");
    }

    @Test
    void execute_extraArgumentsAreForwardedToTheScript() throws IOException {
        writeScript("scripts/validate.sh", "#!/bin/sh\necho \"args:$@\"\n", true);

        ToolResult result = service.executeTool(workspace, "execute",
                List.of("scripts/validate.sh", "--strict", "docs/"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("args:--strict docs/");
    }

    @Test
    void execute_missingScript_reportsClearError() {
        ToolResult result = service.executeTool(workspace, "execute", List.of("scripts/does-not-exist.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("not found inside the repository")
                .contains("scripts/does-not-exist.sh");
    }

    @Test
    void execute_scriptWithoutExecutableBit_reportsClearError() throws IOException {
        writeScript("scripts/validate.sh", "#!/bin/sh\nexit 0\n", false);

        ToolResult result = service.executeTool(workspace, "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not executable").contains("chmod +x");
    }

    @Test
    void execute_pathTraversalOutOfTheRepository_isRejected() {
        ToolResult result = service.executeTool(workspace, "execute", List.of("../../etc/passwd"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("escapes");
    }

    @Test
    void execute_absolutePath_isRejected() {
        ToolResult result = service.executeTool(workspace, "execute", List.of("/etc/passwd"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("must be relative");
    }

    @Test
    void execute_gitInternalsPath_isRejected() {
        ToolResult result = service.executeTool(workspace, "execute", List.of(".git/hooks/pre-commit"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains(".git internals");
    }

    @Test
    void execute_missingPathArgument_reportsClearError() {
        assertThat(service.executeTool(workspace, "execute", List.of()).error())
                .contains("requires the repository-relative path");
        assertThat(service.executeTool(workspace, "execute", List.of("   ")).error())
                .contains("requires the repository-relative path");
    }

    @Test
    void execute_isRejectedWhenTheToolIsNotConfigured() {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getValidation().setAvailableTools(List.of("mvn"));

        ToolResult result = newService(config)
                .executeTool(workspace, "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("is not available");
    }
}
