package org.remus.giteabot.agent.session;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.session.ConversationMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing agent coding sessions.
 */
@Slf4j
@Service
public class AgentSessionService {

    /**
     * Threshold for total content size (in characters) that triggers compaction
     * of persisted agent session history. Set higher than the classic
     * {@link org.remus.giteabot.session.SessionService} threshold (50k) because
     * agentic workflows produce longer conversations.
     */
    private static final int COMPACT_THRESHOLD_CHARS = 80_000;

    /**
     * Maximum number of messages to retain in persisted context after compaction.
     * Set higher than classic (4) because agentic follow-up runs need more
     * continuity from the previous conversation.
     */
    private static final int MAX_MESSAGES_AFTER_COMPACT = 8;

    private final AgentSessionRepository repository;

    public AgentSessionService(AgentSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AgentSession createSession(String owner, String repo, Long issueNumber, String issueTitle) {
        return createSession(owner, repo, issueNumber, issueTitle, AgentSession.AgentSessionType.CODING, null);
    }

    @Transactional
    public AgentSession createSession(String owner, String repo, Long issueNumber, String issueTitle,
                                      AgentSession.AgentSessionType sessionType, String issueAuthorUsername) {
        log.info("Creating new {} agent session for issue #{} in {}/{}",
                sessionType, issueNumber, owner, repo);
        AgentSession session = new AgentSession(owner, repo, issueNumber, issueTitle);
        session.setSessionType(sessionType);
        session.setIssueAuthorUsername(issueAuthorUsername);
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public Optional<AgentSession> getSessionByIssue(String owner, String repo, Long issueNumber) {
        return repository.findByRepoOwnerAndRepoNameAndIssueNumber(owner, repo, issueNumber);
    }

    @Transactional
    public Optional<AgentSession> claimSessionForUpdate(String owner, String repo, Long issueNumber,
                                                        AgentSession.AgentSessionType sessionType) {
        Optional<AgentSession> sessionOpt = repository.findByRepoOwnerAndRepoNameAndIssueNumberForUpdate(
                owner, repo, issueNumber);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }
        AgentSession session = sessionOpt.get();
        if (session.getSessionType() != sessionType
                || session.getStatus() == AgentSession.AgentSessionStatus.UPDATING
                || session.getStatus() == AgentSession.AgentSessionStatus.ISSUE_CREATED
                || session.getStatus() == AgentSession.AgentSessionStatus.FAILED) {
            return Optional.empty();
        }
        session.setStatus(AgentSession.AgentSessionStatus.UPDATING);
        AgentSession savedSession = repository.save(session);
        savedSession.getMessages().size();
        return Optional.of(savedSession);
    }

    @Transactional(readOnly = true)
    public Optional<AgentSession> getSessionByPr(String owner, String repo, Long prNumber) {
        return repository.findByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
    }

    /**
     * Persists a batch of messages and the latest cumulative token counts for a
     * single agent-loop round in one transaction. This replaces the per-message
     * {@link #addMessage} calls (and the separate token-usage write) that the
     * loop previously issued, collapsing N micro-transactions per round into one.
     *
     * <p>The messages are appended to a freshly-fetched managed entity, so a
     * (possibly detached) caller object whose {@code messages} collection may
     * reference rows deleted by a prior compaction is never traversed by
     * {@code merge()}. Callers must rebind to the returned managed entity.</p>
     *
     * <p>Native tool-call payloads are persisted with their message, so a later
     * run can replay an assistant turn together with the tool rows that answer
     * it.</p>
     *
     * @param sessionId         id of the session to update
     * @param messages          the round's pending messages, in order
     * @param totalInputTokens  cumulative input tokens to persist
     * @param totalOutputTokens cumulative output tokens to persist
     * @return the managed entity with the appended messages and updated token counts
     */
    @Transactional
    public AgentSession flushMessages(Long sessionId, List<PendingMessage> messages,
                                      long totalInputTokens, long totalOutputTokens) {
        AgentSession managed = repository.getReferenceById(sessionId);
        // Stamp call-ordered, strictly-increasing timestamps (1µs apart, which
        // survives microsecond-resolution timestamp columns). The messages are an
        // unordered Set replayed by createdAt, so relying on the @PrePersist
        // default would scramble a batch into hash-iteration order. Subsequent
        // rounds flush seconds later, so cross-round order follows wall-clock.
        Instant base = Instant.now();
        for (int i = 0; i < messages.size(); i++) {
            PendingMessage msg = messages.get(i);
            managed.addMessage(msg.role(), msg.content(), base.plus(i, ChronoUnit.MICROS),
                    toolCallsJson(msg.payload()), toolCallId(msg.payload()));
        }
        managed.setTotalInputTokens(totalInputTokens);
        managed.setTotalOutputTokens(totalOutputTokens);
        return managed; // dirty checking flushes inserts + token counts on commit
    }

    /**
     * Serialises an assistant turn's tool calls for storage. Returns {@code null}
     * when the message carries none; a serialisation failure degrades to
     * {@code null} as well — the exchange is then dropped on replay, which is
     * the same outcome as a pre-V53 row and never a broken request.
     */
    private static String toolCallsJson(PendingMessage.ToolPayload payload) {
        if (payload == null || payload.toolCalls() == null || payload.toolCalls().isEmpty()) {
            return null;
        }
        try {
            return AgentJackson.mapper().writeValueAsString(payload.toolCalls());
        } catch (Exception e) {
            log.warn("Could not serialise tool_calls payload: {}", e.getMessage());
            return null;
        }
    }

    private static String toolCallId(PendingMessage.ToolPayload payload) {
        return payload == null ? null : payload.toolCallId();
    }

    /**
     * Step 7.1 — persist the most recently parsed implementation plan on the
     * session row so that PR-body and follow-up comment generation can read
     * the latest plan in O(1) instead of re-parsing the entire conversation
     * history.
     *
     * @param session the session to update (identified by its id)
     * @param summary short human-readable summary (typically {@code plan.summary()}); may be {@code null}
     * @param rawJson raw JSON representation of the plan; may be {@code null}
     * @return the managed entity with the updated plan
     */
    @Transactional
    public AgentSession recordPlan(AgentSession session, String summary, String rawJson) {
        // Mutate only a managed proxy and return it. Persisting via the managed
        // entity avoids merge() on a (possibly detached) caller object whose
        // messages collection may reference rows deleted by a prior compaction.
        // Callers must rebind to the returned entity to observe the new values.
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setLastPlanSummary(summary);
        managed.setLastPlanJson(rawJson);
        managed.setLastPlanAt(Instant.now());
        return managed;
    }

    @Transactional
    public AgentSession setBranchName(AgentSession session, String branchName) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setBranchName(branchName);
        return managed;
    }

