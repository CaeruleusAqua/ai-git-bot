package org.remus.giteabot.agent.codeexecution;

/**
 * Which family owns a tool — where it comes from, not how it is called.
 *
 * <p>{@code PR_WORKFLOW} is not in the feature request's two-value enum, but the family
 * exists in this codebase and is in scope, so it is represented here rather than folded
 * into {@code BUILTIN}.</p>
 */
public enum ToolSource {

    /** A built-in tool executed through {@code AgentToolRouter} / {@code ToolExecutionService}. */
    BUILTIN,

    /** A tool exposed by a configured MCP server. */
    MCP,

    /** A tool owned by a PR workflow (E2E, unit-test, readme-sync, i18n). */
    PR_WORKFLOW
}
