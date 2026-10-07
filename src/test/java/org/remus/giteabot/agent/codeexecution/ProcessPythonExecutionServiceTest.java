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

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
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
    void multiByteOutputWithinTheCapIsNotCalledTruncated() {
        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(50))
                        .execute("print(chr(252) * 40)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.output()).isEqualTo("ü".repeat(40) + "\n");
        assertThat(outcome.output()).doesNotContain("truncated");
    }

    @Test
    void truncationKeepsTheCapAndNeverSplitsASurrogatePair() {
        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(5))
                        .execute("print(chr(0x1F600) * 4)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.output()).contains("[output truncated at 5 chars]");
        assertThat(hasLoneSurrogate(outcome.output())).isFalse();
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

    /**
     * The tool description tells the model to call {@code tools.call(...)} — the program's own
     * namespace carries that name, so no import is needed. A program that writes it and gets a
     * NameError loses the round and reads as "execute-code is broken".
     */
    @Test
    void theToolSurfaceIsBoundWithoutAnImport() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                result = tools.call("cat", {"path": "README.md", "startLine": None, "endLine": None})
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

        // The C modules behind socket/ssl are separate import names, so the guard has to name them too.
        for (String module : List.of("socket", "_socket", "ssl", "_ssl")) {
            PythonExecutionOutcome outcome = service(config -> { })
                    .execute("import " + module + "\n", scope(List.of(), returns(ok(""))));

            assertThat(outcome.success()).as(module).isFalse();
            assertThat(outcome.output()).as(module).contains("is not available inside execute-code");
        }
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

    /**
     * The contract the model depends on: everything the program prints is the result, in order.
     * The bridge has its own channel, so a program may print whatever it likes.
     */
    @Test
    void everythingTheProgramPrintsIsTheResult() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { }).execute("""
                print("CHARS", 11)
                print("LINES", 1)
                """, scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.output()).isEqualTo("CHARS 11\nLINES 1\n");
    }

    /**
     * A program that fails is still a program that printed. Dropping the output would leave an
     * empty result, which reads as a program that chose to say nothing.
     */
    @Test
    void theLinesPrintedBeforeAFailureStillReachTheModel() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { }).execute("""
                print("got this far")
                raise SystemExit("boom")
                """, scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.output()).contains("got this far");
        assertThat(outcome.error()).contains("code 1");
        // What the model actually reads, not only what the sandbox returned.
        assertThat(new ToolResult(outcome.success(), outcome.exitCode(),
                outcome.output(), outcome.error()).formatForAi())
                .contains("got this far");
    }

    /**
     * The program's working directory is its own scratch space, not the checkout: a repository read
     * goes through the tool surface, where the bot's whitelist decides what exists — which is why the
     * tool description spells out {@code tools.call("cat", ...)}. A program could still open an
     * absolute path if it knew one (layer 1 confines no filesystem); the working directory simply
     * never hands it one.
     */
    @Test
    void theProgramsWorkingDirectoryHoldsNothingButTheSandbox() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { })
                .execute("import os\nprint(sorted(os.listdir('.')))",
                        scope(List.of(), returns(ok(""))));

        assertThat(outcome.output())
                .contains("ai_git_bot.py")
                .contains("bootstrap.py")
                .contains("program.py");
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

    /**
     * The capture is bounded in bytes, so multi-byte output can be cut before the character cap is
     * reached. Without a marker the caller reads a shortened result as a complete one.
     */
    @Test
    void multiByteOutputCutByTheByteBudgetIsMarkedTruncated() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        // 2000 two-byte characters are 4000 bytes: past the byte budget (2000 + 1024), while the
        // character count stays under the 2000-character cap, so only the byte test can see the cut.
        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(2000))
                        .execute("print(chr(0xFC) * 2000)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.output()).contains("[output truncated at 3024 bytes]");
    }
}
