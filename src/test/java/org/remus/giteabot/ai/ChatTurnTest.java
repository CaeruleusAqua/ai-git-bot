package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.remus.giteabot.agent.shared.AgentJackson;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTurnTest {

    @ParameterizedTest
    @CsvSource({
            "50, 3, false, true, 0, 3, 3",
            "50, 3, true, false, 50, 0, 50",
            "50, 3, false, false, 0, 0, 0"
    })
    void canonicalConstructorDiscardsCountersMarkedUnavailable(
            long input, long output, boolean inputReported, boolean outputReported,
            long expectedInput, long expectedOutput, long expectedTotal) {
        var turn = new ChatTurn("Answer", List.of(), StopReason.END_TURN,
                input, output, inputReported, outputReported);

        assertEquals(expectedInput, turn.inputTokens());
        assertEquals(expectedOutput, turn.outputTokens());
        assertEquals(expectedTotal, turn.totalTokens());
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 3, 0, 3, false, true",
            "2, -3, 2, 0, true, false",
            "-1, -3, 0, 0, false, false"
    })
    void canonicalConstructorClearsReportedFlagsForNegativeCounters(
            long input, long output, long expectedInput, long expectedOutput,
            boolean expectedInputReported, boolean expectedOutputReported) {
        var turn = new ChatTurn("Answer", List.of(), StopReason.END_TURN,
                input, output, true, true, null);

        assertEquals(expectedInput, turn.inputTokens());
        assertEquals(expectedOutput, turn.outputTokens());
        assertEquals(expectedInputReported, turn.inputTokensReported());
        assertEquals(expectedOutputReported, turn.outputTokensReported());
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, false, false, 0, 0",
            "0, 7, false, true, 0, 7",
            "8, 0, true, false, 8, 0",
            "-1, 3, false, true, 0, 3"
    })
    void legacyConstructorsKeepPositiveCountOnlyPresence(
            long input, long output, boolean inputReported, boolean outputReported,
            long expectedInput, long expectedOutput) {
        var details = List.of(AgentJackson.mapper().readTree("{\"data\":\"private-reasoning\"}"));
        var turns = List.of(
                new ChatTurn("Answer", List.of(), StopReason.END_TURN, input, output),
                new ChatTurn("Answer", List.of(), StopReason.END_TURN, input, output, details));

        for (var turn : turns) {
            assertEquals(inputReported, turn.inputTokensReported());
            assertEquals(outputReported, turn.outputTokensReported());
            assertEquals(expectedInput, turn.inputTokens());
            assertEquals(expectedOutput, turn.outputTokens());
        }
    }

    @Test
    void jsonSerializationIncludesIndependentUsagePresenceFlags() {
        var turn = ChatTurn.withReportedUsage("Answer", List.of(), StopReason.END_TURN, 0L, null);
        var json = AgentJackson.mapper().readTree(AgentJackson.mapper().writeValueAsString(turn));

        assertNotNull(json.get("inputTokensReported"));
        assertNotNull(json.get("outputTokensReported"));
        assertTrue(json.get("inputTokensReported").asBoolean());
        assertFalse(json.get("outputTokensReported").asBoolean());
    }

    @Test
    void reportedUsageFactoryRetainsOpaqueReasoningWithoutExposingIt() {
        var details = new ArrayList<>(List.of(AgentJackson.mapper().readTree("{\"data\":\"private-reasoning\"}")));
        var turn = ChatTurn.withReportedUsage("Answer", List.of(), StopReason.END_TURN, 0L, null, details);

        assertTrue(turn.inputTokensReported());
        assertFalse(turn.outputTokensReported());
        assertEquals(details, turn.reasoningDetails());
        details.clear();
        assertEquals(1, turn.reasoningDetails().size());
        var message = turn.toAssistantMessage();
        assertEquals(turn.reasoningDetails(), message.getReasoningDetails());
        for (Object value : List.of(turn, message)) {
            assertFalse(value.toString().contains("private-reasoning"));
            assertFalse(AgentJackson.mapper().writeValueAsString(value).contains("private-reasoning"));
        }
    }

    @Test
    void opaqueReasoningIsExcludedFromDiagnosticsAndGenericSerialization() {
        var details = List.of(AgentJackson.mapper().readTree("{\"data\":\"private-reasoning\"}"));
        var turn = new ChatTurn("Answer", List.of(), StopReason.END_TURN, 100, 32, details);
        var message = turn.toAssistantMessage();

        assertEquals(details, turn.reasoningDetails());
        for (Object value : List.of(turn, message)) {
            assertFalse(value.toString().contains("private-reasoning"));
            assertFalse(AgentJackson.mapper().writeValueAsString(value).contains("private-reasoning"));
        }
    }

    @Test
    void textFactory_producesEndTurnAndNoToolCalls() {
        ChatTurn turn = ChatTurn.text("hello");
        assertEquals("hello", turn.assistantText());
        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertFalse(turn.hasToolCalls());
        assertNotNull(turn.toolCalls());
        assertTrue(turn.toolCalls().isEmpty());
    }

    @Test
    void canonicalConstructor_normalisesNullFields() {
        ChatTurn turn = new ChatTurn(null, null, null, 0L, 0L);
        assertEquals("", turn.assistantText());
        assertNotNull(turn.toolCalls());
        assertTrue(turn.toolCalls().isEmpty());
        assertEquals(StopReason.OTHER, turn.stopReason());
        assertEquals(0L, turn.inputTokens());
        assertEquals(0L, turn.outputTokens());
    }

    @Test
    void hasToolCalls_trueWhenToolCallPresent() {
        ChatTurn turn = new ChatTurn("",
                List.of(new ToolCall("call_1", "do_thing", null)),
                StopReason.TOOL_USE, 100L, 50L);
        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("do_thing", turn.toolCalls().getFirst().name());
        assertEquals(turn.toolCalls(), turn.toAssistantMessage().getToolCalls());
        assertEquals(100L, turn.inputTokens());
        assertEquals(50L, turn.outputTokens());
        assertEquals(150L, turn.totalTokens());
    }
}
