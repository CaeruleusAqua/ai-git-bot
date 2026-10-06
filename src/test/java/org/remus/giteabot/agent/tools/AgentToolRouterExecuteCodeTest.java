package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.codeexecution.CodeExecutionScope;
import org.remus.giteabot.agent.codeexecution.PythonExecutionOutcome;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The nested half of the router: what a sandboxed program's tool call does.
 *
 * <p>The point of these tests is that a program gets no second dispatch path. A nested call goes
 * through {@code execute} like any other, so the whitelist gates it, the arguments arrive flattened
 * where the executors expect them, and {@code execute-code} itself is out of reach — a program
 * cannot nest sandboxes.</p>
 *
 * <p>{@code execute-code} has to be on the whitelist in these tests, exactly as it does in
 * production: the whitelist is checked before anything else, so a bot that has not selected the
 * tool cannot reach the sandbox at all.</p>
 */
class AgentToolRouterExecuteCodeTest {

    private static final AgentConfigProperties CONFIG = new AgentConfigProperties();

    /** A stand-in for the sandbox: it makes one nested call and hands the answer straight back. */
    private static final class Program implements PythonExecutionService {

        private final String tool;
        private final String arguments;
        private ToolResult nested;
        private List<ToolDescriptor> advertised;

        Program(String tool, String arguments) {
            this.tool = tool;
            this.arguments = arguments;
        }

        @Override
        public PythonExecutionOutcome execute(String code, CodeExecutionScope scope) {
            this.advertised = scope.available();
            this.nested = scope.executor().call(tool, node(arguments));
            return new PythonExecutionOutcome(nested.success(), nested.exitCode(), nested.output(),
                    nested.error());
        }
    }

    private static JsonNode node(String json) {
        return AgentJackson.mapper().readTree(json);
    }

    private static ToolCallContext context(Path workspace, String... args) {
        return new ToolCallContext("owner", "repo", 1L, workspace,
                ImplementationPlan.ToolRequest.builder()
                        .id("tool-1")
                        .tool("execute-code")
                        .args(List.of(args))
                        .build(),
                null);
    }

    private static AgentToolRouter router(ToolExecutionService tools, Set<String> allowed,
                                         PythonExecutionService sandbox) {
        return new AgentToolRouter(tools, new ToolCatalog(CONFIG), mock(McpOrchestrationService.class),
                null, McpToolCatalog.empty(), mock(RepositoryApiClient.class), allowed, sandbox);
    }

    private static Set<String> botWithExecuteCode() {
        return Set.of("execute-code", "cat");
    }

    @Test
    void aProgramsToolCallReachesTheSurfaceDispatch() throws IOException {
        Path workspace = Files.createTempDirectory("router-nested");
        Files.writeString(workspace.resolve("Main.java"), "class Main {}\n");
        ToolExecutionService tools =
                new ToolExecutionService(CONFIG, new ToolCatalog(CONFIG), mock(WorkspaceService.class));
        Program program = new Program("cat", "{\"path\": \"Main.java\"}");

        ToolResult result = router(tools, botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        // The file's contents came back, so cat received the path flattened out of the JSON.
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("class Main {}");
        // What the program sees is what the model would have seen.
        assertThat(program.nested.output()).contains("class Main {}");
    }

    @Test
    void aProgramSeesTheSurfaceAdvertisedTools() throws IOException {
        Path workspace = Files.createTempDirectory("router-advert");
        Program program = new Program("cat", "{\"path\": \"Main.java\"}");

        router(mock(ToolExecutionService.class), botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        assertThat(program.advertised).extracting("name").contains("cat");
    }

    @Test
    void aProgramCannotNestSandboxes() throws IOException {
        Path workspace = Files.createTempDirectory("router-recursion");
        ToolExecutionService tools = mock(ToolExecutionService.class);
        Program program = new Program("execute-code", "{\"code\": \"print(2)\"}");

        router(tools, botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        assertThat(program.nested.success()).isFalse();
        assertThat(program.nested.error()).contains("cannot be called from a program");
        verifyNoInteractions(tools);
    }

    @Test
    void theBotWhitelistGatesProgramsToo() throws IOException {
        Path workspace = Files.createTempDirectory("router-whitelist");
        ToolExecutionService tools = mock(ToolExecutionService.class);
        Program program = new Program("rg", "{\"pattern\": \"Main\"}");

        router(tools, botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        assertThat(program.nested.success()).isFalse();
        verifyNoInteractions(tools);
    }

    @Test
    void aDeploymentWithoutASandboxSaysSo() throws IOException {
        ToolResult result = router(mock(ToolExecutionService.class), botWithExecuteCode(), null)
                .execute(AgentToolRouter.Mode.CODING,
                        context(Files.createTempDirectory("router-nosandbox"), "print(1)"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not available in this deployment");
    }

    @Test
    void theProgramIsTheFirstArgument() throws IOException {
        ToolResult result = router(mock(ToolExecutionService.class), botWithExecuteCode(),
                new Program("cat", "{\"path\": \"Main.java\"}"))
                .execute(AgentToolRouter.Mode.CODING,
                        context(Files.createTempDirectory("router-noarg")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("needs the Python program as its first argument");
    }

    @Test
    void aBotWithoutTheToolNeverReachesTheSandbox() throws IOException {
        Program program = new Program("cat", "{\"path\": \"Main.java\"}");

        ToolResult result = router(mock(ToolExecutionService.class), Set.of("cat"), program)
                .execute(AgentToolRouter.Mode.CODING,
                        context(Files.createTempDirectory("router-unselected"), "print(1)"));

        assertThat(result.success()).isFalse();
        assertThat(program.advertised).isNull();
    }
}
