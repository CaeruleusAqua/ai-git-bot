package org.remus.giteabot.agent.codeexecution;

/**
 * Runs one Python program in the {@code execute-code} sandbox.
 *
 * <p>The implementation owns the process, the temp directory, the bridge socket and the
 * teardown; the caller hands over source text and a resolved tool set and gets back the
 * program's final output. Nothing about the sandbox's shape leaks past this interface, so a
 * later hardening (container-per-execution, for instance) is a new implementation rather than
 * a change to the tool.</p>
 */
public interface PythonExecutionService {

    PythonExecutionOutcome execute(String code, CodeExecutionScope scope);
}
