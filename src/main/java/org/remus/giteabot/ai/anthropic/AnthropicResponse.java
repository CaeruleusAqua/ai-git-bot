package org.remus.giteabot.ai.anthropic;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.ai.UsageCounterDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.Map;
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {
    private String id;
    private String type;
    private String role;
    private List<ContentBlock> content;
    private String model;
    @JsonProperty("stop_reason")
    private String stopReason;
    private Usage usage;
    /**
     * Polymorphic content block. {@code type} is one of {@code text} or
     * {@code tool_use} (Step 6). For tool_use blocks, {@link #id},
     * {@link #name} and {@link #input} are populated.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentBlock {
        private String type;
        private String text;
        // tool_use
        private String id;
        private String name;
        /** Map representation; the client converts it to a JsonNode. */
        private Map<String, Object> input;
    }
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("input_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer inputTokens;
        @JsonProperty("output_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer outputTokens;
        @JsonProperty("cache_creation_input_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer cacheCreationInputTokens = 0;
        @JsonProperty("cache_read_input_tokens")
        @JsonDeserialize(using = UsageCounterDeserializer.class)
        private Integer cacheReadInputTokens = 0;

        /** Anthropic excludes cache reads/writes from input_tokens; add each exactly once. */
        public Long totalInputTokens() {
            if (inputTokens == null || inputTokens < 0
                    || cacheCreationInputTokens == null || cacheCreationInputTokens < 0
                    || cacheReadInputTokens == null || cacheReadInputTokens < 0) {
                return null;
            }
            return (long) inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
        }
    }
}