    @Transactional
    public AgentSession setPrNumber(AgentSession session, Long prNumber) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setPrNumber(prNumber);
        managed.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);
        return managed;
    }

    @Transactional
    public AgentSession setGeneratedIssueNumber(AgentSession session, Long generatedIssueNumber) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setGeneratedIssueNumber(generatedIssueNumber);
        managed.setStatus(AgentSession.AgentSessionStatus.ISSUE_CREATED);
        return managed;
    }

    /**
     * Truncates the content of all persisted tool-role messages for a session,
     * shortening each one with head+tail truncation. Called when the context
     * window token budget is exceeded — this keeps tool results compact in
     * the persisted history rather than deleting them entirely.
     *
     * @param sessionId id of the session whose tool messages should be truncated
     * @param maxResultChars max characters to keep per tool result after truncation
     * @return the managed entity
     */
    @Transactional
    public AgentSession truncateToolMessages(Long sessionId, int maxResultChars) {
        AgentSession managed = repository.findById(sessionId).orElseThrow();
        int truncated = 0;
        for (ConversationMessage msg : managed.getMessages()) {
            if ("tool".equalsIgnoreCase(msg.getRole())
                    && msg.getContent() != null
                    && msg.getContent().length() > maxResultChars) {
                msg.setContent(truncateToolResult(msg.getContent(), maxResultChars));
                truncated++;
            }
        }
        if (truncated > 0) {
            log.debug("Truncated {} tool message(s) to max {} chars in session {}",
                    truncated, maxResultChars, sessionId);
        }
        return managed;
    }

    /**
     * Head+tail truncation for a tool result string. Keeps the first half of
     * {@code maxChars} from the head, the second half from the tail, with a
     * marker showing how many characters were removed.
     */
    public static String truncateToolResult(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        int headSize = maxChars / 2;
        int markerOverhead = 60; // "… [N chars truncated] …"
        int tailSize = maxChars - headSize - markerOverhead;
        if (tailSize <= 0) {
            return text.substring(0, maxChars) + "\n… [truncated]";
        }
        int truncatedChars = text.length() - headSize - tailSize;
        return text.substring(0, headSize)
                + "\n… [" + truncatedChars + " chars truncated] …\n"
                + text.substring(text.length() - tailSize);
    }

    @Transactional
    public AgentSession setStatus(AgentSession session, AgentSession.AgentSessionStatus status) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setStatus(status);
        return managed;
    }

    /**
     * Compacts the persisted agent session history when total content size
     * exceeds {@value #COMPACT_THRESHOLD_CHARS} characters. Keeps the
     * {@value #MAX_MESSAGES_AFTER_COMPACT} most recent messages and replaces
     * the removed portion with a summary placeholder.
     *
     * <p>This is the agentic equivalent of
     * {@link org.remus.giteabot.session.SessionService#compactContextWindow}.
     * It should be called between agent runs (e.g., after a follow-up comment
     * triggers a new coding round) to prevent the DB-persisted history from
     * growing without bound across sessions.</p>
     *
     * <p>The retained window never starts inside a tool exchange: the same rows
     * back {@link #toAiMessages}' replay, so cutting between an assistant turn and
     * the tool rows that answer it would leave them unpaired. The size counted
     * against the threshold includes the persisted tool-call payload, which is
     * replayed as well.</p>
     *
     * @param sessionId id of the session to compact
     * @return the managed (compacted) entity; callers must rebind to it because
     *         their detached object's {@code messages} collection still
     *         references the rows this method deleted
     */
    @Transactional
    public AgentSession compactContextWindow(Long sessionId) {
        // Operate on a freshly-fetched managed entity so the caller's detached
        // object — whose messages collection may reference rows a prior compaction
        // deleted — is never traversed (a merge() of it would resolve each element
        // by id and fail with ObjectNotFoundException on the deleted rows).
        AgentSession managed = repository.findById(sessionId).orElseThrow();

        List<ConversationMessage> sorted = new ArrayList<>(managed.getMessages());
        sorted.sort(Comparator.comparing(ConversationMessage::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        if (sorted.size() <= MAX_MESSAGES_AFTER_COMPACT) {
            log.debug("Agent session {} has {} messages, no compaction needed",
                    managed.getId(), sorted.size());
            return managed;
        }

        int totalChars = sorted.stream().mapToInt(AgentSessionService::messageChars).sum();

        if (totalChars < COMPACT_THRESHOLD_CHARS) {
            log.debug("Agent session {} has {} chars, below threshold {}, no compaction needed",
                    managed.getId(), totalChars, COMPACT_THRESHOLD_CHARS);
            return managed;
        }

        log.info("Compacting agent session {} context window: {} messages, {} chars -> keeping last {}",
                managed.getId(), sorted.size(), totalChars, MAX_MESSAGES_AFTER_COMPACT);

        // Identify messages to remove (all but the most recent N). A window that
        // would start on a tool row is widened back onto the assistant turn that
        // announced the call, so the replay never sees an unanswered pair.
        int removeCount = sorted.size() - MAX_MESSAGES_AFTER_COMPACT;
        while (removeCount > 0 && "tool".equalsIgnoreCase(sorted.get(removeCount).getRole())) {
            removeCount--;
        }
        List<ConversationMessage> toRemove = sorted.subList(0, removeCount);

        // Build a summary of what was removed
        String summary = buildAgentContextSummary(toRemove);

        // Remove old messages from the managed Set (orphanRemoval = true will DELETE from DB)
        toRemove.forEach(managed.getMessages()::remove);

        // Add a summary message as the first message in the surviving history
        if (!summary.isBlank()) {
            managed.addMessage("user", summary);
        }

        int newTotalChars = managed.getMessages().stream()
                .mapToInt(AgentSessionService::messageChars).sum();

        log.info("Agent session {} compacted: {} messages, {} chars remaining",
                managed.getId(), managed.getMessages().size(), newTotalChars);

        return managed; // dirty checking + orphanRemoval handle the flush
    }

    /**
     * Persisted size of a message: its content plus the native tool-call payload,
     * which is replayed to the provider and therefore counts towards the
     * compaction threshold.
     */
    private static int messageChars(ConversationMessage message) {
        int contentChars = message.getContent() == null ? 0 : message.getContent().length();
        int payloadChars = message.getToolCalls() == null ? 0 : message.getToolCalls().length();
        return contentChars + payloadChars;
    }

    /**
     * Builds a brief summary of removed conversation context for the agentic
     * workflow. Tailored to coding sessions (references files, PRs, tool calls).
     */
    private String buildAgentContextSummary(List<ConversationMessage> removedMessages) {
        if (removedMessages.isEmpty()) {
            return "";
        }

        long userMessages = removedMessages.stream()
                .filter(m -> "user".equals(m.getRole())).count();
        long assistantMessages = removedMessages.stream()
                .filter(m -> "assistant".equals(m.getRole())).count();

        return String.format(
                "[Previous agent session context was compacted to save space. "
                + "This is a coding agent session. "
                + "%d previous exchanges were summarized. "
                + "You can re-read files or re-run tools if you need to recover earlier context.]",
                Math.min(userMessages, assistantMessages));
    }


    /**
     * Converts stored conversation messages to the provider-agnostic AI message
     * format, sorted by creation time.
     *
     * <p>Native tool exchanges are rebuilt from the persisted payload
     * ({@link ConversationMessage#getToolCalls()} on the assistant turn,
     * {@link ConversationMessage#getToolCallId()} on the tool row), so a follow-up
     * run replays the same pairs the previous run produced. Rows that cannot be
     * paired are dropped, because OpenAI/Anthropic reject a {@code role:"tool"}
     * message whose call id the preceding assistant turn did not announce:</p>
     * <ul>
     *   <li>a tool row without a {@code tool_call_id} (persisted before V53),</li>
     *   <li>a tool row whose id no preceding assistant turn announced, or that
     *       this turn already answered,</li>
     *   <li>an assistant row that is blank <em>and</em> carries no tool calls
     *       (a pre-V53 tool-call-only turn).</li>
     * </ul>
     *
     * <p>Removing an assistant turn therefore removes its tool rows too — a pair is
     * never replayed half-way.</p>
     */
    public List<AiMessage> toAiMessages(AgentSession session) {
        List<ConversationMessage> ordered = new ArrayList<>(session.getMessages());
        ordered.sort(Comparator.comparing(ConversationMessage::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        List<AiMessage> replay = new ArrayList<>();
        // Call ids announced by the most recent assistant turn, not yet answered.
        Set<String> unanswered = new LinkedHashSet<>();
        for (ConversationMessage message : ordered) {
            String role = message.getRole();
            if ("assistant".equalsIgnoreCase(role)) {
                List<ToolCall> toolCalls = parseToolCalls(message.getToolCalls());
                if (!toolCalls.isEmpty()) {
                    replay.add(AiMessage.builder()
                            .role(role)
                            .content(message.getContent())
                            .toolCalls(toolCalls)
                            .build());
                    unanswered.clear();
                    for (ToolCall call : toolCalls) {
                        unanswered.add(call.id());
                    }
                } else if (message.getContent() != null && !message.getContent().isBlank()) {
                    replay.add(AiMessage.builder().role(role).content(message.getContent()).build());
                    unanswered.clear();
                }
            } else if ("tool".equalsIgnoreCase(role)) {
                String callId = message.getToolCallId();
                if (callId == null || !unanswered.remove(callId)) {
                    continue;
                }
                replay.add(AiMessage.builder()
                        .role(role)
                        .content(message.getContent())
                        .toolResult(message.getContent())
                        .toolCallId(callId)
                        .build());
            } else {
                replay.add(AiMessage.builder().role(role).content(message.getContent()).build());
                unanswered.clear();
            }
        }
        return replay;
    }

    /**
     * Reads back an assistant turn's persisted tool calls. A payload that cannot be
     * parsed yields no calls, so the turn is replayed as if it had none.
     */
    private static List<ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(AgentJackson.mapper().readValue(json, ToolCall[].class));
        } catch (Exception e) {
            log.warn("Could not parse persisted tool_calls payload: {}", e.getMessage());
            return List.of();
        }
    }
}
