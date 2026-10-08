package org.remus.giteabot.prworkflow;

import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolArguments;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tool surface one PR-workflow run offers its agent: the workflow's own tools, plus the
 * read-only catalogue tools — {@code execute-code} among them — that every workflow run makes
 * available, plus whatever the bot's own tool configuration selects.
 *
 * <p>Before this class a workflow's agent advertised only its own {@code ALLOWED_TOOLS} and handed
 * every returned name to its own executor, so no catalogue tool reached those runs at all — not the
 * repository reads, not {@code execute-code}. The tool carries
 * {@link ToolCatalog.Role#PR_WORKFLOW}, but nothing on that surface offered or dispatched it, so the
 * role entry was dead. This surface closes the gap: {@link #advertised()} returns the workflow's own
 * descriptors together with the read-only ones, and {@link #executeRouted} dispatches a catalogue
 * name the workflow does not own to an {@link AgentToolRouter} in {@link AgentToolRouter.Mode#WRITER}
 * — the same read-only handler the writer and agent-review runs use, so the two-way split in the
 * runner is the only new dispatch logic.</p>
 *
 * <p>{@code router} is {@code null} on a path with no router wired (unit tests that build an agent
 * without one, and {@code AgentToolRouter}'s own convention): the run then advertises only the
 * workflow's own tools and refuses a catalogue name with an explanation instead of failing
 * obscurely.</p>
 */
public final class WorkflowToolSurface {

    /**
     * The read-only catalogue tools every workflow run offers. The WRITER-role context reads —
     * excluding {@code branch-switcher}, which moves the checkout the workflow is about to commit
     * to. Kept in one place so a workflow's {@code ALLOWED_TOOLS} and the advertised surface cannot
     * drift apart.
     */
    public static final Set<String> READ_ONLY_CATALOGUE_TOOLS = Set.of(
            "rg", "find", "cat", "tree", "git-log", "git-blame",
            "ctags-signatures", "ctags-deps", "pr-diff", "get-issue", "search-issues");

    /** The one catalogue tool with a side effect — the program sandbox — offered on every run. */
    public static final String PROGRAM_TOOL = "execute-code";

    private final Set<String> workflowTools;
    private final ToolCatalog catalog;
    private final McpToolCatalog mcpCatalog;
    /** Everything the run may call: the workflow's own tools plus the catalogue tools it offers. */
    private final Set<String> callable;
    private final AgentToolRouter router;
    private final String owner;
    private final String repo;
    private final Long number;
    private final Path workspace;

    public WorkflowToolSurface(Set<String> workflowTools,
                               ToolCatalog catalog,
                               McpToolCatalog mcpCatalog,
                               Set<String> callable,
                               AgentToolRouter router,
                               String owner,
                               String repo,
                               Long number,
                               Path workspace) {
        this.workflowTools = Set.copyOf(workflowTools);
        this.catalog = catalog;
        this.mcpCatalog = mcpCatalog == null ? McpToolCatalog.empty() : mcpCatalog;
        this.callable = Set.copyOf(callable);
        this.router = router;
        this.owner = owner;
        this.repo = repo;
        this.number = number;
        this.workspace = workspace;
    }

    /** A workflow's own tools together with the read-only catalogue tools every run offers. */
    public static Set<String> withReadOnlyCatalogueTools(Set<String> workflowTools) {
        Set<String> out = new LinkedHashSet<>(workflowTools);
        out.addAll(READ_ONLY_CATALOGUE_TOOLS);
        out.add(PROGRAM_TOOL);
        return Set.copyOf(out);
    }

    /**
     * The descriptors to advertise: the workflow's own tools (PR_WORKFLOW role) plus the read-only
     * catalogue and MCP tools the run offers (WRITER role). A name reachable under both roles —
     * {@code execute-code} is declared for PR_WORKFLOW and WRITER alike — is advertised once.
     */
    public List<ToolDescriptor> advertised() {
        Map<String, ToolDescriptor> byName = new LinkedHashMap<>();
        for (ToolDescriptor d : catalog.nativeDescriptors(ToolCatalog.Role.PR_WORKFLOW, null, callable)) {
            byName.put(d.name(), d);
        }
        for (ToolDescriptor d : catalog.nativeDescriptors(ToolCatalog.Role.WRITER, mcpCatalog, callable)) {
            byName.putIfAbsent(d.name(), d);
        }
        return List.copyOf(byName.values());
    }

    /** Everything the run may call — the whitelist and the prompt's tool-strategy use this. */
    public Set<String> callable() {
        return callable;
    }

    public McpToolCatalog mcpCatalog() {
        return mcpCatalog;
    }

    /** {@code true} when the workflow's own executor handles {@code tool} (and only then). */
    public boolean handles(String tool) {
        return tool != null && workflowTools.contains(tool.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Dispatches a catalogue call the workflow does not own, flattening the model's JSON arguments
     * the way the native path does (schema order, holes for skipped optionals). Returns the
     * formatted result — a whitelist refusal included, in the executor's own words.
     */
    public String executeRouted(String tool, JsonNode arguments) {
        return executeRouted(tool, ToolArguments.toPositional(tool, arguments,
                catalog.schemaOf(tool).orElse(null)));
    }

    /** Dispatches a catalogue call whose positional vector is already known (legacy envelope). */
    public String executeRouted(String tool, List<String> positional) {
        if (router == null) {
            return "ERROR: tool '" + tool + "' is not available in this run (no tool router wired)";
        }
        ToolCallContext context = new ToolCallContext(owner, repo, number, workspace,
                ImplementationPlan.ToolRequest.builder()
                        .id("pr-workflow-call")
                        .tool(tool)
                        .args(positional == null ? List.of() : positional)
                        .build());
        return router.execute(AgentToolRouter.Mode.WRITER, context).formatForAi();
    }
}
