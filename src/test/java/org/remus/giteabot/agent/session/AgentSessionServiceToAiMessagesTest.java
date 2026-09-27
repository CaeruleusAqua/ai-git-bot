package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.session.ConversationMessage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: when a follow-up comment arrives after a previously aborted /
 * tool-heavy run, the in-DB conversation history contains {@code role:"tool"}
 * messages and (for native function-calling providers) assistant turns whose
 * only payload was {@code tool_calls}. Replaying those naively to OpenAI /
 * Anthropic fails with
 * <em>"messages with role 'tool' must be a response to a preceeding message
 * with 'tool_calls'"</em> when the tool rows disagree with the payload the
 * preceding assistant turn announced. Rows that cannot be paired — persisted
 * before the payload columns existed, orphaned, or answered twice — are stripped
 * when rebuilding the AI history, while a complete persisted pair is replayed.
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionServiceToAiMessagesTest {

    @Mock private AgentSessionRepository repository;

    @Test
    void toAiMessages_dropsToolRoleAndBlankAssistantMessages() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Implement feature X", 1);
        addMessageAt(session, "assistant", "", 2); // tool-call-only turn (tool_calls lost on persistence)
        addMessageAt(session, "tool", "[call_123] some result", 3); // orphaned tool result
        addMessageAt(session, "assistant", "Done with first round.", 4);
        addMessageAt(session, "user", "Please continue", 5);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "user");
        assertThat(messages).extracting(AiMessage::getContent)
                .containsExactly("Implement feature X", "Done with first round.", "Please continue");
    }

    @Test
    void toAiMessages_keepsNormalConversation() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Hello", 1);
        addMessageAt(session, "assistant", "Hi", 2);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
    }

    @Test
    void toAiMessages_replaysAPersistedToolExchange() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Implement feature X", 1);
        addPayloadMessageAt(session, "assistant", "", toolCallsJson("call_123", "cat"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_123] file body", null, "call_123", 3);
        addMessageAt(session, "assistant", "Done with first round.", 4);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(messages.get(1).getToolCalls()).extracting(ToolCall::id).containsExactly("call_123");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_123");
        assertThat(messages.get(2).getToolResult()).isEqualTo("[call_123] file body");
    }

    @Test
    void toAiMessages_dropsToolRowsThatAnswerNoAnnouncedCall() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Implement feature X", 1);
        addPayloadMessageAt(session, "assistant", "reading", toolCallsJson("call_1", "cat"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_1] first", null, "call_1", 3);
        addPayloadMessageAt(session, "tool", "[call_1] answered twice", null, "call_1", 4);
        addPayloadMessageAt(session, "tool", "[call_2] never announced", null, "call_2", 5);
        addPayloadMessageAt(session, "tool", "[call_1] id without a payload turn", null, null, 6);
        addMessageAt(session, "user", "Please continue", 7);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "tool", "user");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_1");
        assertThat(messages.get(2).getToolResult()).isEqualTo("[call_1] first");
    }

    @Test
    void toAiMessages_keepsAnAssistantTurnWhoseOnlyPayloadIsItsToolCalls() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addPayloadMessageAt(session, "assistant", "", toolCallsJson("call_9", "rg"), null, 1);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getToolCalls()).extracting(ToolCall::id).containsExactly("call_9");
    }

    private static void addMessageAt(AgentSession session, String role, String content, long offsetSeconds) {
        ConversationMessage msg = new ConversationMessage(role, content);
        msg.setCreatedAt(Instant.ofEpochSecond(1_700_000_000L + offsetSeconds));
        session.getMessages().add(msg);
    }

    private static void addPayloadMessageAt(AgentSession session, String role, String content,
                                           String toolCalls, String toolCallId, long offsetSeconds) {
        ConversationMessage msg = new ConversationMessage(role, content);
        msg.setCreatedAt(Instant.ofEpochSecond(1_700_000_000L + offsetSeconds));
        msg.setToolCalls(toolCalls);
        msg.setToolCallId(toolCallId);
        session.getMessages().add(msg);
    }

    /** Serialises a one-call payload the way {@code AgentSessionService} stores it. */
    private static String toolCallsJson(String callId, String toolName) {
        return AgentJackson.mapper().writeValueAsString(
                List.of(new ToolCall(callId, toolName, AgentJackson.mapper().readTree("{}"), null)));
    }
}



