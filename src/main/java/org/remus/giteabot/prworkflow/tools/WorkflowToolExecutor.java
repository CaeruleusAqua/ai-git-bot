package org.remus.giteabot.prworkflow.tools;

import java.util.Map;

/**
 * What a PR-workflow agent's tool dispatch looks like, whatever the family behind it.
 *
 * <p>Extracted so that one decorator can sit in front of all of them: the four executors
 * ({@code e2e}, {@code i18n}, {@code readmesync}, {@code unittest}) already have this exact method,
 * and the agents that own them already call it. Nothing else changes — the runners keep dispatching
 * as they do, and the SPI plan's runner merge stays independent of this.</p>
 *
 * <p>The contract is deliberately the executors' own: {@code args} are named (not positional), and a
 * failure is reported as text starting with {@code "ERROR: "} rather than by throwing. A decorator
 * that wanted to signal an outcome differently would break every caller's error handling.</p>
 *
 * @param <C> the family's tool context type
 */
public interface WorkflowToolExecutor<C> {

    /**
     * Executes the named tool against the given context.
     *
     * @return the tool's textual result; failures start with {@code "ERROR: "}
     */
    String execute(String toolName, Map<String, Object> args, C ctx);
}
