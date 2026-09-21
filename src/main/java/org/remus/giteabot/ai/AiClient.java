package org.remus.giteabot.ai;

import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Locale;

/**
 * Provider-agnostic interface for AI-powered code review and agent chat.
 *
 * <p>{@link #chatWithTools(List, String, List, String, String, Integer)} exposes
 * completion metadata for both native tools and text-only conversations.
 * Providers without native tools keep {@link #supportsNativeTools()} false but
 * can still override the typed API. Its default textual fallback cannot verify
 * completion and reports {@link StopReason#OTHER}.</p>
 */
public interface AiClient {

    /**
     * Sends a single review prompt to the AI provider and returns the response.
     * This is the primitive operation that the code-review service uses for each
     * diff chunk. The provider resolves its own model, token budget, and default
     * prompt internally.
     */
    String submitReviewPrompt(String systemPrompt, String modelOverride, String userMessage);

    /**
     * Sends a multi-turn conversation to the AI provider and returns the assistant's response.
     */
    String chat(List<AiMessage> conversationHistory, String newUserMessage,
                String systemPrompt, String modelOverride);

    /**
     * Sends a multi-turn conversation to the AI provider with a custom max tokens limit.
     *
     * @param maxTokensOverride Custom max tokens limit (if null, uses the default)
     */
    String chat(List<AiMessage> conversationHistory, String newUserMessage,
                String systemPrompt, String modelOverride, Integer maxTokensOverride);

    // ---------------------------------------------------------------------
    // Native function/tool calling (Step 6)
    // ---------------------------------------------------------------------

    /**
     * Capability flag: true when the implementation can advertise tools to
     * the underlying provider and parse {@code tool_use}/{@code tool_calls}
     * responses. Defaults to {@code false}; override in providers that
     * implement {@link #chatWithTools(List, String, List, String, String, Integer)}
     * natively.
     *
     * <p>Per-integration overrides (e.g. the {@code use_legacy_tool_calling}
     * column on {@code AiIntegration}) are applied by the
     * {@code AiClientFactory} when constructing the client.</p>
     */
    default boolean supportsNativeTools() {
        return false;
    }

    /**
     * Sends a typed chat turn with optional native tool descriptors. The default
     * implementation falls back to {@link #chat(List, String, String, String, Integer)}
     * with unknown completion status ({@link StopReason#OTHER}) and usage.
     * Providers should override it to retain their actual response metadata.
     *
     * @param conversationHistory the conversation up to (but not including) the
     *                            new user message
     * @param newUserMessage      the next user prompt (may be empty when the
     *                            previous turn already produced tool calls and
     *                            the caller is now feeding back tool results)
     * @param tools               the tools the model may invoke; an empty list
     *                            forces a text-only turn
     * @param systemPrompt        the system prompt
     * @param modelOverride       optional model override
     * @param maxTokensOverride   optional token budget
     */
    default ChatTurn chatWithTools(List<AiMessage> conversationHistory,
                                   String newUserMessage,
                                   List<ToolDescriptor> tools,
                                   String systemPrompt,
                                   String modelOverride,
                                   Integer maxTokensOverride) {
        String text = chat(conversationHistory, newUserMessage, systemPrompt,
                modelOverride, maxTokensOverride);
        return new ChatTurn(text, List.of(), StopReason.OTHER, 0L, 0L);
    }

    // ---------------------------------------------------------------------
    // Error classification
    // ---------------------------------------------------------------------

    /**
     * Heuristic check whether an HTTP client error indicates the prompt
     * exceeded the model's context window. The default implementation matches
     * common provider error patterns; concrete providers should override with
     * their own, more specific patterns.
     */
    default boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        String status = String.valueOf(e.getStatusCode().value());
        return normalized.contains("prompt is too long")
                || normalized.contains("maximum context length")
                || normalized.contains("request too large")
                || normalized.contains("input too long")
                || normalized.contains("too many tokens")
                || normalized.contains("context_length_exceeded")
                || normalized.contains("context length")
                || normalized.contains("token limit")
                || ("400".equals(status) && normalized.contains("too large"));
    }

    /**
     * Reports a failed provider interaction to the attached audit recorder
     * (no-op when no recorder is attached).
     */
    void reportError(Throwable error);

    /**
     * Returns the AiClients model-name
     * @return the model name
     */
    String getModel();

}
