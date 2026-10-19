package org.remus.giteabot.ai.llamacpp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.ai.UsageCounterDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Response model for llama.cpp's OpenAI-compatible /v1/completions endpoint.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlamaCppCompletionResponse {

    private String model;
    private List<Choice> choices;
    private Usage usage;
    private ApiError error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private String text;

        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer completionTokens;

        @JsonProperty("prompt_tokens_details")
        private PromptTokensDetails promptTokensDetails;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.LongCounter.class)
        private Long cachedTokens;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiError {
        private String message;
        private String type;
        private JsonNode code;
    }
}
