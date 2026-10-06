package org.remus.giteabot.agent.codeexecution;

import tools.jackson.databind.JsonNode;

/**
 * A tool as Python sees it: identity, description, schema and provenance — never
 * infrastructure. The mapping to the code that actually runs it is kept Java-side in
 * {@link ResolvedToolSet}, so the descriptor stays comparable and loggable.
 *
 * @param name           the name Python passes to {@code tools.call}
 * @param sourceId       MCP server alias; {@code null} for built-in tools
 * @param nativeToolName the name the owning family knows the tool by
 */
public record ResolvedTool(String name,
                           String description,
                           JsonNode inputSchema,
                           ToolSource source,
                           String sourceId,
                           String nativeToolName) {
}
