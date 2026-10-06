package org.remus.giteabot.agent.codeexecution;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pin down what a Python program may reach: the read-only subset of what the surface
 * already advertises, minus agent-control tools, with MCP bounded by the operator's deny
 * list — and never a tool that would then fail to run.
 */
class AgentToolResolverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentConfigProperties agentConfig = new AgentConfigProperties();
    private final ToolCatalog catalog = new ToolCatalog(agentConfig);

    private AgentToolRouter newRouter(ToolExecutionService tes, Set<String> allowed,
                                     McpToolCatalog mcpCatalog,
                                     McpOrchestrationService orchestration,
                                     McpConfiguration configuration) {
        return new AgentToolRouter(tes, catalog, orchestration, configuration,
                mcpCatalog, mock(RepositoryApiClient.class), allowed);
    }

    private static ToolCallContext base() {
        return new ToolCallContext("owner", "repo", 7L, Path.of("/tmp/ws"),
                ImplementationPlan.ToolRequest.builder().id("id-1").tool("cat").args(List.of()).build());
    }

    private static McpToolCatalog githubCatalog() {
        return new McpToolCatalog(List.of(new McpToolDefinition(
                "github", "search_issues", "Search issues", "Search GitHub issues",
                Map.of("query", "string"), "mcp:github:search_issues")));
    }

    private static McpToolAccess access(McpToolCatalog catalog) {
        return new McpToolAccess(mock(McpOrchestrationService.class),
                mock(McpConfiguration.class), catalog);
    }

    @Test
    void forAgentLoop_coding_exposesOnlyReadOnlyBuiltins() {
        Set<String> allowed = Set.of("cat", "rg", "pr-diff", "write-file", "patch-file", "mkdir");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, McpToolCatalog.empty(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), McpToolAccess.none(), allowed, Set.of());

        assertThat(set.list()).extracting(ResolvedTool::name).contains("cat", "rg", "pr-diff");
        assertThat(set.list()).extracting(ResolvedTool::name)
                .doesNotContain("write-file", "patch-file", "mkdir");
    }

    @Test
    void forAgentLoop_excludesAgentControlAndValidationShapedTools() {
        Set<String> allowed = Set.of("cat", "branch-switcher", "pr-test-run", "preview-status", "mvn");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, McpToolCatalog.empty(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), McpToolAccess.none(), allowed, Set.of());

        assertThat(set.list()).extracting(ResolvedTool::name)
                .doesNotContain("branch-switcher", "pr-test-run", "preview-status", "mvn", "execute_code");
    }

    @Test
    void forAgentLoop_writer_addsRepositoryLookups_only() {
        Set<String> allowed = Set.of("cat", "get-issue", "search-issues");
        ResolvedToolSet writer = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, McpToolCatalog.empty(), null, null),
                catalog, ToolCatalog.Role.WRITER, base(), McpToolAccess.none(), allowed, Set.of());
        ResolvedToolSet coding = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, McpToolCatalog.empty(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), McpToolAccess.none(), allowed, Set.of());

        assertThat(writer.list()).extracting(ResolvedTool::name)
                .contains("get-issue", "search-issues", "cat");
        assertThat(coding.list()).extracting(ResolvedTool::name)
                .doesNotContain("get-issue", "search-issues");
    }

    @Test
    void forAgentLoop_mcpToolsCarryTheirProvenance() {
        Set<String> allowed = Set.of("cat");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, githubCatalog(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), access(githubCatalog()), allowed, Set.of());

        ResolvedTool tool = set.find("mcp:github:search_issues").orElseThrow();

        assertThat(tool.source()).isEqualTo(ToolSource.MCP);
        assertThat(tool.sourceId()).isEqualTo("github");
        assertThat(tool.nativeToolName()).isEqualTo("search_issues");
    }

    @Test
    void forAgentLoop_mcpToolIsRemovedByQualifiedName() {
        Set<String> allowed = Set.of("cat");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, githubCatalog(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), access(githubCatalog()), allowed,
                Set.of("mcp:github:search_issues"));

        assertThat(set.contains("mcp:github:search_issues")).isFalse();
        assertThat(set.contains("cat")).isTrue();
    }

    @Test
    void forAgentLoop_mcpToolIsRemovedByServerPrefix() {
        Set<String> allowed = Set.of("cat");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, githubCatalog(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), access(githubCatalog()), allowed,
                Set.of("github:"));

        assertThat(set.contains("mcp:github:search_issues")).isFalse();
    }

    @Test
    void forAgentLoop_mcpToolIsNotAdvertisedWhenItCannotBeExecuted() {
        Set<String> allowed = Set.of("cat");
        // Catalog present but no orchestration/configuration: advertising it would give Python
        // a tool that cannot run, so the set must not carry it.
        McpToolAccess unusable = new McpToolAccess(null, null, githubCatalog());
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, githubCatalog(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), unusable, allowed, Set.of());

        assertThat(set.contains("mcp:github:search_issues")).isFalse();
    }

    @Test
    void invoke_builtinTool_takesTheExistingRouterPath() {
        ToolExecutionService tes = mock(ToolExecutionService.class);
        when(tes.executeContextTool(any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "file-content", ""));
        Set<String> allowed = Set.of("cat");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(tes, allowed, McpToolCatalog.empty(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), McpToolAccess.none(), allowed, Set.of());

        JsonNode arguments = JSON.readTree("{\"args\":[\"README.md\"]}");
        ToolInvocationResult result = set.invoke("cat", arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("file-content");
        verify(tes).executeContextTool(any(), eqStr("cat"), eqArgs("README.md"));
    }

    @Test
    void invoke_mcpTool_takesTheExistingMcpPath() {
        McpOrchestrationService orchestration = mock(McpOrchestrationService.class);
        when(orchestration.executeTool(any(), any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "issues", ""));
        McpToolAccess access = new McpToolAccess(orchestration, mock(McpConfiguration.class),
                githubCatalog());
        Set<String> allowed = Set.of("cat");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, githubCatalog(), orchestration, null),
                catalog, ToolCatalog.Role.CODING, base(), access, allowed, Set.of());

        ToolInvocationResult result = set.invoke("mcp:github:search_issues",
                JSON.readTree("{\"query\":\"execute_code\"}"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("issues");
        verify(orchestration).executeTool(any(), any(), eqStr("mcp:github:search_issues"),
                eqArgs("{\"query\":\"execute_code\"}"));
    }

    @Test
    void invoke_toolOutsideTheSet_isRefused() {
        Set<String> allowed = Set.of("cat");
        ResolvedToolSet set = AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, McpToolCatalog.empty(), null, null),
                catalog, ToolCatalog.Role.CODING, base(), McpToolAccess.none(), allowed, Set.of());

        assertThatThrownBy(() -> set.invoke("write-file", null))
                .isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void forAgentLoop_prWorkflowRole_isNotResolvableThroughTheAgentLoopFactory() {
        Set<String> allowed = Set.of("cat");

        assertThatThrownBy(() -> AgentToolResolver.forAgentLoop(
                newRouter(mock(ToolExecutionService.class), allowed, McpToolCatalog.empty(), null, null),
                catalog, ToolCatalog.Role.PR_WORKFLOW, base(), McpToolAccess.none(), allowed, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Mockito argThat helpers kept inline to match AgentToolRouterWhitelistTest.
    private static String eqStr(String expected) {
        return org.mockito.ArgumentMatchers.argThat(expected::equals);
    }

    @SafeVarargs
    private static <T> List<T> eqArgs(T... expected) {
        List<T> exp = List.of(expected);
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null && actual.equals(exp));
    }
}
