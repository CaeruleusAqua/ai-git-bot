package org.remus.giteabot.agent.codeexecution;

/**
 * Receives one event per nested tool call, on every surface.
 *
 * <p>Always installed: on the surfaces that have an audit sink it also feeds the audit
 * record, and on the four PR-workflow runners it is the only thing that makes a nested call
 * visible at all.</p>
 */
@FunctionalInterface
public interface ToolCallObserver {

    ToolCallObserver NOOP = (name, source, sourceId, success, durationMs) -> { };

    void onNestedToolCall(String name, ToolSource source, String sourceId,
                          boolean success, long durationMs);
}
