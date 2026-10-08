package org.remus.giteabot.agent.codeexecution;

import lombok.extern.slf4j.Slf4j;

import org.remus.giteabot.util.ProcessSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The pool of identities the execute-code interpreter may be switched to, one per running execution.
 *
 * <p>One identity per execution, rather than one sandbox account for all of them, is what makes
 * concurrent runs strangers to each other. A single sandbox uid is a single principal: a program can
 * list {@code /tmp/execute-code-*}, read the next run's source, connect to its bridge socket — which
 * answers with <em>that</em> run's bot whitelist, MCP configuration and checkout — and signal its
 * process, because the uid that owns all of it is its own. The slot a run takes fixes the account
 * sudo switches to and the group its workspace is handed over to.</p>
 *
 * <p>The pool is the file docker/install-execute-code-sandbox.sh writes and generates its sudo rule
 * from, so what the deployment provisioned and what this class hands out cannot drift.</p>
 *
 * <p>Reading the pool also clears it: each slot is asked to kill the processes it still owns.
 * Anything a slot owns when the pool is first read is a leftover from a JVM that died holding it —
 * nothing else uses those uids — and leaving it would hand a fresh run a uid something else is
 * already running as, which is the one thing the per-run identity is there to prevent. The same call
 * proves the slot works: a missing sudo rule, or a slot sudo will not switch to, fails here, before
 * an execution starts, rather than in the middle of one. A failed read is not remembered —
 * provisioning fixed after a bad start is picked up without a restart. Changing which slots the pool
 * <em>holds</em> does need one, since the queue built from the first successful read is what is
 * handed out.</p>
 *
 * <p>{@link #acquire()} waits a bounded time and then reports failure rather than queueing for ever:
 * a slot is held for the length of one execution, so a wait longer than that cannot succeed, and a
 * run that cannot get an identity must not fall back to running as the service user.</p>
 */
@Slf4j
final class SandboxSlots {

    /** One identity: the account sudo switches to, and the group the workspace is handed over to. */
    record Slot(String name, long uid, long gid) {
    }

    /**
     * What stops everything a slot owns, and nothing else. Issued as the slot, so the caller needs
     * no right of its own, and by uid rather than by process group: the group the JVM created holds
     * the sudo process, while the program itself runs in a session of its own — sudo gives it a pty
     * — so the group kill the timeout uses everywhere else could not reach it.
     */
    private static final List<String> STOP_EVERYTHING = List.of("kill", "-KILL", "--", "-1");

    /** How long the pool read gets: one small file, then one command per slot. */
    private static final long LOAD_TIMEOUT_SECONDS = 10;

    /** Enough for a line per slot plus a refused switch, and a bound even if something misbehaves. */
    private static final int LOAD_OUTPUT_BYTES = 16 * 1024;

    /** How long one slot-clearing command may take. A kill that has not run in ten seconds will not. */
    private static final long STOP_TIMEOUT_SECONDS = 10;

    /** How long clearing a finished run's workspace may take. A tree that size deletes in a moment. */
    private static final long DELETE_TIMEOUT_SECONDS = 10;

    private final String slotsFile;
    private final String sudo;
    private final Duration wait;
    private volatile BlockingQueue<Slot> pool;

    SandboxSlots(String slotsFile, String sudo, Duration wait) {
        this.slotsFile = slotsFile;
        this.sudo = sudo;
        this.wait = wait;
    }

    /**
     * One free slot, or {@code null} when every slot stayed busy for longer than the wait.
     *
     * @throws IOException when the pool cannot be read, or does not describe usable identities: a
     *         deployment that configured one must not silently run the program as the service user.
     */
    Slot acquire() throws IOException, InterruptedException {
        return pool().poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Hands a slot back. Not a lease with an owner: the run is over, and the slot is free for the
     * next execution as soon as its workspace is gone.
     */
    void release(Slot slot) {
        BlockingQueue<Slot> queue = pool;
        if (slot != null && queue != null && !queue.offer(slot)) {
            // Only reachable if a slot was released twice; the next acquire() then reports a busy
            // pool, which is a visible wrong answer rather than a silent one.
            log.warn("execute-code: sandbox slot {} did not go back into the pool", slot.name());
        }
    }

    /** How many slots the pool holds, for a failure that can say what to provision more of. */
    int size() throws IOException {
        BlockingQueue<Slot> queue = pool();
        return queue.size() + queue.remainingCapacity();
    }

    /**
     * Stops everything this slot owns, and nothing else. What the timeout of a sandboxed execution
     * runs, because the JVM cannot signal another user's processes.
     *
     * <p>Best effort by design: it is called while the caller already deals with a timeout or a
     * teardown, and a failure leaves a process behind only until the next pool read clears it.</p>
     */
    void stop(Slot slot) {
        ProcessSupport.CommandResult result;
        try {
            result = clear(slot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            log.warn("execute-code: could not stop sandbox slot {}: {}", slot.name(), e.getMessage());
            return;
        }
        if (!result.finished() || result.exitCode() != 0) {
            log.warn("execute-code: could not stop sandbox slot {}: {}", slot.name(),
                    result.output().trim());
        }
    }

    /**
     * Removes everything inside a finished execution's workspace, as the slot it ran as.
     *
     * <p>The program's own temp files and any directories it created belong to the slot, and the
     * JVM — which owns the workspace but only holds the slot's group — cannot traverse a directory
     * the program made 0700, let alone delete inside it, so a plain {@code Files.walk} leaves the
     * tree behind. Deleting the contents as the slot sidesteps the permission question entirely;
     * the now-empty directory the JVM created is then removed by the caller. Best effort, like
     * {@link #stop}: a failure is logged and the caller's own sweep reports whatever remains.</p>
     *
     * <p>{@code find … -delete} rather than a shell {@code rm -rf}: the workspace path is
     * JVM-generated, but a shell would still be a second interpreter on the privileged path, and
     * the slot may run nothing else.</p>
     */
    void delete(Slot slot, Path workspace) {
        if (workspace == null) {
            return;
        }
        List<String> argv = new ArrayList<>(List.of(sudo, "-n", "-u", slot.name(), "--",
                "find", workspace.toString(), "-mindepth", "1", "-delete"));
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.redirectErrorStream(true);
        ProcessSupport.scrubEnvironment(processBuilder);
        ProcessSupport.CommandResult result;
        try {
            result = ProcessSupport.run(processBuilder, DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                    LOAD_OUTPUT_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            log.warn("execute-code: could not clear workspace {} as sandbox slot {}: {}",
                    workspace, slot.name(), e.getMessage());
            return;
        }
        if (!result.finished() || result.exitCode() != 0) {
            log.warn("execute-code: could not clear workspace {} as sandbox slot {}: {}",
                    workspace, slot.name(), result.output().trim());
        }
    }

    private BlockingQueue<Slot> pool() throws IOException {
        BlockingQueue<Slot> current = pool;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (pool == null) {
                List<Slot> read = read();
                // Capacity is the pool size, not Integer.MAX_VALUE: the queue is the pool, and its
                // remaining capacity is what `size()` reports as "how many could be handed out".
                BlockingQueue<Slot> queue = new LinkedBlockingQueue<>(read.size());
                queue.addAll(read);
                pool = queue;
            }
            return pool;
        }
    }

    private List<Slot> read() throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(slotsFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("sandbox pool " + slotsFile + " could not be read: " + e.getMessage(), e);
        }
        List<Slot> slots = new ArrayList<>();
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty()) {
                continue;
            }
            String[] fields = value.split("\\s+");
            if (fields.length != 3) {
                throw new IOException("sandbox pool " + slotsFile + " has an unusable line: " + value);
            }
            long uid = number(fields[1], value);
            long gid = number(fields[2], value);
            if (uid <= 0 || gid <= 0) {
                // uid 0 named here would be a pool entry for root: the sudo rule does not cover it,
                // so it could only ever fail — but it is worth refusing loudly rather than later.
                throw new IOException("sandbox pool " + slotsFile + " names a privileged identity: " + value);
            }
            slots.add(new Slot(fields[0], uid, gid));
        }
        if (slots.isEmpty()) {
            throw new IOException("sandbox pool " + slotsFile + " is empty");
        }
        // Clearing is the check that each slot works as well: see the class comment.
        for (Slot slot : slots) {
            ProcessSupport.CommandResult result;
            try {
                result = clear(slot);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while preparing sandbox slot " + slot.name());
            }
            if (!result.finished() || result.exitCode() != 0) {
                throw new IOException("sandbox slot " + slot.name() + " cannot be used: "
                        + result.output().trim());
            }
        }
        return slots;
    }

    private static long number(String field, String line) throws IOException {
        try {
            return Long.parseLong(field);
        } catch (NumberFormatException e) {
            throw new IOException("sandbox pool line is not \"name uid gid\": " + line);
        }
    }

    /**
     * Kills the processes the slot still owns, as the slot. Through sudo, because only sudo may
     * switch to it, and only the slot may signal what it started — {@code kill -1} is every process
     * the caller has permission for, which for a slot nothing else runs as is exactly this run.
     */
    private ProcessSupport.CommandResult clear(Slot slot) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(List.of(sudo, "-n", "-u", slot.name(), "--"));
        argv.addAll(STOP_EVERYTHING);
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.redirectErrorStream(true);
        ProcessSupport.scrubEnvironment(processBuilder);
        return ProcessSupport.run(processBuilder, STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                LOAD_OUTPUT_BYTES);
    }
}
