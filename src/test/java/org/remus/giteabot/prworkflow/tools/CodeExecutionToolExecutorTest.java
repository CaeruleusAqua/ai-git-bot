package org.remus.giteabot.prworkflow.tools;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.codeexecution.CodeExecutionScope;
import org.remus.giteabot.agent.codeexecution.PythonExecutionOutcome;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PR-workflow half of {@code execute-code}: the decorator's contract.
 *
 * <p>What matters here is that the decorator owns exactly one name, that a program's call lands in
 * the family's own executor with the family's own named arguments and context, and that a program is
 * shown the agent's tool surface minus the one tool that would nest sandboxes.</p>
 */
class CodeExecutionToolExecutorTest {

    private static final Set<String> ALLOWED = Set.of("execute-code", "pr-test-write");

    /** Records what the family's executor was asked to do. */
    private static final class Family implements WorkflowToolExecutor<String> {

        private final List<String> calls = new ArrayList<>();
        private String answer = "OK: did the thing";

        @Override
        public String execute(String toolName, Map<String, Object> args, String ctx) {
            calls.add(toolName + " " + args + " ctx=" + ctx);
            return answer;
        }
    }

    /** Runs the program the decorator was handed: one nested call, straight back. */
    private static final class Program implements PythonExecutionService {

        private final String tool;
        private final String arguments;
        private ToolResult nested;
        private List<ToolDescriptor> available;

        Program(String tool, String arguments) {
            this.tool = tool;
            this.arguments = arguments;
        }

        @Override
        public PythonExecutionOutcome execute(String code, CodeExecutionScope scope) {
            this.available = scope.available();
            this.nested = scope.executor().call(tool,
                    AgentJackson.mapper().readTree(arguments));
            String text = nested.success() ? nested.output() : "ERROR: " + nested.error();
            return new PythonExecutionOutcome(nested.success(), nested.exitCode(), text, "");
        }
    }

    private static List<ToolDescriptor> advertised() {
        return new ToolCatalog(new AgentConfigProperties())
                .nativeDescriptors(ToolCatalog.Role.PR_WORKFLOW, null, ALLOWED);
    }

    @Test
    void everyOtherNameGoesStraightThrough() {
        Family family = new Family();

        String result = new CodeExecutionToolExecutor<>(family, null, advertised())
                .execute("pr-test-write", Map.of("path", "tests/a.spec.ts"), "ctx-1");

        assertThat(result).isEqualTo("OK: did the thing");
        assertThat(family.calls).containsExactly("pr-test-write {path=tests/a.spec.ts} ctx=ctx-1");
    }

    @Test
    void aProgramsCallLandsInTheFamilyExecutor() {
        Family family = new Family();
        Program program = new Program("pr-test-write", "{\"path\": \"tests/a.spec.ts\"}");

        String result = new CodeExecutionToolExecutor<>(family, program, advertised())
                .execute("execute-code", Map.of("code", "print(1)"), "ctx-2");

        // The program's own result comes back as the tool's output.
        assertThat(result).isEqualTo("OK: did the thing");
        // The family saw its own named argument, its own context, and nothing else.
        assertThat(family.calls).containsExactly("pr-test-write {path=tests/a.spec.ts} ctx=ctx-2");
    }

    @Test
    void aProgramIsShownTheSurfaceMinusItself() {
        Program program = new Program("pr-test-write", "{\"path\": \"tests/a.spec.ts\"}");

        new CodeExecutionToolExecutor<>(new Family(), program, advertised())
                .execute("execute-code", Map.of("code", "print(1)"), "ctx");

        assertThat(program.available).extracting("name").contains("pr-test-write")
                .doesNotContain(CodeExecutionToolExecutor.TOOL_NAME);
    }

    @Test
    void aFamilyErrorReachesTheProgramAsFailure() {
        Family family = new Family();
        family.answer = "ERROR: path 'x' is outside the allowed test directory";
        Program program = new Program("pr-test-write", "{\"path\": \"x\"}");

        new CodeExecutionToolExecutor<>(family, program, advertised())
                .execute("execute-code", Map.of("code", "print(1)"), "ctx");

        assertThat(program.nested.success()).isFalse();
        assertThat(program.nested.output()).contains("outside the allowed test directory");
    }

    @Test
    void aProgramCannotNestSandboxes() {
        Family family = new Family();
        Program program = new Program("execute-code", "{\"code\": \"print(2)\"}");

        new CodeExecutionToolExecutor<>(family, program, advertised())
                .execute("execute-code", Map.of("code", "print(1)"), "ctx");

        assertThat(program.nested.success()).isFalse();
        assertThat(program.nested.error()).contains("cannot be called from a program");
        assertThat(family.calls).isEmpty();
    }

    @Test
    void aMissingProgramIsReported() {
        String result = new CodeExecutionToolExecutor<>(new Family(), new Program("x", "{}"),
                advertised())
                .execute("execute-code", Map.of(), "ctx");

        assertThat(result).startsWith("ERROR:").contains("needs the Python program");
    }

    @Test
    void aFailingSandboxBecomesAnErrorResult() {
        PythonExecutionService failing = (code, scope) ->
                new PythonExecutionOutcome(false, 1, "Traceback…", "SyntaxError: invalid syntax");

        String result = new CodeExecutionToolExecutor<>(new Family(), failing, advertised())
                .execute("execute-code", Map.of("code", "print("), "ctx");

        assertThat(result).isEqualTo("ERROR: SyntaxError: invalid syntax");
    }
}
