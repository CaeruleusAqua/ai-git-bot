package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.agent.validation.ToolResult;
import tools.jackson.databind.JsonNode;

/**
 * Runs one tool for one agent surface, on behalf of a sandboxed program.
 *
 * <p>There is deliberately nothing to configure here — no allow list, no resolution, no per-family
 * adapter. The surface supplies its own dispatch, so a program's call travels the same path the
 * model's own call takes, and the bot's whitelist plus the MCP selection are the authorisation for
 * both. On the {@code AgentLoop} surfaces that is a single delegation to
 * {@code AgentToolRouter.execute}.</p>
 *
 * <p>Implementations do not throw: a refused or failed call comes back as an unsuccessful
 * {@link ToolResult}, which is what the model itself would see.</p>
 */
@FunctionalInterface
public interface PythonToolExecutor {

    ToolResult call(String tool, JsonNode arguments);
}
