package org.remus.giteabot.agent.codeexecution;

import lombok.extern.slf4j.Slf4j;

import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.util.ProcessSupport;
import org.remus.giteabot.util.TextSupport;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The Layer 1 sandbox: a restricted local subprocess.
 *
 * <p>{@code python3 -I -u -B bootstrap.py program.py}, in a fresh 0700 temp directory that is also
 * its cwd and {@code TMPDIR}, with a scrubbed environment, rlimits applied by the bootstrap, and a
 * wall-clock timeout enforced here. The container is the real isolation boundary
 * ({@code sandbox-approach.md} / the execute-code plan, ADR-1) — nothing in this class claims
 * otherwise.</p>
 *
 * <p>Where the deployment names a sandbox account, the program is not run as the service user at
 * all: {@link #command} puts {@code setpriv} in front of the interpreter, and the temp directory,
 * the files in it and the bridge socket are opened to the account's group. The program then reads
 * neither the service user's files nor this JVM's start-time environment
 * ({@code /proc/<jvm-pid>/environ} is granted to same-uid readers only). Two things are needed on
 * the JVM side and both are provisioned in the image (see the Dockerfile): a {@code setpriv} that
 * carries {@code CAP_SETUID}/{@code CAP_SETGID}, because a non-root JVM cannot switch uid on its
 * own, and {@code CAP_KILL} on the JVM itself, because the child it produced can no longer be
 * signalled by uid.</p>
 *
 * <p>stdout carries the program's output and nothing else. Tool calls travel over an {@code AF_UNIX}
 * socket in the temp directory (permissions are filesystem permissions: no port, nothing to
 * authenticate) and are served by a second thread while this one drains the process's output —
 * draining only after the conversation finished would deadlock as soon as a program printed more
 * than a pipe buffer before calling a tool.</p>
 *
 * <p>The sandbox decides nothing about which tools exist or whether the run is allowed: it relays a
 * name and its arguments to the surface's own executor ({@link PythonToolExecutor}) and hands the
 * answer back. Whether a bot may run a program at all is decided before this service is reached: the
 * tool is opt-in per bot, and {@link org.remus.giteabot.agent.tools.AgentToolRouter} refuses it when
 * the bot's configuration does not select it.</p>
 *
 * <p>Two deviations from the plan's wording, both deliberate: the serving thread is a platform daemon
 * thread rather than a virtual one (there is one per execution, so the scheduler buys nothing), and
 * the process's output is drained by {@link ProcessSupport} rather than by a thread of our own — it
 * already bounds the captured bytes and escalates a timeout to a process-group kill.</p>
 */
@Slf4j
@Service
public class ProcessPythonExecutionService implements PythonExecutionService {

    private static final String BRIDGE_ENV = "AI_GIT_BOT_BRIDGE";
    private static final String BOOTSTRAP_RESOURCE = "/codeexecution/bootstrap.py";
    private static final String MODULE_RESOURCE = "/codeexecution/ai_git_bot.py";
    private static final String BOOTSTRAP_FILE = "bootstrap.py";
    private static final String MODULE_FILE = "ai_git_bot.py";
    private static final String PROGRAM_FILE = "program.py";
    private static final String SOCKET_FILE = "bridge.sock";
    private static final String BRIDGE_THREAD = "execute-code-bridge";

    /**
     * Permissions the workspace, the files in it and the bridge socket carry when the program runs
     * as a sandbox account: reachable by the group both accounts share and by nobody else. The group
     * is what makes that possible without giving the JVM a second privilege — it owns the directory
     * either way.
     */
    private static final String SANDBOX_DIRECTORY_MODE = "rwxrwx---";
    private static final String SANDBOX_FILE_MODE = "rw-r-----";
    private static final String SANDBOX_SOCKET_MODE = "rw-rw----";

    /**
     * Captured beyond the configured cap, purely so truncation can be detected: with the cap passed
     * straight through, a result that stopped exactly at the cap would be indistinguishable from one
     * that was cut off.
     */
    private static final int OUTPUT_SLACK_BYTES = 1024;

    /** The conventional timeout exit code, so a caller can tell it from the program's own. */
    private static final int EXIT_TIMEOUT = 124;

    private final CodeExecutionLimits limits;
    private final ObjectMapper json = new ObjectMapper();

    public ProcessPythonExecutionService(AgentConfigProperties config) {
        this.limits = CodeExecutionLimits.from(config);
    }

    @Override
    public PythonExecutionOutcome execute(String code, CodeExecutionScope scope) {
        if (code == null || code.isBlank()) {
            return PythonExecutionOutcome.failed(1, "", "no program submitted");
        }
        int codeBytes = code.getBytes(StandardCharsets.UTF_8).length;
        if (codeBytes > limits.maxCodeBytes()) {
            return PythonExecutionOutcome.failed(1, "",
                    "program is " + codeBytes + " bytes, over the limit of "
                            + limits.maxCodeBytes() + " bytes");
        }

        Path workspace = null;
        try {
            GroupPrincipal sandboxGroup = sandboxGroup();
            workspace = Files.createTempDirectory("execute-code-");
            if (sandboxGroup == null) {
                restrictToOwner(workspace);
            } else {
                openToSandbox(workspace, sandboxGroup, SANDBOX_DIRECTORY_MODE);
            }
            writeResource(workspace, BOOTSTRAP_FILE, BOOTSTRAP_RESOURCE);
            writeResource(workspace, MODULE_FILE, MODULE_RESOURCE);
            Files.writeString(workspace.resolve(PROGRAM_FILE), code, StandardCharsets.UTF_8);
            if (sandboxGroup != null) {
                // The program has to read all three and the JVM created them, so their group is
                // settled here rather than by a umask.
                for (String file : List.of(BOOTSTRAP_FILE, MODULE_FILE, PROGRAM_FILE)) {
                    openToSandbox(workspace.resolve(file), sandboxGroup, SANDBOX_FILE_MODE);
                }
            }

            Path socketPath = workspace.resolve(SOCKET_FILE);
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(socketPath));
                if (sandboxGroup != null) {
                    // Connecting to a unix socket needs write permission on it, so the socket is the
                    // one path the group may write.
                    openToSandbox(socketPath, sandboxGroup, SANDBOX_SOCKET_MODE);
                }
                PythonToolBridge bridge = new PythonToolBridge(scope.available(), scope.executor(),
                        limits, json);
                Thread serving = startServing(server, bridge);
                ProcessSupport.CommandResult result;
                try {
                    result = ProcessSupport.run(command(workspace),
                            limits.timeout().toSeconds(), TimeUnit.SECONDS,
                            limits.maxResultChars() + OUTPUT_SLACK_BYTES);
                } finally {
                    serving.interrupt();
                }
                awaitServing(serving);
                return outcome(result, bridge);
            }
        } catch (IOException e) {
            return PythonExecutionOutcome.failed(1, "", "sandbox failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PythonExecutionOutcome.failed(1, "", "execution interrupted");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The argv. Package-private so a test can pin it: {@code -I} isolates the interpreter from the
     * environment, the user site directory and the script's path entry, {@code -u} keeps the
     * program's output arriving instead of sitting in a pipe buffer (a timeout would otherwise lose
     * the lines that explain what it was doing), {@code -B} keeps the sandbox directory free of
     * bytecode. A configured sandbox account puts {@code setpriv} in front of the interpreter, so
     * the program never starts with the service user's identity and drops it afterwards.
     */
    ProcessBuilder command(Path workspace) {
        List<String> argv = new ArrayList<>();
        if (!limits.sandboxUser().isBlank()) {
            argv.add(limits.setprivBinary());
            argv.add("--reuid=" + limits.sandboxUser());
            argv.add("--regid=" + limits.sandboxGroup());
            // No supplementary groups: the account keeps its primary group only, which is the one the
            // workspace is opened to.
            argv.add("--clear-groups");
        }
        argv.add(limits.pythonBinary());
        argv.add("-I");
        argv.add("-u");
        argv.add("-B");
        argv.add(workspace.resolve(BOOTSTRAP_FILE).toString());
        argv.add(workspace.resolve(PROGRAM_FILE).toString());
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.directory(workspace.toFile());
        // stdout and stderr are one stream: the program's output is the tool result, and a traceback
        // belongs in it rather than in a log nobody reads.
        processBuilder.redirectErrorStream(true);

        // The environment is replaced first; the sandbox's own variables are added after, or the
        // scrub would remove them.
        ProcessSupport.scrubEnvironment(processBuilder);
        Map<String, String> environment = processBuilder.environment();
        environment.put(BRIDGE_ENV, workspace.resolve(SOCKET_FILE).toString());
        environment.put("AI_GIT_BOT_LIMIT_AS_BYTES", Long.toString(limits.maxMemoryBytes()));
        environment.put("AI_GIT_BOT_LIMIT_CPU_SECONDS", Integer.toString(limits.cpuSeconds()));
        environment.put("AI_GIT_BOT_LIMIT_FSIZE_BYTES", Long.toString(limits.maxFileSizeBytes()));
        environment.put("AI_GIT_BOT_LIMIT_NPROC", Integer.toString(limits.maxProcesses()));
        // Temp files land in the directory that is deleted with the execution, not in /tmp.
        environment.put("TMPDIR", workspace.toString());
        return processBuilder;
    }

    private Thread startServing(ServerSocketChannel server, PythonToolBridge bridge) {
        Thread serving = new Thread(() -> serve(server, bridge), BRIDGE_THREAD);
        serving.setDaemon(true);
        serving.start();
        return serving;
    }

    /**
     * Accepts one connection at a time and answers frame by frame until the socket is closed
     * (teardown) or the program stops asking. A program that opens a second connection is served
     * after the first closes: sequential by design, since the Python side blocks on every reply.
     */
    private void serve(ServerSocketChannel server, PythonToolBridge bridge) {
        while (!Thread.currentThread().isInterrupted()) {
            try (SocketChannel connection = server.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(connection),
                                StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(connection),
                                StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    writer.write(bridge.handle(line).toString());
                    writer.write('\n');
                    writer.flush();
                }
            } catch (ClosedChannelException e) {
                // Also covers ClosedByInterruptException and AsynchronousCloseException: the server
                // (or the connection) was closed at teardown, which ends the loop.
                return;
            } catch (IOException e) {
                if (!server.isOpen()) {
                    return;
                }
                // The program died mid-conversation. Its output carries the diagnosis, so this only
                // needs to keep the serving loop alive for a reconnect.
            }
        }
    }

    /**
     * Waits for an in-flight nested call before the workspace it may be reading is deleted.
     *
     * <p>{@code interrupt()} does not abort a call blocked on I/O, so a program that ran out of time
     * can leave one behind. The bound is the tool timeout the nested call is itself subject to: a call
     * that respects its own timeout finishes inside it, and one that does not is reported instead of
     * waited on for ever. Either way the workspace is removed underneath it, which is why the warning
     * is not decoration.</p>
     *
     * <p>Nested calls run on {@code execute-code-bridge}, not on the agent's thread. Nothing depends on
     * that difference today — the only {@link ThreadLocal}s in the codebase are the AI audit and retry
     * contexts, and tool dispatch does not read them — but an executor that starts to will need its
     * context handed over here.</p>
     */
    private void awaitServing(Thread serving) {
        long bound = limits.timeout().toMillis();
        try {
            serving.join(bound);
            if (serving.isAlive()) {
                log.warn("execute-code: a nested tool call is still running {} ms after the program "
                        + "ended; the workspace is being removed underneath it", bound);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PythonExecutionOutcome outcome(ProcessSupport.CommandResult result, PythonToolBridge bridge) {
        String output = result.output();
        int cap = limits.maxResultChars();
        // Characters, the unit the marker names and every other result cap uses. A byte-based test
        // called multi-byte output truncated when nothing was cut, and substring() at a character
        // offset can split a surrogate pair.
        boolean cutByChars = output.length() > cap;
        if (cutByChars) {
            output = TextSupport.cutAtCodePoint(output, cap);
        }
        // The capture is bounded in bytes, at this cap plus one slack window, so a result that never
        // reaches the character cap can still have been cut: multi-byte text hits the byte budget
        // first. Marking only the character cut would hand back a shortened result as a complete one.
        boolean cutByBytes = output.getBytes(StandardCharsets.UTF_8).length >= cap + OUTPUT_SLACK_BYTES;
        if (cutByChars) {
            output = output + "\n[output truncated at " + cap + " chars]";
        } else if (cutByBytes) {
            output = output + "\n[output truncated at " + (cap + OUTPUT_SLACK_BYTES) + " bytes]";
        }

        String error = "";
        if (!result.finished()) {
            error = "timed out after " + limits.timeout().toSeconds() + "s";
        } else if (result.exitCode() != 0) {
            error = "program exited with code " + result.exitCode();
        }
        if (bridge.budgetExhausted()) {
            error = error.isEmpty()
                    ? "tool-call limit of " + limits.maxToolCalls() + " reached"
                    : error + "; tool-call limit of " + limits.maxToolCalls() + " reached";
        }
        if (!error.isEmpty()) {
            return PythonExecutionOutcome.failed(
                    result.finished() ? result.exitCode() : EXIT_TIMEOUT, output, error);
        }
        return PythonExecutionOutcome.success(output);
    }

    private static void writeResource(Path directory, String fileName, String resource)
            throws IOException {
        try (InputStream content = ProcessPythonExecutionService.class.getResourceAsStream(resource)) {
            if (content == null) {
                throw new IOException("classpath resource " + resource + " is missing");
            }
            Files.copy(content, directory.resolve(fileName));
        }
    }

    /**
     * The group both accounts share, or {@code null} when the deployment names no sandbox account.
     *
     * <p>Fails rather than degrades: a configured account that cannot be resolved, or that is named
     * without the group the workspace needs, must not leave the program running as the service user —
     * that is the whole point of configuring it.</p>
     */
    private GroupPrincipal sandboxGroup() throws IOException {
        String user = limits.sandboxUser();
        if (user.isBlank()) {
            return null;
        }
        String group = limits.sandboxGroup();
        if (group.isBlank()) {
            throw new IOException("sandbox account '" + user + "' is configured without a sandbox group");
        }
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByGroupName(group);
        } catch (UserPrincipalNotFoundException e) {
            throw new IOException("sandbox group '" + group + "' does not exist");
        }
    }

    /**
     * Opens one path in the workspace to the sandbox account's group.
     *
     * <p>The group is the one thing both accounts share (the JVM as its member, the program as its
     * primary group), so this is how the program reaches the bootstrap, its own source, its
     * {@code TMPDIR} and the bridge socket without the directory having to become world-accessible.</p>
     */
    private static void openToSandbox(Path path, GroupPrincipal group, String mode) throws IOException {
        Files.setAttribute(path, "posix:group", group);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }

    private static void restrictToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem: the directory is still the boundary, permissions are the
            // belt-and-braces part.
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A file the program left locked; the temp directory is not precious.
                }
            });
        } catch (IOException ignored) {
            // The directory was already gone.
        }
    }
}
