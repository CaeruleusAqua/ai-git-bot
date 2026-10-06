package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.systemsettings.McpConfiguration;

/**
 * The three MCP collaborators a resolution needs. They always travel together — the catalog
 * to advertise from and to resolve a descriptor's server/tool names, the configuration and
 * the orchestration service to execute through — and none of them is ever handed to Python.
 */
public record McpToolAccess(McpOrchestrationService orchestration,
                            McpConfiguration configuration,
                            McpToolCatalog catalog) {

    public McpToolAccess {
        catalog = catalog != null ? catalog : McpToolCatalog.empty();
    }

    /** No MCP for this bot: nothing to advertise, nothing to call. */
    public static McpToolAccess none() {
        return new McpToolAccess(null, null, McpToolCatalog.empty());
    }

    public boolean canExecute() {
        return orchestration != null && configuration != null && catalog.hasTools();
    }
}
