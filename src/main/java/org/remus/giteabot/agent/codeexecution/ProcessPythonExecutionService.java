package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.util.ProcessSupport;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
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
 * <p>stdout carries the program's output and nothing else. Tool calls travel over an {@code AF_UNIX}
 * socket in the temp directory (permissions are filesystem permissions: no port, nothing to
 * authenticate) and are served by a second thread while this one drains the process's output —
 * draining only after the conversation finished would deadlock as soon as a program printed more
 * than a pipe buffer before calling a tool.</p>
 *
 * <p>The sandbox decides nothing about which tools exist or whether the run is allowed: it relays a
 * name and its arguments to the surface's own executor ({@link PythonToolExecutor}) and hands the
 * answer back. The bot's tool whitelist is what decides whether a program runs at all, exactly as it
 * does for every other tool.</p>
 *
 * <p>Two deviations from the plan's wording, both deliberate: the serving thread is a platform daemon
 * thread rather than a virtual one (there is one per execution, so the scheduler buys nothing), and
 * the process's output is drained by {@link ProcessSupport} rather than by a thread of our own — it
 * already bounds the captured bytes and escalates a timeout to a process-group kill.</p>
 */
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
            workspace = Files.createTempDirectory("execute-code-");
            restrictToOwner(workspace);
            writeResource(workspace, BOOTSTRAP_FILE, BOOTSTRAP_RESOURCE);
            writeResource(workspace, MODULE_FILE, MODULE_RESOURCE);
            Files.writeString(workspace.resolve(PROGRAM_FILE), code, StandardCharsets.UTF_8);

            Path socketPath = workspace.resolve(SOCKET_FILE);
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(socketPath));
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
     * bytecode.
     */
    ProcessBuilder command(Path workspace) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                limits.pythonBinary(), "-I", "-u", "-B",
                workspace.resolve(BOOTSTRAP_FILE).toString(),
                workspace.resolve(PROGRAM_FILE).toString());
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

    private PythonExecutionOutcome outcome(ProcessSupport.CommandResult result, PythonToolBridge bridge) {
        String output = result.output();
        int cap = limits.maxResultChars();
        boolean truncated = output.getBytes(StandardCharsets.UTF_8).length > cap;
        if (truncated) {
            // Bounded by characters too: what the model reads is what it pays for.
            output = output.length() > cap ? output.substring(0, cap) : output;
            output = output + "\n[output truncated at " + cap + " chars]";
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
