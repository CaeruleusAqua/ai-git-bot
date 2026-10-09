package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.ai.ToolDescriptor;

import java.util.List;

/**
 * What one {@code execute-code} invocation needs: the tools the model itself is offered, and how
 * this surface runs one of them.
 *
 * <p>{@code available} is the surface's own advertised set — the very descriptors the model sees —
 * so a program can call neither more nor less than the agent can, and the two lists cannot drift.
 * {@code executor} is that surface's dispatch, so a nested call is authorised by the same rules as a
 * direct one.</p>
 */
public record CodeExecutionScope(List<ToolDescriptor> available, PythonToolExecutor executor) {
}
