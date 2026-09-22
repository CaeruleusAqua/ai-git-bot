package org.remus.giteabot.ai.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Chat-Completions request payload (OpenAI flavour). Tool-related fields
 * (Step 6) are only emitted when the caller populated {@link #tools}.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAiRequest {

    private String model;

    @JsonProperty("max_completion_tokens")
    private Integer maxCompletionTokens;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private ProviderPreferences provider;

    private List<Plugin> plugins;

    /** Per-request override of an OpenRouter account-default plugin. */
    public record Plugin(String id, boolean enabled) {}

    /** OpenRouter routing preserves requested parameters and avoids hidden provider fallback attempts. */
    public record ProviderPreferences(@JsonProperty("data_collection") String dataCollection, boolean zdr) {
        /** Requested parameters must be supported by the chosen endpoint. */
        @JsonProperty("require_parameters")
        public boolean requireParameters() { return true; }

        /** Hidden provider fallback attempts are always disabled. */
        @JsonProperty("allow_fallbacks")
        public boolean allowFallbacks() { return false; }
    }

    /**
     * Sent only when the provider requires it. Some gateway front-ends inject
     * a default {@code reasoning_effort} value; for models such as
     * {@code gpt-5.6-sol} the Chat Completions API then rejects function tools
     * unless {@code reasoning_effort} is explicitly {@code "none"}. Set via the
     * integration's model flavor — see {@link OpenAiFlavor} — which is resolved
     * in {@code OpenAiProviderMetadata#createClient} and handed to
     * {@code OpenAiClient} so this field is populated only when the
     * {@code no_reasoning} flavor is selected.
     */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    private List<Message> messages;

    /** Tool definitions advertised to the model (function calling, Step 6). */
    private List<Tool> tools;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        /** Plain assistant/user/system text. */
        private String content;

        /** Opaque reasoning blocks echoed in their original order for tool continuations. */
        @JsonProperty("reasoning_details")
        @ToString.Exclude
        private List<JsonNode> reasoningDetails;

        /** Assistant turns may carry tool_calls instead of (or in addition to) content. */
        @JsonProperty("tool_calls")
        private List<ToolCallPayload> toolCalls;

        /** Required on tool-role messages: the call id this result belongs to. */
        @JsonProperty("tool_call_id")
        private String toolCallId;

        /** Optional: explicit name of the tool whose result this message carries. */
        private String name;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        /** Always {@code "function"} for current OpenAI tools. */
        @Builder.Default
        private String type = "function";
        private Function function;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {
        private String name;
        private String description;
        /**
         * JSON-Schema for the function's parameters. Typed as {@code Object} so
         * the field works with whichever {@code JsonNode} flavour
         * (Jackson 2 / 3) the rest of the project uses; Jackson serializes the
         * underlying tree node correctly either way.
         */
        private Object parameters;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallPayload {
        private String id;
        @Builder.Default
        private String type = "function";
        private FunctionCall function;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        private String name;
        /** Stringified JSON, per OpenAI's spec. */
        private String arguments;
    }
}
