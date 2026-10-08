package org.remus.giteabot.agent.codeexecution;

/**
 * What one program left behind, in the shape the tool result needs.
 *
 * <p>{@code output} is the program's own stdout, already bounded by
 * {@code max-output-size} and marked when truncation happened. {@code error} is empty on
 * success and carries {@code exit code N} or the timeout notice otherwise — the tool result
 * exposes both, so the model can tell a crashed program from a quiet one.</p>
 */
public record PythonExecutionOutcome(boolean success, int exitCode, String output, String error) {

    public static PythonExecutionOutcome success(String output) {
        return new PythonExecutionOutcome(true, 0, output, "");
    }

    public static PythonExecutionOutcome failed(int exitCode, String output, String error) {
        return new PythonExecutionOutcome(false, exitCode, output, error);
    }
}
