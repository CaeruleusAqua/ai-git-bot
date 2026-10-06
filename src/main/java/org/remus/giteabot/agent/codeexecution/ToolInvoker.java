package org.remus.giteabot.agent.codeexecution;

import tools.jackson.databind.JsonNode;

/**
 * Executes an already-authorised tool on behalf of a Python program.
 *
 * <p>One implementation per family, each bound to that family's own executor and typed
 * context — nothing is reimplemented here, and nothing is resolved by name. Implementations
 * never throw: a failed tool call is a normal {@link ToolInvocationResult}.</p>
 */
@FunctionalInterface
public interface ToolInvoker {

    ToolInvocationResult invoke(JsonNode arguments);
}
