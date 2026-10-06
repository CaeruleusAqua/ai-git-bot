package org.remus.giteabot.agent.codeexecution;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP half of the resolved set. Python names a tool; Java decides which server, which client
 * and which credentials — the program never sees a URL, a header, a token or a transport.
 */
final class McpToolInvokers {

    private McpToolInvokers() {
    }

    static Map<String, ToolInvoker> invokers(McpToolAccess access, List<ResolvedTool> tools) {
        Map<String, ToolInvoker> out = new LinkedHashMap<>();
        if (!access.canExecute()) {
            return out;
        }
        for (ResolvedTool tool : tools) {
            if (tool.source() == ToolSource.MCP) {
                out.put(tool.name(), call(access, tool));
            }
        }
        return out;
    }

    private static ToolInvoker call(McpToolAccess access, ResolvedTool tool) {
        return arguments -> ToolInvocationResult.from(access.orchestration().executeTool(
                access.configuration(), access.catalog(), tool.name(), List.of(jsonArguments(arguments))));
    }

    /**
     * MCP tools accept provider-defined schemas, so the whole argument object travels as one
     * JSON element — the convention the native path already uses, which
     * {@code McpOrchestrationService.parseArguments} turns back into a map.
     */
    private static String jsonArguments(JsonNode arguments) {
        return arguments == null || arguments.isNull() ? "{}" : arguments.toString();
    }
}
