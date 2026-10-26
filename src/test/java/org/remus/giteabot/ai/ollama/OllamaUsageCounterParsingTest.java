package org.remus.giteabot.ai.ollama;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OllamaUsageCounterParsingTest {

    private HttpServer server;
    @Mock
    private AiAuditRecorder recorder;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void objectCounterDoesNotDiscardTheAnswerOrReportInputUsage() {
        OllamaClient client = respondingWith("{}");

        ChatTurn turn = chatWithTools(client);

        assertEquals("Answer retained", turn.assistantText());
        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertEquals(0L, turn.inputTokens());
        assertFalse(turn.inputTokensReported());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "1.5", "7.0", "1e2", "\"7\"", "\"\"", "true", "false",
            "[]", "[1,{\"nested\":[2]}]", "{\"nested\":[{},1]}", "-1", "-2147483648",
            "2147483648", "-2147483649", "9223372036854775808",
            "9999999999999999999999999999999999999999999999999999999999999999999999999999999999"})
    void unavailableCountersPreserveTheAnswerWithoutReportingInvalidInput(String counterJson) {
        OllamaClient client = respondingWith(counterJson);
        client.setAuditRecorder(recorder);

        ChatTurn turn = chatWithTools(client);

        assertEquals("Answer retained", turn.assistantText());
        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertEquals(0L, turn.inputTokens());
        assertFalse(turn.inputTokensReported());
        verify(recorder, never()).recordUsage(longThat(input -> input != 0L),
                anyLong(), anyLong(), anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7, Integer.MAX_VALUE})
    void validCountersStillReachTheTurnAndRawAudit(int counter) {
        OllamaClient client = respondingWith(Integer.toString(counter));
        client.setAuditRecorder(recorder);
        AiUsageProperties properties = new AiUsageProperties();
        properties.setRawPayloadsEnabled(true);
        client.setUsageProperties(properties);

        ChatTurn turn = chatWithTools(client);

        assertEquals("Answer retained", turn.assistantText());
        assertEquals(counter, turn.inputTokens());
        assertEquals(3L, turn.outputTokens());
        ArgumentCaptor<String> rawResponse = ArgumentCaptor.forClass(String.class);
        verify(recorder).recordUsage(eq((long) counter), eq(3L), eq(0L), eq(0L),
                anyString(), rawResponse.capture());
        assertTrue(rawResponse.getValue().contains("\"prompt_eval_count\":" + counter));
    }

    private OllamaClient respondingWith(String counterJson) {
        String counterField = counterJson == null ? "" : "\"prompt_eval_count\":" + counterJson + ",";
        byte[] body = ("{" + counterField + """
                "eval_count":3,"message":{"role":"assistant","content":"Answer retained"},"done":true,"done_reason":"stop"}
                """).getBytes(StandardCharsets.UTF_8);
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return new OllamaClient(RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort()).build(),
                "test-model", 128, true);
    }

    private ChatTurn chatWithTools(OllamaClient client) {
        return client.chatWithTools(List.of(), "Question",
                List.of(new ToolDescriptor("lookup", "Read context", null)),
                "System", null, null);
    }
}
