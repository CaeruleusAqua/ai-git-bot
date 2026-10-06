package org.remus.giteabot.agent.codeexecution;

import lombok.Getter;

/**
 * Thrown when a program names a tool that is not part of this execution's resolved set.
 *
 * <p>A tool that exists globally but is not enabled for this bot is simply absent from the
 * set, so it is unreachable rather than rejected at execution time. This exception is the
 * only way to reach that state, and it becomes a {@code tool_error} envelope that Python
 * sees as a {@code ToolError}.</p>
 */
@Getter
public class ToolNotAllowedException extends RuntimeException {

    private final String toolName;

    public ToolNotAllowedException(String toolName) {
        super("Tool '" + toolName + "' is not available in this execution");
        this.toolName = toolName;
    }

}
