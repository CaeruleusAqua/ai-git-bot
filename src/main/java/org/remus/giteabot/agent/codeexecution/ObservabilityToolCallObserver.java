package org.remus.giteabot.agent.codeexecution;

import lombok.extern.slf4j.Slf4j;

/**
 * The observer installed on every surface.
 *
 * <p>A successful nested call is routine, so it logs at debug; a failed one logs at warn, so
 * an operator running at default levels still sees it. Per-call metrics with tool and source
 * labels are deliberately not emitted yet — {@code AgentMetrics} has no counter that can carry
 * them, and reusing {@code recordToolCall(provider)} with a tool name as the provider would be
 * a lie.</p>
 */
@Slf4j
public final class ObservabilityToolCallObserver implements ToolCallObserver {

    @Override
    public void onNestedToolCall(String name, ToolSource source, String sourceId,
                                 boolean success, long durationMs) {
        if (!success) {
            log.warn("execute-code -> {} [{}{}] failed after {}ms",
                    name, source, sourceId == null ? "" : ", server=" + sourceId, durationMs);
        } else if (log.isDebugEnabled()) {
            log.debug("execute-code -> {} [{}{}] ok in {}ms",
                    name, source, sourceId == null ? "" : ", server=" + sourceId, durationMs);
        }
    }
}
