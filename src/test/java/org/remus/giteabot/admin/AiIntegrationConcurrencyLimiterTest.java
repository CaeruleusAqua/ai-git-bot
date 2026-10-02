package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiIntegrationConcurrencyLimiterTest {

    private final AiIntegrationConcurrencyLimiter limiter = new AiIntegrationConcurrencyLimiter();

    private static AiIntegration integration(long id, int limit) {
        AiIntegration integration = new AiIntegration();
        integration.setId(id);
        integration.setParallelWorkerLimit(limit);
        return integration;
    }

    @Test
    void returnsTheActionResultWhenTheIntegrationIsUnlimited() {
        assertEquals("ok", limiter.withPermit(integration(1L, 0), () -> "ok"));
    }

    @Test
    void runsWithoutACapWhenTheLimitIsZero() throws Exception {
        int max = maxConcurrency(0, 4);
        assertTrue(max > 1, "limit 0 must allow parallel execution, observed max=" + max);
    }

    @Test
    void limitOneSerialisesJobsOfTheSameIntegration() throws Exception {
        assertEquals(1, maxConcurrency(1, 4));
    }

    @Test
    void limitNCapsConcurrentJobs() throws Exception {
        int max = maxConcurrency(2, 6);
        assertTrue(max <= 2, "at most 2 jobs may run at the same time, observed " + max);
    }

    @Test
    void twoJobsRunTogetherUnderALimitOfTwo() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable job = () -> limiter.runWithPermit(integration(1L, 2), () -> {
            bothInside.countDown();
            await(release);
        });
        Thread first = new Thread(job);
        Thread second = new Thread(job);
        first.start();
        second.start();

        assertTrue(bothInside.await(5, TimeUnit.SECONDS),
                "two jobs must run concurrently under a limit of 2");
        release.countDown();
        first.join(5000);
        second.join(5000);
    }

    @Test
    void queuedJobStartsWhenASlotFreesUp() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);

        Thread first = new Thread(() -> limiter.runWithPermit(integration(1L, 1), () -> {
            firstInside.countDown();
            await(releaseFirst);
        }));
        first.start();
        assertTrue(firstInside.await(5, TimeUnit.SECONDS), "first job must start");

        Thread second = new Thread(() -> limiter.runWithPermit(integration(1L, 1), secondInside::countDown));
        second.start();
        assertFalse(secondInside.await(200, TimeUnit.MILLISECONDS),
                "the second job must wait for a free slot");

        releaseFirst.countDown();
        assertTrue(secondInside.await(5, TimeUnit.SECONDS),
                "the queued job must start once the running one releases its slot");
        first.join(5000);
        second.join(5000);
    }

    @Test
    void differentIntegrationsDoNotBlockEachOther() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);

        Thread first = new Thread(() -> limiter.runWithPermit(integration(1L, 1), () -> {
            firstInside.countDown();
            await(releaseFirst);
        }));
        first.start();
        assertTrue(firstInside.await(5, TimeUnit.SECONDS), "first job must start");

        Thread second = new Thread(() -> limiter.runWithPermit(integration(2L, 1), secondInside::countDown));
        second.start();
        assertTrue(secondInside.await(5, TimeUnit.SECONDS),
                "another integration's job must not be blocked by this integration's limit");

        releaseFirst.countDown();
        first.join(5000);
        second.join(5000);
    }

    @Test
    void aNullIntegrationRunsWithoutACap() {
        assertEquals("ok", limiter.withPermit(null, () -> "ok"));
    }

    @Test
    void anUnsavedIntegrationRunsWithoutACap() {
        AiIntegration unsaved = new AiIntegration();
        unsaved.setParallelWorkerLimit(1);
        assertNotNull(limiter.withPermit(unsaved, () -> "ok"));
    }

    @Test
    void aRaisedLimitTakesEffectForSubsequentJobs() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AiIntegration integration = integration(1L, 1);

        Thread first = new Thread(() -> limiter.runWithPermit(integration, () -> {
            firstInside.countDown();
            await(releaseFirst);
        }));
        first.start();
        assertTrue(firstInside.await(5, TimeUnit.SECONDS), "first job must start");

        integration.setParallelWorkerLimit(2);
        CountDownLatch secondInside = new CountDownLatch(1);
        Thread second = new Thread(() -> limiter.runWithPermit(integration, secondInside::countDown));
        second.start();
        assertTrue(secondInside.await(5, TimeUnit.SECONDS),
                "the raised limit must make a second slot available immediately");

        releaseFirst.countDown();
        first.join(5000);
        second.join(5000);
    }

    /** Runs {@code tasks} jobs of one integration concurrently and returns the peak concurrency. */
    private int maxConcurrency(int limit, int tasks) throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    limiter.runWithPermit(integration(1L, limit), () -> {
                        int now = concurrent.incrementAndGet();
                        maxConcurrent.accumulateAndGet(now, Math::max);
                        sleep(40);
                        concurrent.decrementAndGet();
                    });
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return maxConcurrent.get();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
