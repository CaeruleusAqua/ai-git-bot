package org.remus.giteabot.prworkflow.tools;

import org.remus.giteabot.agent.codeexecution.CodeExecutionScope;
import org.remus.giteabot.agent.codeexecution.PythonExecutionOutcome;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolDescriptor;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Adds {@code execute-code} to a PR-workflow agent's tool surface.
 *
 * <p>The four PR-workflow agents keep dispatching exactly as before; this decorator passes every
 * name it does not own straight through. When it does own the call it runs the submitted program in
 * the sandbox, giving it the agent's own tool surface — built from the descriptors the agent already
 * advertises, minus {@code execute-code} itself, which would nest sandboxes — so a program can call
 * neither more nor less than the model can.</p>
 *
 * <p>A nested call reaches the same executor with the same context the model's call would, which is
 * the whole point: the family's own argument validation, workspace sandboxing, database writes and
 * audit hooks apply unchanged. The Python-side arguments are named like the executor's, so they are
 * handed over as they arrive rather than flattened.</p>
 *
 * <p>One implementation for all four families, constructed once per agent around its own executor
 * and its own advertised list.</p>
 *
 * @param <C> the family's tool context type
 */
public final class CodeExecutionToolExecutor<C> implements WorkflowToolExecutor<C> {

    /** The tool name this decorator owns. */
    public static final String TOOL_NAME = "execute-code";

    /** How the executors report failure: text, not an exception. */
    private static final String ERROR_PREFIX = "ERROR";

    private final WorkflowToolExecutor<C> delegate;
    private final PythonExecutionService sandbox;
    private final List<ToolDescriptor> advertised;

    /**
     * @param delegate   the family's own executor, which serves every other tool name
     * @param sandbox    the Python sandbox
     * @param advertised the descriptors this agent shows its model; {@code execute-code} is removed
     *                   from the program's copy
     */
    public CodeExecutionToolExecutor(WorkflowToolExecutor<C> delegate, PythonExecutionService sandbox,
                                     List<ToolDescriptor> advertised) {
        this.delegate = delegate;
        this.sandbox = sandbox;
        this.advertised = advertised.stream()
                .filter(descriptor -> !TOOL_NAME.equals(descriptor.name()))
                .toList();
    }

    @Override
    public String execute(String toolName, Map<String, Object> args, C ctx) {
        if (!TOOL_NAME.equals(toolName)) {
            return delegate.execute(toolName, args, ctx);
        }
        Object code = args == null ? null : args.get("code");
        if (code == null || code.toString().isBlank()) {
            return "ERROR: execute-code needs the Python program as its first argument";
        }
        CodeExecutionScope scope = new CodeExecutionScope(advertised,
                (tool, arguments) -> call(tool, arguments, ctx));
        PythonExecutionOutcome outcome = sandbox.execute(code.toString(), scope);
        if (!outcome.success()) {
            String error = outcome.error() == null || outcome.error().isBlank()
                    ? outcome.output()
                    : outcome.error();
            return "ERROR: " + error;
        }
        return outcome.output();
    }

    /**
     * One call from inside a program. The result is built from the delegate's own text result, so a
     * program is handed exactly what the model would have been, errors included.
     */
    private ToolResult call(String tool, JsonNode arguments, C ctx) {
        if (TOOL_NAME.equals(tool)) {
            return new ToolResult(false, -1, "",
                    "Tool '" + tool + "' cannot be called from a program");
        }
        String result = delegate.execute(tool, toArgs(arguments), ctx);
        boolean ok = result != null && !result.startsWith(ERROR_PREFIX);
        return new ToolResult(ok, 0, result == null ? "" : result, "");
    }

    private static Map<String, Object> toArgs(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> named =
                (Map<String, Object>) AgentJackson.mapper().convertValue(arguments, Map.class);
        return named;
    }
}
