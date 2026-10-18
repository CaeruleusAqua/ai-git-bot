package org.remus.giteabot.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.google.GoogleAiClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProviderAggregateAvailabilityTest {

    @ParameterizedTest(name = "{0}: native tools = {1}")
    @CsvSource(textBlock = """
            anthropic, true,  false, true,  0, 3
            anthropic, false, false, true,  0, 3
            google,    true,  true,  false, 5, 0
            google,    false, true,  false, 5, 0
            """)
    void suppliedNullComponentInvalidatesOnlyItsDependentAggregate(
            String provider, boolean nativeTools, boolean inputReported, boolean outputReported,
            long input, long output) {
        RestClient.Builder http = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        AbstractAiClient client;
        String endpoint;
        String response;
        switch (provider) {
            case "anthropic" -> {
                client = new AnthropicAiClient(http.build(), "test-model", 32, true, false, false, null);
                endpoint = "/v1/messages";
                response = """
                        {"content":[{"type":"text","text":"Answer"}],"stop_reason":"end_turn",
                         "usage":{"input_tokens":0,"output_tokens":3,"cache_creation_input_tokens":null}}
                        """;
            }
            case "google" -> {
                client = new GoogleAiClient(http.build(), "test-model", 32, true);
                endpoint = "/v1beta/models/test-model:generateContent";
                response = """
                        {"candidates":[{"content":{"parts":[{"text":"Answer"}]},"finishReason":"STOP"}],
                         "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":0,"thoughtsTokenCount":null}}
                        """;
            }
            default -> throw new IllegalArgumentException(provider);
        }
        server.expect(requestTo("https://provider.example" + endpoint))
                .andExpect(nativeTools ? jsonPath("$.tools").isNotEmpty() : jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        CapturingRecorder recorder = new CapturingRecorder();
        client.setAuditRecorder(recorder);
        List<ToolDescriptor> tools = nativeTools
                ? List.of(new ToolDescriptor("lookup", "Read context", null)) : List.of();

        ChatTurn turn = client.chatWithTools(List.of(), "Question", tools, "System", null, null);

        server.verify();
        assertAll(
                () -> assertThat(turn.assistantText()).isEqualTo("Answer"),
                () -> assertThat(turn.inputTokensReported()).as("input availability").isEqualTo(inputReported),
                () -> assertThat(turn.outputTokensReported()).as("output availability").isEqualTo(outputReported),
                () -> assertThat(turn.inputTokens()).isEqualTo(input),
                () -> assertThat(turn.outputTokens()).isEqualTo(output),
                () -> assertThat(recorder.usage).containsExactly(new RecordedUsage(input, output)));
    }

    private record RecordedUsage(long inputTokens, long outputTokens) {}

    private static final class CapturingRecorder implements AiAuditRecorder {
        private final List<RecordedUsage> usage = new ArrayList<>();

        @Override
        public void recordUsage(long inputTokens, long outputTokens,
                                long cacheCreationInputTokens, long cacheReadInputTokens,
                                String rawRequest, String rawResponse) {
            usage.add(new RecordedUsage(inputTokens, outputTokens));
        }

        @Override
        public void recordError(Throwable error) {
            throw new AssertionError("Unexpected provider error", error);
        }
    }
}
