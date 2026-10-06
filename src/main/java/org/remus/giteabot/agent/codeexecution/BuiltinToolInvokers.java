package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolArguments;
import org.remus.giteabot.agent.tools.ToolCallContext;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in half of the resolved set: binds each advertised built-in tool to the existing
 * {@link AgentToolRouter}, so a nested call takes the identical path — and the identical
 * whitelist check — as a direct one. Nothing about tool execution is reimplemented.
 */
final class BuiltinToolInvokers {

    private BuiltinToolInvokers() {
    }

    static Map<String, ToolInvoker> invokers(AgentToolRouter router,
                                             AgentToolRouter.Mode mode,
                                             ToolCallContext base,
                                             List<ResolvedTool> tools) {
        Map<String, ToolInvoker> out = new LinkedHashMap<>();
        for (ResolvedTool tool : tools) {
            if (tool.source() == ToolSource.BUILTIN) {
                out.put(tool.name(), positional(router, mode, base, tool.name()));
            }
        }
        return out;
    }

    private static ToolInvoker positional(AgentToolRouter router, AgentToolRouter.Mode mode,
                                          ToolCallContext base, String name) {
        return arguments -> ToolInvocationResult.from(router.execute(mode, withArgs(base, name, arguments)));
    }

    /**
     * The router speaks positional args, so the program's named arguments are flattened by
     * {@link ToolArguments} — the same mapping a direct native tool call goes through.
     */
    private static ToolCallContext withArgs(ToolCallContext base, String name, JsonNode arguments) {
        return new ToolCallContext(base.owner(), base.repo(), base.issueNumber(), base.workspaceDir(),
                ImplementationPlan.ToolRequest.builder()
                        .id(name)
                        .tool(name)
                        .args(ToolArguments.toPositional(name, arguments))
                        .build(),
                base.diffSummary());
    }
}
