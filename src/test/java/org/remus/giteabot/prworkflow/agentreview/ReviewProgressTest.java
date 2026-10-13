package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.loop.AgentRunContext.ProgressUpdate;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewProgressTest {

    @Test
    void reusesOneCommentThrottlesActivityAndAlwaysWritesTerminalState() {
        RepositoryApiClient client = mock(RepositoryApiClient.class);
        when(client.createPullRequestComment(eq("owner"), eq("repo"), eq(1L), anyString())).thenReturn(42L);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-13T00:00:00.123456Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        ReviewProgress progress = new ReviewProgress(client, "owner", "repo", 1L, clock);

        progress.start();
        progress.accept(new ProgressUpdate(1, "Waiting for model", 0));
        now.set(now.get().plusSeconds(14));
        progress.accept(new ProgressUpdate(1, "Repository inspection completed", 1));
        now.set(now.get().plusSeconds(1));
        progress.accept(new ProgressUpdate(2, "Waiting for model", 0));
        now.set(now.get().plusSeconds(1));
        progress.accept(new ProgressUpdate(2, "Repository inspection completed", 2));
        progress.finish(true);

        var bodies = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).createPullRequestComment(eq("owner"), eq("repo"), eq(1L), eq("""
                ## 🤖 Agentic Review Progress

                Tracking the review progress for **PR #1**.

                - Latest activity: Preparing repository
                - Rounds: 0 · Inspection tool calls completed: 0
                - Started: 2026-10-13 00:00:00 UTC
                - Elapsed: 0s · Last update: 2026-10-13 00:00:00 UTC"""));
        verify(client, times(3)).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(42L), bodies.capture());
        assertThat(bodies.getAllValues()).satisfiesExactly(
                body -> assertThat(body).contains("Rounds: 1", "Elapsed: 0s", "Inspection tool calls completed: 0"),
                body -> assertThat(body).contains("Rounds: 2", "Elapsed: 15s", "Inspection tool calls completed: 1"),
                body -> assertThat(body).isEqualTo("""
                        ## 🤖 Agentic Review Progress

                        Tracking the review progress for **PR #1**.

                        - Latest activity: Review completed
                        - Rounds: 2 · Inspection tool calls completed: 3
                        - Started: 2026-10-13 00:00:00 UTC
                        - Elapsed: 16s · Last update: 2026-10-13 00:00:16 UTC"""));
    }

    @Test
    void failedCommentWriteIsExposed() {
        RepositoryApiClient client = mock(RepositoryApiClient.class);
        when(client.createPullRequestComment(eq("owner"), eq("repo"), eq(1L), anyString())).thenReturn(42L);
        ReviewProgress progress = new ReviewProgress(client, "owner", "repo", 1L, Clock.systemUTC());
        progress.start();
        doThrow(new IllegalStateException("Comment update denied")).when(client)
                .updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(42L), anyString());

        assertThatThrownBy(() -> progress.accept(new ProgressUpdate(1, "Waiting for model", 0)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Comment update denied");
    }

    @Test
    void formatsLongElapsedTimeWithoutAssumingTheServersTimezone() {
        RepositoryApiClient client = mock(RepositoryApiClient.class);
        when(client.createPullRequestComment(anyString(), anyString(), anyLong(), anyString())).thenReturn(42L);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:00:00Z"),
                Instant.parse("2026-10-13T00:00:00Z"), Instant.parse("2026-10-13T01:01:15Z"));
        ReviewProgress progress = new ReviewProgress(client, "owner", "repo", 1L, clock);
        progress.start();

        progress.finish(false);

        verify(client).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(42L),
                argThat(body -> body.contains("Elapsed: 1h 1m 15s")
                        && body.contains("Last update: 2026-10-13 01:01:15 UTC")));
    }
}
