package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.tools.ToolKind;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the tool set one {@code execute_code} invocation may use, per agent surface.
 *
 * <p>Advertisement is the authority: the set is derived from the very descriptors the surface
 * already advertises ({@link ToolCatalog#nativeDescriptors}), then restricted to the read-only
 * subset. So a tool the model cannot call directly is also a tool Python cannot call, and the
 * two lists cannot drift apart.</p>
 *
 * <p>V1 exposes read-only tools only: built-in {@code CONTEXT} plus {@code REPOSITORY}
 * lookups, and whatever MCP tools the bot selected minus the operator's deny list. File
 * mutation, validation commands and the PR-workflow writers are out of scope until an
 * operator opts in.</p>
 */
public final class AgentToolResolver {

    /**
     * Never handed to Python.
     *
     * <ul>
     *   <li>{@code execute_code} — recursion.</li>
     *   <li>{@code branch-switcher} — classified {@code CONTEXT} and read-only-looking, but it
     *       mutates git state, and the strategy's bookkeeping depends on it happening through
     *       {@code AgentRunContext.setBaseBranch}. A program switching branches mid-run
     *       desynchronises {@code baseBranch} from the checkout, and the commit and diff steps
     *       then operate on the wrong ref.</li>
     *   <li>{@code pr-test-run} and {@code preview-status} — they shell out to test frameworks and
     *       probe a deployment, which is outside the read-only policy.</li>
     * </ul>
     */
    static final Set<String> EXCLUDED = Set.of(
            "execute_code", "branch-switcher", "pr-test-run", "preview-status");

    private AgentToolResolver() {
    }

    /**
     * Resolves the set for an {@code AgentLoop} surface. The routing mode is derived from the
     * role rather than passed separately, so a mismatched pair cannot be constructed.
     */
    public static ResolvedToolSet forAgentLoop(AgentToolRouter router,
                                               ToolCatalog catalog,
                                               ToolCatalog.Role role,
                                               ToolCallContext base,
                                               McpToolAccess mcp,
                                               Set<String> allowedBuiltinTools,
                                               Set<String> deniedMcpTools) {
        AgentToolRouter.Mode mode = switch (role) {
            case CODING -> AgentToolRouter.Mode.CODING;
            case WRITER -> AgentToolRouter.Mode.WRITER;
            case PR_WORKFLOW -> throw new IllegalArgumentException(
                    "PR-workflow surfaces resolve their tools through the PR-workflow decorator, "
                            + "not through the AgentLoop factory");
        };
        McpToolAccess access = mcp != null ? mcp : McpToolAccess.none();
        List<ResolvedTool> tools = resolve(catalog, role, access, allowedBuiltinTools, deniedMcpTools);

        Map<String, ToolInvoker> invokers = new LinkedHashMap<>();
        invokers.putAll(BuiltinToolInvokers.invokers(router, mode, base, tools));
        invokers.putAll(McpToolInvokers.invokers(access, tools));

        return ResolvedToolSet.of(tools, invokers).without(EXCLUDED.toArray(String[]::new));
    }

    private static List<ResolvedTool> resolve(ToolCatalog catalog,
                                              ToolCatalog.Role role,
                                              McpToolAccess mcp,
                                              Set<String> allowedBuiltinTools,
                                              Set<String> deniedMcpTools) {
        McpToolCatalog mcpCatalog = mcp.catalog();
        List<ResolvedTool> out = new ArrayList<>();
        for (ToolDescriptor descriptor : catalog.nativeDescriptors(role, mcpCatalog, allowedBuiltinTools)) {
            Optional<McpToolDefinition> mcpTool = mcpCatalog.find(descriptor.name());
            if (mcpTool.isPresent()) {
                McpToolDefinition definition = mcpTool.get();
                if (!isDenied(definition, deniedMcpTools)) {
                    out.add(new ResolvedTool(descriptor.name(), descriptor.description(),
                            descriptor.jsonSchema(), ToolSource.MCP,
                            definition.serverName(), definition.name()));
                }
            } else if (isReadOnly(catalog.kindOf(descriptor.name()))) {
                out.add(new ResolvedTool(descriptor.name(), descriptor.description(),
                        descriptor.jsonSchema(), ToolSource.BUILTIN, null, descriptor.name()));
            }
        }
        return out;
    }

    /** The read-only subset: repository exploration and repository lookups. */
    private static boolean isReadOnly(ToolKind kind) {
        return kind == ToolKind.CONTEXT || kind == ToolKind.REPOSITORY;
    }

    /**
     * A deny rule matches a whole MCP tool (its qualified name) or a whole server, written
     * either bare or with the separator — {@code github:} reads more naturally in config than
     * a bare server name sitting among tool names.
     */
    private static boolean isDenied(McpToolDefinition definition, Set<String> denied) {
        if (denied == null || denied.isEmpty()) {
            return false;
        }
        String server = definition.serverName();
        for (String candidate : denied) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String rule = candidate.strip();
            if (rule.equals(definition.qualifiedName())
                    || rule.equals(server)
                    || rule.equals(server + ":")) {
                return true;
            }
        }
        return false;
    }
}
