package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The surface a PR-workflow run offers. Before it existed, a workflow agent advertised only its
 * own {@code ALLOWED_TOOLS} and dispatched every name to its own executor, so no catalogue tool
 * reached those runs — {@code execute-code} included, despite its {@code Role.PR_WORKFLOW}.
 */
class WorkflowToolSurfaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> OWN = Set.of("doc-write", "doc-delete");

    private final ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());

    private WorkflowToolSurface surface(AgentToolRouter router) {
        return new WorkflowToolSurface(OWN, catalog, McpToolCatalog.empty(),
                WorkflowToolSurface.withReadOnlyCatalogueTools(OWN), router,
                "acme", "repo", 42L, Path.of("/tmp/ws"));
    }

    @Test
    void advertisesTheWorkflowsOwnToolsAndTheReadOnlyCatalogueTools() {
        List<String> names = surface(null).advertised().stream().map(ToolDescriptor::name).toList();

        assertThat(names).contains("doc-write", "doc-delete", "execute-code", "cat", "rg", "find",
                "ctags-signatures", "pr-diff", "get-issue", "search-issues");
        // execute-code is declared for PR_WORKFLOW and WRITER alike — it must be offered once.
        assertThat(names).doesNotHaveDuplicates();
        // branch-switcher moves the checkout the run is about to commit to; it is not a read.
        assertThat(names).doesNotContain("branch-switcher");
    }

    @Test
    void withReadOnlyCatalogueToolsAlwaysAddsTheProgramTool() {
        Set<String> callable = WorkflowToolSurface.withReadOnlyCatalogueTools(OWN);

        assertThat(callable).contains("doc-write", "execute-code", "cat", "rg");
        assertThat(callable).containsAll(WorkflowToolSurface.READ_ONLY_CATALOGUE_TOOLS);
    }

    @Test
    void aWorkflowToolIsHandledLocallyAndACatalogueToolIsNot() {
        WorkflowToolSurface surface = surface(null);

        assertThat(surface.handles("doc-write")).isTrue();
        assertThat(surface.handles("DOC-WRITE")).isTrue();
        assertThat(surface.handles("rg")).isFalse();
        assertThat(surface.handles("execute-code")).isFalse();
        assertThat(surface.handles(null)).isFalse();
    }

    @Test
    void withoutARouterACatalogueCallIsRefusedWithAnExplanation() {
        String result = surface(null).executeRouted("cat", JSON.createObjectNode().put("path", "README.md"));

        assertThat(result).contains("not available in this run");
    }

    @Test
    void aCatalogueCallReachesTheRouterSoTheWhitelistDecides() {
        // An empty bot whitelist: the router, not the surface, owns the refusal.
        AgentToolRouter router = new AgentToolRouter(
                mock(ToolExecutionService.class), catalog, mock(McpOrchestrationService.class),
                mock(McpConfiguration.class), McpToolCatalog.empty(), mock(RepositoryApiClient.class),
                Set.of(), null);

        String result = surface(router).executeRouted("cat", JSON.createObjectNode().put("path", "README.md"));

        assertThat(result).contains("not enabled for this bot");
    }

    @Test
    void executeCodeIsDispatchedToTheRouterAndReportsAnUnavailableSandbox() {
        // Whitelisted, but this deployment wires no sandbox: the call must come back in
        // execute-code's own words rather than being swallowed by the workflow's executor.
        AgentToolRouter router = new AgentToolRouter(
                mock(ToolExecutionService.class), catalog, mock(McpOrchestrationService.class),
                mock(McpConfiguration.class), McpToolCatalog.empty(), mock(RepositoryApiClient.class),
                WorkflowToolSurface.withReadOnlyCatalogueTools(OWN), null);

        String result = surface(router).executeRouted("execute-code",
                JSON.createObjectNode().put("code", "print(1)"));

        assertThat(result).contains("execute-code is not available in this deployment");
    }
}
