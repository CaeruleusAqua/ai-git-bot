package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.agent.validation.ToolResult;

/**
 * Normalised envelope: the one shape Python sees, whichever family produced it.
 *
 * <p>{@code exitCode} and {@code error} are nullable and preserved, so collapsing
 * {@link ToolResult} (built-in and MCP) together with the PR-workflow
 * {@code "OK: …"}/{@code "ERROR: …"} string onto one shape loses nothing — a program can
 * still branch on a tool's exit code and read its error text.</p>
 */
public record ToolInvocationResult(boolean success, Integer exitCode, String output, String error) {

    /** Adapts the universal executor result. Never carries {@code null} text. */
    public static ToolInvocationResult from(ToolResult result) {
        return new ToolInvocationResult(result.success(), result.exitCode(),
                nullToEmpty(result.output()), nullToEmpty(result.error()));
    }

    public static ToolInvocationResult failure(String message) {
        return new ToolInvocationResult(false, null, "", nullToEmpty(message));
    }

    /** What Python receives as the {@code result} payload of a bridge response. */
    public boolean hasError() {
        return error != null && !error.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
