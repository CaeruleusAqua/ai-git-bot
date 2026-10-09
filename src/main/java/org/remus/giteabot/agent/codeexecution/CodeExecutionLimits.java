package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.config.AgentConfigProperties;

import java.time.Duration;

/**
 * One immutable snapshot of what the sandbox needs, taken from the config.
 *
 * <p>The config class owns the defaults; this record only carries the values the engine needs,
 * already converted to the units the process APIs want. Two of them deliberately come from outside
 * {@code agent.code-execution.*}: the timeout and the result cap are the ones every other tool call
 * uses, so there is one owner per limit and a sandboxed run cannot outlive a normal one. Test call
 * sites use inline literals matching the shipped defaults.</p>
 *
 * <p>{@code sandboxSlots} is the pool of identities the interpreter is switched to before it starts,
 * one per execution, so the program reads neither the service user's files nor this JVM's start-time
 * environment. Blank means the program runs as the service user (layer 1 only). {@code sudoBinary} is
 * what performs the switch: only sudo can be restricted to named target users, and the rule the
 * installer generates from that same pool file allows the slots and nothing else, so the service can
 * become a slot and never root.</p>
 */
public record CodeExecutionLimits(Duration timeout,
                                  int maxToolCalls,
                                  int maxResultChars,
                                  int maxCodeBytes,
                                  long maxMemoryBytes,
                                  int cpuSeconds,
                                  long maxFileSizeBytes,
                                  int maxProcesses,
                                  String pythonBinary,
                                  String sandboxSlots,
                                  String sudoBinary) {

    public static CodeExecutionLimits from(AgentConfigProperties config) {
        AgentConfigProperties.CodeExecutionConfig code = config.getCodeExecution();
        return new CodeExecutionLimits(
                Duration.ofSeconds(config.getValidation().getToolTimeoutSeconds()),
                code.getMaxToolCalls(),
                config.getBudget().getMaxToolResultChars(),
                toIntBytes(code.getMaxCodeSize().toBytes()),
                code.getMaxMemoryMb() * 1024L * 1024L,
                code.getCpuSeconds(),
                code.getMaxFileSize().toBytes(),
                code.getMaxProcesses(),
                orEmpty(code.getPythonBinary()),
                orEmpty(code.getSandboxSlots()),
                orEmpty(code.getSudoBinary()));
    }

    private static int toIntBytes(long bytes) {
        return (int) Math.min(bytes, Integer.MAX_VALUE);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
