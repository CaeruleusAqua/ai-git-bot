package org.remus.giteabot.ai;

import java.time.Duration;
import java.time.Instant;

/**
 * Thread-local comment target for provider-overload retry notices: the
 * workflow (or issue workflow) that is currently running on this thread
 * installs a {@link Notice}, so {@link RetryAiClient} can tell the affected
 * pull request or issue when the next attempt happens.
 *
 * <p>Like {@link AiAuditContext}, callers that install a notice must
 * {@link #clear()} it in a {@code finally} block. When no notice is installed
 * (e.g. an admin "test connection" call), retries still happen — only the
 * comment is skipped.</p>
 */
public final class AiRetryContext {

    /** Posts one retry notice; implementations must never throw. */
    @FunctionalInterface
    public interface NoticeSink {
        void post(String markdown);
    }

    /**
     * Where retry notices for the current workflow run are posted.
     *
     * @param label human-readable name of the workflow, e.g. {@code agentic-review}
     * @param sink  the comment target
     */
    public record Notice(String label, NoticeSink sink) {
    }

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private AiRetryContext() {
    }

    public static void install(Notice notice) {
        CURRENT.set(new State(notice));
    }

    /** The installed notice, or {@code null} when this thread has none. */
    public static Notice notice() {
        State state = CURRENT.get();
        return state == null ? null : state.notice;
    }

    public static void clear() {
        CURRENT.remove();
    }

    static State state() {
        return CURRENT.get();
    }

    /**
     * Per-run notice bookkeeping: at most one "retry scheduled" comment per
     * cooldown window and at most one "retries exhausted" comment.
     */
    static final class State {

        final Notice notice;
        private Instant lastNoticeAt;
        private boolean exhaustedNotified;

        State(Notice notice) {
            this.notice = notice;
        }

        boolean claimScheduledNotice(Duration cooldown, Instant now) {
            if (lastNoticeAt != null && now.isBefore(lastNoticeAt.plus(cooldown))) {
                return false;
            }
            lastNoticeAt = now;
            return true;
        }

        boolean claimExhaustedNotice() {
            if (exhaustedNotified) {
                return false;
            }
            exhaustedNotified = true;
            return true;
        }
    }
}
