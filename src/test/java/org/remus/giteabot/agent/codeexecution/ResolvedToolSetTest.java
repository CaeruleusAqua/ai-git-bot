package org.remus.giteabot.agent.codeexecution;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resolved set is the authority for one execution: a name it does not carry is
 * unreachable, and every name it does carry can actually run.
 */
class ResolvedToolSetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ResolvedTool tool(String name) {
        return new ResolvedTool(name, "description", JSON.createObjectNode(),
                ToolSource.BUILTIN, null, name);
    }

    private static ToolInvoker ok(String output) {
        return arguments -> new ToolInvocationResult(true, 0, output, "");
    }

    @Test
    void of_keepsResolutionOrder() {
        ResolvedToolSet set = ResolvedToolSet.of(
                List.of(tool("cat"), tool("rg"), tool("tree")),
                Map.of("cat", ok("c"), "rg", ok("r"), "tree", ok("t")));

        assertThat(set.list()).extracting(ResolvedTool::name)
                .containsExactly("cat", "rg", "tree");
    }

    @Test
    void of_dropsToolsThatHaveNoInvoker() {
        ResolvedToolSet set = ResolvedToolSet.of(
                List.of(tool("cat"), tool("ghost")),
                Map.of("cat", ok("c")));

        assertThat(set.list()).extracting(ResolvedTool::name).containsExactly("cat");
        assertThat(set.contains("ghost")).isFalse();
    }

    @Test
    void invoke_unknownTool_throws() {
        ResolvedToolSet set = ResolvedToolSet.empty();

        assertThatThrownBy(() -> set.invoke("rm-rf", null))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("rm-rf");
    }

    @Test
    void invoke_acceptsSurroundingWhitespace() {
        ResolvedToolSet set = ResolvedToolSet.of(List.of(tool("cat")), Map.of("cat", ok("c")));

        assertThat(set.invoke("  cat  ", null).output()).isEqualTo("c");
    }

    @Test
    void invoke_toolThatThrows_becomesUnsuccessfulResult() {
        ResolvedToolSet set = ResolvedToolSet.of(List.of(tool("cat")),
                Map.<String, ToolInvoker>of("cat", arguments -> {
                    throw new IllegalStateException("boom");
                }));

        ToolInvocationResult result = set.invoke("cat", null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("boom");
    }

    @Test
    void without_removesToolAndItsInvoker() {
        ResolvedToolSet set = ResolvedToolSet.of(
                List.of(tool("cat"), tool("execute-code")),
                Map.of("cat", ok("c"), "execute-code", ok("e")));

        ResolvedToolSet trimmed = set.without("execute-code");

        assertThat(trimmed.list()).extracting(ResolvedTool::name).containsExactly("cat");
        assertThatThrownBy(() -> trimmed.invoke("execute-code", null))
                .isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void without_isANoOpForNamesThatAreNotPresent() {
        ResolvedToolSet set = ResolvedToolSet.of(List.of(tool("cat")), Map.of("cat", ok("c")));

        assertThat(set.without("does-not-exist")).isSameAs(set);
    }
}
