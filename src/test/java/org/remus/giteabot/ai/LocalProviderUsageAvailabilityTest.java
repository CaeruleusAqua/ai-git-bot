package org.remus.giteabot.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalProviderUsageAvailabilityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputCounterCases")
    void retainsAnswerAndReportedOutputZeroWhenInputCounterIsInvalid(
            String provider, String endpoint, MediaType mediaType, String response) {
        var http = RestClient.builder().baseUrl("https://provider.example");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://provider.example" + endpoint))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(response, mediaType));
        AbstractAiClient client = switch (provider) {
            case "ollama" -> new OllamaClient(http.build(), "model", 32, true);
            case "llamacpp" -> new LlamaCppClient(http.build(), "model", 32);
            default -> throw new IllegalArgumentException(provider);
        };
        List<List<Long>> legacyUsageRows = new ArrayList<>();
        client.setAuditRecorder(new AiAuditRecorder() {
            @Override
            public void recordUsage(long inputTokens, long outputTokens,
                                    long cacheCreationInputTokens, long cacheReadInputTokens,
                                    String rawRequest, String rawResponse) {
                legacyUsageRows.add(List.of(inputTokens, outputTokens));
            }

            @Override
            public void recordError(Throwable error) {
            }
        });

        ChatTurn turn = client.chatWithTools(List.of(), "Question", List.of(), "System", null, null);

        assertThat(turn.assistantText()).isEqualTo("Answer");
        assertThat(turn.inputTokensReported()).isFalse();
        assertThat(turn.inputTokens()).isZero();
        assertThat(turn.outputTokensReported()).isTrue();
        assertThat(turn.outputTokens()).isZero();
        // The legacy audit callback uses zero placeholders; only the turn carries availability.
        assertThat(legacyUsageRows).containsExactly(List.of(0L, 0L));
        server.verify();
    }

    static Stream<Arguments> invalidInputCounterCases() {
        return Stream.of(
                Arguments.of("ollama", "/api/chat", MediaType.APPLICATION_NDJSON, """
                        {"message":{"role":"assistant","content":"Answer"},"done":true,"prompt_eval_count":{},"eval_count":0}
                        """),
                Arguments.of("llamacpp", "/v1/completions", MediaType.TEXT_EVENT_STREAM, """
                        data: {"choices":[{"text":"Answer","finish_reason":"stop"}],"usage":{"prompt_tokens":{},"completion_tokens":0}}

                        data: [DONE]

                        """));
    }
}
