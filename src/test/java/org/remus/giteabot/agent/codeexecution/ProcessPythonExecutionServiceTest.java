package org.remus.giteabot.agent.codeexecution;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sandbox end to end, against a real interpreter: the argv, the bridge socket, the limits and the
 * teardown proved to work together rather than in isolation. Skipped where python3 is absent, which is
 * the only environment-dependent part of this suite.
 */
class ProcessPythonExecutionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static boolean pythonAvailable;

    @BeforeAll
    static void detectPython() {
        try {
            Process process = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start();
            pythonAvailable = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            pythonAvailable = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pythonAvailable = false;
        }
    }

    private static ProcessPythonExecutionService service(Consumer<AgentConfigProperties> tweak) {
        AgentConfigProperties config = new AgentConfigProperties();
        tweak.accept(config);
        return new ProcessPythonExecutionService(config);
    }

    private static List<ToolDescriptor> advertised(String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> new ToolDescriptor(name, "Read a file.",
                        JSON.createObjectNode().put("type", "object")))
                .toList();
    }

    private static ToolResult ok(String output) {
        return new ToolResult(true, 0, output, "");
    }

    private static CodeExecutionScope scope(List<ToolDescriptor> available,
                                            PythonToolExecutor executor) {
        return new CodeExecutionScope(available, executor);
    }

    private static PythonToolExecutor returns(ToolResult result) {
        return (tool, arguments) -> result;
    }

    @Test
    void stdoutBecomesTheResult() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { })
                .execute("print('hello from python')", scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.output()).isEqualTo("hello from python\n");
        assertThat(outcome.error()).isEmpty();
    }

    @Test
    void theArgvIsolatesTheInterpreterAndCarriesTheSandboxEnvironment() throws IOException {
        Path workspace = Files.createTempDirectory("execute-code-argv-");
        try {
            ProcessBuilder processBuilder = service(config -> { }).command(workspace);

            assertThat(processBuilder.command()).containsExactly("python3", "-I", "-u", "-B",
                    workspace.resolve("bootstrap.py").toString(),
                    workspace.resolve("program.py").toString());
            assertThat(processBuilder.directory()).isEqualTo(workspace.toFile());
            // The environment is scrubbed first, so this also proves the sandbox variables are added
            // after the scrub rather than being cleared by it.
            Map<String, String> environment = processBuilder.environment();
            assertThat(environment).containsKey("AI_GIT_BOT_BRIDGE");
            assertThat(environment.get("AI_GIT_BOT_LIMIT_AS_BYTES")).isEqualTo("268435456");
            assertThat(environment.get("AI_GIT_BOT_LIMIT_NPROC")).isEqualTo("64");
            assertThat(environment.get("TMPDIR")).isEqualTo(workspace.toString());
        } finally {
            Files.deleteIfExists(workspace);
        }
    }

    @Test
    void aProgramCanCallAToolThroughTheBridge() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                import ai_git_bot

                result = ai_git_bot.tools.call("cat", {"path": "README.md"})
                print("tool said: " + result["output"])
                """;
        AtomicInteger relayed = new AtomicInteger();

        PythonExecutionOutcome outcome = service(config -> { }).execute(program,
                scope(advertised("cat"), (tool, arguments) -> {
                    relayed.incrementAndGet();
                    return ok("FILE-CONTENT");
                }));

        assertThat(outcome.error()).isEmpty();
        assertThat(outcome.output()).isEqualTo("tool said: FILE-CONTENT\n");
        assertThat(relayed).hasValue(1);
    }

    @Test
    void aRefusedToolIsReadableFromTheProgramWithoutAnException() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                import ai_git_bot

                result = ai_git_bot.tools.call("write-file", {"path": "x"})
                print("success=%s error=%s" % (result["success"], result["error"]))
                """;

        PythonExecutionOutcome outcome = service(config -> { }).execute(program,
                scope(advertised("write-file"),
                        returns(new ToolResult(false, -1, "",
                                "Tool 'write-file' is not enabled for this bot."))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.output())
                .isEqualTo("success=False error=Tool 'write-file' is not enabled for this bot.\n");
    }

    @Test
    void theToolListIsReachableFromTheProgram() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                import ai_git_bot

                listed = ai_git_bot.tools.list()
                print("tools=%d first=%s" % (len(listed), listed[0]["name"]))
                """;

        PythonExecutionOutcome outcome = service(config -> { })
                .execute(program, scope(advertised("cat"), returns(ok(""))));

        assertThat(outcome.output()).isEqualTo("tools=1 first=cat\n");
    }

    @Test
    void theNetworkModulesAreRefusedInsideTheProgram() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { })
                .execute("import socket\n", scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.output()).contains("is not available inside execute-code");
    }

    @Test
    void anEndlessProgramIsStoppedByTheTimeout() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome =
                service(config -> config.getValidation().setToolTimeoutSeconds(2))
                .execute("print('before the loop')\nwhile True:\n    pass\n",
                        scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(124);
        assertThat(outcome.error()).contains("timed out after 2s");
        // Proves the output was streamed, not left in a pipe buffer to be lost on the kill.
        assertThat(outcome.output()).contains("before the loop");
    }

    @Test
    void anOversizedOutputIsTruncatedAndMarked() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(1024))
                        .execute("print('x' * 50000)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.output()).contains("[output truncated at 1024 chars]");
        assertThat(outcome.output().length()).isLessThanOrEqualTo(1024 + 40);
    }

    @Test
    void aProgramOverTheSizeLimitIsRefusedWithoutSpawningAnything() {
        PythonExecutionOutcome outcome =
                service(config -> config.getCodeExecution().setMaxCodeSize(DataSize.ofBytes(10)))
                        .execute("print('far too long for ten bytes')",
                                scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.error()).contains("over the limit of 10 bytes");
    }
}
