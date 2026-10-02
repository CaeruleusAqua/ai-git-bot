package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * JVM-local, per-{@link AiIntegration} concurrency control for AI job execution.
 *
 * <p>Every AI integration carries a {@code parallelWorkerLimit}: {@code 0} (the
 * default) means unlimited parallel execution — the historical behaviour — while
 * {@code 1..20} caps how many jobs of that integration may run at the same time.
 * {@link #withPermit} blocks the calling thread until a slot is free, so excess
 * jobs queue instead of being dropped or failing. Different integrations use
 * different permit pools and never block each other.</p>
 *
 * <p>Because the affected work is dispatched on virtual threads (the
 * {@code @Async} webhook entrypoints in {@code BotWebhookService}), waiting on a
 * slot costs one cheap thread, not a platform-thread-pool slot.</p>
 *
 * <h2>Scope and limitations</h2>
 * <ul>
 *     <li><strong>Single-instance only.</strong> Like
 *     {@code PrWorkflowRunLockManager} the permits live in this JVM, so a
 *     multi-instance deployment applies the limit per instance.</li>
 *     <li>The permit pool is rebuilt when the configured limit changes, so a
 *     limit edited at runtime takes effect for jobs that start afterwards. A job
 *     already holding a permit keeps it on the previous pool and releases it
 *     there.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiIntegrationConcurrencyLimiter {

    private final ConcurrentMap<Long, Gate> gates = new ConcurrentHashMap<>();

    /**
     * Runs {@code action} while holding one concurrency slot for
     * {@code integration}, returning its result. Blocks until a slot becomes
     * available when the integration's limit is reached (queuing the job); runs
     * immediately when the limit is {@code 0} / negative or the integration is
     * unknown / unsaved.
     */
    public <T> T withPermit(AiIntegration integration, Supplier<T> action) {
        Gate gate = gateFor(integration);
        if (gate == null) {
            return action.get();
        }
        acquire(gate.semaphore());
        try {
            return action.get();
        } finally {
            gate.semaphore().release();
        }
    }

    /** {@link #withPermit(AiIntegration, Supplier)} for an action with no result. */
    public void runWithPermit(AiIntegration integration, Runnable action) {
        withPermit(integration, () -> {
            action.run();
            return null;
        });
    }

    /**
     * The permit pool for {@code integration}, or {@code null} when no cap should
     * apply (unlimited / unknown integration). The pool is replaced when the
     * configured limit changes.
     */
    private Gate gateFor(AiIntegration integration) {
        if (integration == null || integration.getId() == null) {
            return null;
        }
        int limit = integration.getParallelWorkerLimit();
        if (limit <= 0) {
            return null;
        }
        return gates.compute(integration.getId(),
                (id, existing) -> existing != null && existing.limit() == limit
                        ? existing
                        : new Gate(limit, new Semaphore(limit, true)));
    }

    /**
     * Acquires a slot, waiting until one is free. An interrupt aborts the wait
     * (restoring the interrupt flag) rather than silently running without a
     * permit — a workflow run that is being shut down must not pile onto the
     * provider.
     */
    private void acquire(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for a free AI integration concurrency slot", e);
        }
    }

    /** The permit pool of one integration; rebuilt when {@code limit} changes. */
    private record Gate(int limit, Semaphore semaphore) {
    }
}
