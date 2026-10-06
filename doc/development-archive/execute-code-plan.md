# Plan — `execute-code`: In-Sandbox Python Tool Orchestration

**Status:** Proposed
**Scope:** One new agent tool (`execute-code`), one new read-only tool-resolution layer shared by
every agentic surface, one new config block. **No DB migration.** No new runtime dependency
(`python3` already ships in the runtime image). No changes to the AI provider clients, the webhook
layer, or any `PrWorkflow`'s domain logic.

**Settled decisions** (settled with the maintainer before writing; this doc records decisions, not
open questions):

1. **This document lives in `doc/development-archive/`** (repo convention for plan/architecture
   docs), not in Hermes-side `.hermes/plans/`.
2. **V1 sandbox = restricted local subprocess** — `python3 -I`, `ProcessSupport.scrubEnvironment`,
   a per-execution temp cwd, process-group kill, wall-clock timeout, bounded output. In-process
   `resource.setrlimit()` from a bootstrap module supplies the memory / CPU / file-size bounds
   without privileges. Import-level network blocking is **defence-in-depth only**; the container is
   the real boundary for filesystem and network. This is Layer 1 of `sandbox-approach.md`; Layer 2
   (`unshare --net`, `prlimit`, `setpriv`) stays the documented opt-in upgrade (ADR-1).
3. **Scope = every agentic surface** — the four `AgentLoop` strategies *and* the five PR-workflow
   agents (E2E author/runner, unit-test author, readme-sync, i18n) with their four hand-rolled
   runners.
4. **MCP tool names are unchanged.** `mcp:<server>:<tool>` stays canonical and is exactly what
   generated Python passes to `tools.call(...)`. No dotted alias, no rename, no data migration
   (ADR-4).

**Origin:** the feature request (`execute-code` — deterministic in-Python orchestration of existing
tools so intermediate tool results never enter the LLM context). The evidence base for every
"as-built" claim below is the dispatch chain as read at HEAD of
`fork/CaeruleusAqua/feat/openrouter-routing-settings` (6 commits ahead of `develop`), with file
paths and line numbers named so each claim can be re-checked.

---

## 1. Goal

Give the model a single tool, `execute-code`, that runs a short, untrusted Python program in a
locked-down subprocess where **every tool call the program makes is routed back into Java and
executed by that surface's existing tool infrastructure**, so that N deterministic operations
(loops, filtering, aggregation, parsing, repeated reads) cost **one** LLM round and contribute
**zero** intermediate tool results to the conversation.

The optimisation is not Python. It is that the reasoning loop stops paying an inference per
deterministic step.

---

## 2. As-built: the dispatch chain this must plug into

### 2.1 Four dispatchers, three arg/result shapes

| Surface | Dispatcher | Arguments | Result | Context carrier |
|---|---|---|---|---|
| `AgentLoop` strategies (coding, writer, triage, agent-review) | `AgentToolRouter.execute(Mode, ToolCallContext)` — `agent/tools/AgentToolRouter.java:73` | positional `List<String>` | `ToolResult(success, exitCode, output, error)` | `ToolCallContext` (`owner`, `repo`, `issueNumber`, `workspaceDir`, `ImplementationPlan.ToolRequest`, `diffSummary`) |
| ↳ built-in families (file/context/validation) | `ToolExecutionService` — `agent/validation/ToolExecutionService.java:58,174,277` | positional | `ToolResult` | bare `Path workspaceDir` |
| ↳ MCP | `McpOrchestrationService.executeTool(cfg, catalog, qualifiedName, args)` — `mcp/McpOrchestrationService.java:76` | positional list, JSON-decoded from a single element | `ToolResult` | `McpConfiguration` + `McpToolCatalog` |
| E2E PR-workflow | `PrWorkflowToolExecutor.execute(name, Map, PrWorkflowToolContext)` — `prworkflow/e2e/tools/PrWorkflowToolExecutor.java:83` | structured `Map<String,Object>` | `String` — `"OK: …"` / `"ERROR: …"` | `PrWorkflowToolContext` |
| unit-test author | `UnitTestToolExecutor.execute(...)` — `prworkflow/unittest/tools/UnitTestToolExecutor.java:54` | structured | `String` | `UnitTestToolContext` |
| readme-sync | `ReadmeSyncToolExecutor.execute(...)` | structured | `String` | `ReadmeSyncToolContext` |
| i18n | `I18nToolExecutor.execute(...)` | structured | `String` | `I18nCoverageToolContext` |

The three shapes are: positional `List<String>` → `ToolResult` (built-in families); positional
with the single element JSON-decoded → `ToolResult` (MCP); structured `Map<String,Object>` →
`"OK:"`/`"ERROR:"` string (PR-workflow). The bridge normalises all three — that is `§3.3`'s
`ToolInvoker` + envelope, and it is the only place the difference is visible.

### 2.2 One shared loop plus four hand-rolled ones

`AgentLoop` (`agent/loop/AgentLoop.java`) drives `AgentStrategy` implementations and owns
`LLM → tool → LLM`. It is **the only place** with tool-call audit plumbing
(`AgentRunContext.auditToolCallConsumer`, drained at `AgentLoop.java:182-203`).

The PR-workflow agents do **not** use it. `E2eAgentRunner`, `UnitTestAgentRunner`,
`ReadmeSyncAgentRunner` and `I18nCoverageAgentRunner` are four near-identical chat-and-dispatch
loops that call `toolExecutor.execute(name, args, toolContext)` directly
(`ReadmeSyncAgentRunner.java:127,147` and siblings). **None of them has any tool-call audit
plumbing today.**

### 2.3 Authorization lives in three different places

| Family | Gate | Where |
|---|---|---|
| built-in | per-bot whitelist `Set<String> allowedBuiltinTools` | `AgentToolRouter.enforceWhitelist`, `AgentToolRouter.java:97-116` |
| MCP | per-bot selected-tool set → filtered `McpToolCatalog` | `McpToolSelectionService` → `McpToolCatalog.filterByQualifiedNames` |
| PR-workflow | **hardcoded per agent** — `Set.of("doc-write","doc-delete")` (`ReadmeSyncAgent.java:34`), `Set.of("unit-test-write")` (`UnitTestAuthorAgent.java:35`), `Set.of("i18n-write","i18n-delete")` (`I18nCoverageAgent.java:35`), `TestRunnerAgent.java:45`, plus `TestAuthorAgent` | the agent class itself |

Tool **advertisement** is centralised: `ToolCatalog.nativeDescriptors(Role, McpToolCatalog,
Set<String>)` (`ToolCatalog.java:439`), called from nine sites — the four `*Strategy` classes plus
`TestAuthorAgent:58`, `TestRunnerAgent:67`, `UnitTestAuthorAgent:75`, `ReadmeSyncAgent:72`,
`I18nCoverageAgent:77`.

### 2.4 What already exists for isolation

`util/ProcessSupport.java` is present and pure Java: `scrubEnvironment(ProcessBuilder)`,
`scrubEnvironmentForGit`, `run(pb, timeout, unit, maxOutputBytes)`,
`runInNewProcessGroup(...)`, `waitFor(...)`, returning
`CommandResult(finished, exitCode, output)` with bounded output draining.
`ToolExecutionService.executeCommand` (`ToolExecutionService.java:1370`) is the reference consumer:
no shell, argv dispatch, scrubbed env, timeout, `destroyForcibly`.

`doc/development-archive/sandbox-approach.md` is the recorded (still **Proposed**, i.e. not
implemented) design for hardening LLM-chosen commands. Its ADR-1 rejects the Docker sandbox
(§2.1: mounting `/var/run/docker.sock` hands the app user root on the host) and its ADR-2 fixes the
shape: **no shell anywhere, Java builds the argv prefix, timeout enforcement stays in Java only.**
Its ADR-3 fixes the filesystem posture: same uid + strict file permissions as the baseline, with a
dedicated uid as documented opt-in — not a hard boundary. This plan follows those decisions rather
than inventing a second isolation posture. Note: despite PR #322 being an ancestor of HEAD, no
`SandboxedCommandExecutor` and no `agent.sandbox.*` properties exist in this tree — `ProcessSupport`
is the whole of the available machinery.

### 2.5 What does **not** exist (naming traps)

- There is no `ToolContext` type, no `ResolvedTool`, no `ResolvedToolSet`, no `McpClientRegistry`,
  no `AgentToolResolver`, and no `ExecutionEnvelope`.
- **`ToolExecutionService` is taken** — it is the built-in CLI executor (`agent/validation/`),
  *not* a common dispatcher. The feature request's proposed "extract a common
  `ToolExecutionService`" is therefore **not** implemented under that name (§3.2, ADR-3). Before
  introducing any new SPI type, grep the simple name
  (`search_files pattern="ResolvedTool"`) — the repo already carries two unrelated
  `RoutingDecision`-style near-collisions.

---

## 3. Design

### 3.1 The one rule

> **Python knows tools, not infrastructure.**

Python may name `cat`, `rg`, `mcp:github:search_issues`, `doc-write`. It may not name a bean, an MCP
server URL, a transport, a credential, a repository id, or a workflow run id. Every tool name it can
see was resolved and authorised by Java for *this* execution.

### 3.2 Reconciliation: what deliberately does **not** change

`doc/development-archive/workflow-tool-execution-spi-plan.md` records an explicit instruction:
collapse the three duplicated PR-workflow executors behind one SPI, but **do not** fold them into
`ToolExecutionService` / `AgentToolRouter`, because the PR-workflow tools drive
workflow-specific persistence (`PrTestCase` / `UnitTestCase` upserts, the in-memory change set on
`ReadmeSyncToolContext`), each enforces its own scope guard (`UnitTestPathGuard`, `DocPathGuard`),
and execution has always been the workflow's own concern while `ToolCatalog.Role.PR_WORKFLOW`
governs advertisement only.

That decision stands. `execute-code` therefore does **not** become a god-service. Instead:

- The **four dispatchers stay exactly where they are**, guards and persistence untouched.
- A thin, read-only **resolution layer** is introduced that *delegates*: each family contributes
  `ToolInvoker` adapters bound to its own executor and its own typed context. Nothing moves.
- The `execute-code` entry point is one new `AGENT_CONTROL` tool kind, dispatched from the two
  existing per-surface chokepoints (§3.4).

This is the single largest deviation from the feature request's prose, and it is deliberate: the
request's diagram ("both converge at `ToolExecutionService`") would delete the SPI separation that
was decided on purpose. Convergence happens at **resolution and audit**, not at execution.

### 3.3 Components

New package `org.remus.giteabot.agent.codeexecution` (no `repository`/`gitea`/`github`/`gitlab`/
`bitbucket` dependency, so the `ai..` ArchUnit rule is not in play; the `agent → prworkflow`
direction already exists — `ToolCallContext` imports `prworkflow.agentreview.DiffSummary`).

```java
/** A tool as seen by Python: identity + schema + provenance. Never infrastructure. */
public record ResolvedTool(String name,
                           String description,
                           JsonNode inputSchema,
                           ToolSource source,
                           String sourceId,        // MCP server alias, else null
                           String nativeToolName) { }

public enum ToolSource { BUILTIN, MCP, PR_WORKFLOW }

/** Executes an already-authorised tool. Implemented per family; never throws. */
@FunctionalInterface
public interface ToolInvoker {
    ToolInvocationResult invoke(JsonNode arguments);
}

/**
 * Normalised envelope: the one shape Python sees, regardless of family.
 * {@code exitCode} and {@code error} are nullable and preserved, so collapsing
 * ToolResult(success, exitCode, output, error), the MCP ToolResult and the
 * PR-workflow "OK: …"/"ERROR: …" string onto one shape loses nothing.
 */
public record ToolInvocationResult(boolean success, Integer exitCode,
                                  String output, String error) { }

public final class ResolvedToolSet {
    ResolvedToolSet(List<ResolvedTool> tools, Map<String, ToolInvoker> invokers);
    List<ResolvedTool> list();                       // tools.list()
    Optional<ResolvedTool> find(String name);        // tools.describe()
    ResolvedToolSet without(String... names);         // recursion / agent-control exclusion
    ToolInvocationResult invoke(String name, JsonNode args) throws ToolNotAllowedException;
}

/** Everything Java-side that one execution needs. Never handed to Python. */
public record CodeExecutionScope(String owner, String repo, Path workspaceDir,
                                Long runId, Long botId,
                                ResolvedToolSet tools,
                                ToolCallObserver observer,   // always installed (§3.9)
                                CodeExecutionLimits limits) { }

/** Where nested-call observability goes. Always logging + metrics (§3.9). */
public interface ToolCallObserver {
    void onNestedToolCall(String name, ToolSource source, String sourceId,
                          boolean success, long durationMs);
}

public interface PythonExecutionService {
    String execute(String code, CodeExecutionScope scope);
}

/** Builds the per-surface read-only tool set. One factory per family. */
public final class AgentToolResolver { /* forAgentLoop / forE2e / forUnitTest / forReadmeSync / forI18n */ }

@Component public class ExecuteCodeTool { /* the tool itself */ }

/** Reads JSON lines from the control socket, dispatches, writes JSON back. */
final class PythonToolBridge { }

/** Protocol records. */
record ToolBridgeRequest(String type, String id, String name, JsonNode arguments) { }
record ToolBridgeResponse(String type, String id, JsonNode result, ErrorEnvelope error) { }
```

The families implement `ToolInvoker` locally, so no cross-family hub class appears:

- `agent/tools/BuiltinToolInvokers` → wraps `AgentToolRouter`/`ToolExecutionService`, unwrapping
  `arguments.get("args")` to the positional `List<String>` those tools declare.
- `mcp/McpToolInvokers` → wraps `McpOrchestrationService.executeTool(cfg, catalog, qualifiedName, args)`.
- Each PR-workflow executor gains a small adapter to `ToolInvoker` that converts the JSON node to the
  `Map<String,Object>` its `execute(String, Map, C)` signature wants — enabled by the SPI interface
  extraction in §10 phase 0.

### 3.4 Delivery: how each of the nine surfaces advertises and dispatches `execute-code`

**Advertisement.** A new `ToolCatalog` entry (one line per the house register, behavioural contract
in the prompt):

```java
entry("execute-code", ToolKind.AGENT_CONTROL,
        EnumSet.of(Role.CODING, Role.WRITER, Role.PR_WORKFLOW),
        "Run a short Python program to process tool results deterministically (loop, filter, "
                + "sort, parse, aggregate, repeat tool calls) and print one compact result. "
                + "Prefer it when several steps need no further reasoning.",
        objectSchema(prop("code", "string", "Python source to execute."), required("code")))
```

A new kind — `ToolKind.AGENT_CONTROL` — rather than reusing `CONTEXT`, because reusing `CONTEXT`
would (a) inject `execute-code` into `ToolCatalog.contextToolNames()`, which
`ToolExecutionService.executeContextTool` validates against *and* `AgentToolRouter.executeWriter`
prints as its "Available tools" error text, and (b) route it into
`ToolExecutionService.executeContextTool`'s switch — the built-in CLI executor, the wrong layer to
own the code-execution entry point.

Fixed costs of the new kind (the compiler only catches exhaustive switches; these are not
exhaustive):

- `ToolCatalog.isSilent` — returns silent for everything but `VALIDATION`/`UNKNOWN`, so
  `AGENT_CONTROL` is silent with **no** change; assert it in a test rather than assume.
- `ToolCatalog.bucketOf` — needs a `case AGENT_CONTROL -> DisplayBucket.CONTEXT` arm (its outer
  switch has `default -> throw new IllegalStateException`).
- `ToolCatalog` needs an `agentControlToolNames()` accessor, and
  `systemsettings/BuiltinToolRegistry.builtinTools()` needs one `addAll(byName,
  toolCatalog.agentControlToolNames(), ToolKind.AGENT_CONTROL, Role.CODING)` line. **Without this
  the tool is registered but never appears in the admin tool-configuration UI and cannot be put on
  any bot's whitelist** — the same silent-no-op class as a `RepositoryApiClient` default method.
- `tool.execute-code.description=` in **all seven** `messages*.properties` (36 `tool.*` keys exist
  today, so this is an established surface).

Advertisement then reaches every surface automatically for the four `AgentLoop` strategies (they pass
the bot whitelist into `nativeDescriptors`). The five PR-workflow agents need `"execute-code"` added
to each of their hardcoded `ALLOWED_TOOLS` sets (§2.3) — five one-line edits.

**Dispatch — `AgentLoop` side (one site).** `AgentToolRouter.execute` gains one check before the
existing file → MCP → context → validation order:

```java
if (catalog.kindOf(tool) == ToolKind.AGENT_CONTROL) {
    return executeCodeTool.run(buildScope(context));   // ResolvedToolSet = built-in + MCP
}
```

`AgentToolRouter` already holds everything the scope needs (whitelist, MCP configuration + catalog,
`RepositoryApiClient`), and `ToolCallContext` supplies `owner`/`repo`/`workspaceDir`/`diffSummary`.
All four strategies funnel through this one method — the native path converts `ChatTurn.toolCalls()`
to `ImplementationPlan.ToolRequest` first (`CodingAgentStrategy.java:204-206`) and the legacy path
builds them directly, then both call `toolExecutor`-equivalent dispatch at
`CodingAgentStrategy.java:607-615`.

**Dispatch — PR-workflow side (one decorator).** The four runners each loop on
`toolExecutor.execute(name, args, ctx)`. Extract the SPI interface from
`workflow-tool-execution-spi-plan.md` §1 (introduce `PrWorkflowToolExecutor<C>`, rename the E2E
concrete class to `E2eToolExecutor`) — **interface and rename only, not the runner merge** — and add
one decorator:

```java
final class CodeExecutionToolExecutor<C> implements PrWorkflowToolExecutor<C> {
    public String execute(String toolName, Map<String,Object> args, C ctx) {
        if (!"execute-code".equals(toolName)) return delegate.execute(toolName, args, ctx);
        return executeCodeTool.run(scopeFrom(delegate, ctx, readOnlyViewOf(ALLOWED_TOOLS)));
    }
}
```

Each of the five agents then constructs the decorator once around its executor. One implementation,
zero duplicated logic, and the SPI plan's own four-runner merge remains independent (it can land
before or after, in either order).

*Cheaper alternative if the maintainer wants no prerequisite:* a five-line `execute-code` branch in
each of the four runners. Costs four copies of the same interception; rejected for that reason.

### 3.5 The bridge: transport, protocol, lifecycle

**Transport.** A per-execution **AF_UNIX stream socket** (`StandardProtocolFamily.UNIX` /
`UnixDomainSocketAddress`, Java 16+, so fine on Java 21) bound at
`<execution-temp-dir>/bridge.sock`, created with owner-only permissions inside the 0700 temp dir.
The path is passed to `python3` via an env var. No public port, no loopback exposure, nothing to
authenticate beyond filesystem permissions. Rejected: dedicated inherited fds (needs a Python-side
bootstrap to reconstruct the socket — more machinery for no gain); loopback TCP (a real port and a
per-execution secret to defend it) — kept as the documented fallback.

**stdout is not the control channel.** `stdout` carries the program's final output and nothing else;
the socket carries protocol frames. Mixing them would let generated Python accidentally or
deliberately print something that looks like a protocol message. This is a hard requirement of the
feature, and it is why the socket is not optional.

**Protocol** (newline-delimited JSON, one request per line, one response per line):

```
→ {"type":"tool_call","id":"42","name":"mcp:github:search_issues","arguments":{"query":"x"}}
← {"type":"tool_result","id":"42","result":{"issues":[]}}
← {"type":"tool_error","id":"42","error":{"code":"TOOL_NOT_ALLOWED","message":"…"}}
```

**Lifecycle and the deadlock trap.** Java must serve the socket *while* `python3` runs and
simultaneously drain stdout/stderr. A single-threaded implementation that drains stdout only after
the socket conversation completes deadlocks the moment the program prints more than the pipe buffer
(~64 KiB) before making a tool call. Design: one virtual thread drains stdout+stderr into a bounded
buffer, a second accepts and serves connections. V1 is sequential (one connection, one outstanding
request) — the Python helper blocks on the response, so no request multiplexing is needed.

Teardown: `close()` on the socket, then `destroyForcibly()` on the process group (per
`sandbox-approach.md` ADR-2), then bounded wait, then delete the temp dir. Tool-call budget
exhaustion and timeout both terminate through the same path.

### 3.6 The Python API and the bootstrap

Two classpath resources, materialised into the execution temp dir and put on `PYTHONPATH`:

`ai_git_bot.py` — the runtime module, exposing `tools.list()`, `tools.describe(name)`,
`tools.call(name, arguments)`, and a `ToolError` exception raised on a `tool_error` envelope. It
opens the socket from the env var, writes one JSON line per call, blocks for the reply, and decodes
the result into Python objects.

`bootstrap.py` — applied *before* user code, in this order:

1. `resource.setrlimit(RLIMIT_AS, (max_memory, max_memory))` — the memory bound. Unprivileged, and
   inherited across `fork`/`exec`, so it also bounds any child the program spawns.
2. `resource.setrlimit(RLIMIT_CPU, (cpu_seconds, cpu_seconds))` — a CPU bound that survives a
   JVM-side timeout failing to fire.
3. `resource.setrlimit(RLIMIT_FSIZE, (max_file_size, max_file_size))` and `RLIMIT_NPROC`.
4. An import guard (`sys.meta_path` finder) that refuses `socket`, `ssl`, `http`, `urllib`,
   `ftplib`, `smtplib`, `asyncio`, `ctypes`. **Defence-in-depth only** — `ctypes` is exactly the
   kind of bypass this cannot stop; the container is the boundary (ADR-1).
5. Confine `sys.path` to the temp dir (no accidental import of app classes) and cap
   `sys.setrecursionlimit`.

All values arrive as env vars from Java, so limits have one owner:
`AgentConfigProperties.CodeExecutionConfig`.

The generated-wrapper convenience (`from ai_git_bot_tools import github_search_issues`) is a
**follow-up, not V1** — the feature request itself sequences it after the generic `tools.call()`
bridge works, and `tools.describe()` already gives the schema on demand. V1 also does not inject
every tool schema up front: `tools.list()` returns names + one-line descriptions, and
`tools.describe(name)` returns the full `inputSchema`.

### 3.7 Result isolation

The nested call path is bridge → `ResolvedToolSet.invoke` → the owning family's executor → raw
result → Python. It never touches `AgentLoop`, `AgentStep`, `PendingMessage`, `AiMessage`, or a
`StepDecision.ToolCallResult`, so a nested result **cannot** become a conversation message — the
isolation is structural, not a filtering rule. Nested calls are still authorised, logged, measured,
and audited (§3.9).

Only the final stdout (truncated to `max-output-size`) becomes the single `execute-code` tool
result. Returned as `new ToolResult(true, 0, finalOutput, "")` so it renders through the same
`formatForAi()` shape as every sibling tool; a non-zero Python exit returns
`ToolResult(false, exitCode, stdout, stderrTail)`.

Tool-result size: each nested result is capped (`max-nested-result-chars`) before it crosses the
bridge, so one 10 MiB `cat` cannot be smuggled into the LLM's context through a `print()` in the
program's last line.

Nested results cross the bridge as the full envelope (`success`, `exitCode`, `output`, `error`),
so a built-in tool's exit code is preserved rather than folded into prose — a program can branch
on a validation tool's exit code and still read the error text.

### 3.8 Authorization, recursion, and agent-control exclusion

- `ResolvedToolSet.find(name)` is the only entry point; a name absent from the set raises
  `ToolNotAllowedException` → a `tool_error` envelope → a Python `ToolError`. A tool that exists
  globally but is not enabled for this bot is simply **not in the set**, so it is unreachable
  rather than rejected-on-execution.
- There is no API that takes a server URL, a server alias, or a transport: `tools.call(name, args)`
  is the whole surface.
- `ResolvedToolSet.without(...)` is applied at construction, not at call time:
  - `execute-code` itself (recursion — required).
  - `branch-switcher`. Non-obvious and worth recording: it is classified `CONTEXT` and reads as
    read-only, but it mutates git state and the agent's own bookkeeping depends on it happening
    through `AgentRunContext.setBaseBranch` in the strategy. Letting a Python program switch
    branches mid-execution desynchronises the strategy's `baseBranch` from the checkout, and the
    commit/diff steps then operate on the wrong ref.
  - `pr-test-run` and `preview-status` — they shell out to test frameworks and probe a deployment;
    excluded by the read-only policy below, not by name.
- **V1 exposes read-only tools only** (the policy the exclusions above come from). Built-in:
  `CONTEXT` + `REPOSITORY`. PR-workflow: the read-only subset only, so
  `doc-write`/`doc-delete`/`unit-test-write`/`i18n-*`/`pr-test-write` are **not** in the set in V1.
- **MCP is the one family whose read/write nature cannot be derived from its metadata.** An MCP
  `inputSchema` says nothing about whether a call mutates a remote system, and this repo carries
  no per-MCP-tool capability flag. Resolution that keeps the boundary: the bot's existing
  selection (`McpToolSelectionService` → the filtered `McpToolCatalog`) stays the gate, and the
  resolved set is additionally filtered by `agent.code-execution.mcp-deny-tools` — a list of
  qualified names or `<server>:` prefixes, **empty by default**. An operator who has selected a
  mutating MCP tool can therefore exclude it from Python without losing it for direct LLM use.
  Real capability metadata is a V2 item (§9 row 11, §11).
  One honest note in favour of enabling writes later: because the decorator delegates to the *same*
  executor with the *same* context, nested writes would already be recorded correctly
  (`ReadmeSyncToolContext.recordCreated`, the `UnitTestCase` upsert). Enabling them is a policy and
  audit decision, not a structural one — which is a good sign the delegation design is right.

### 3.9 Audit and metrics

- **Every nested call is observed on every surface.** The bridge invokes the scope's
  `ToolCallObserver` once per call with name, source, `sourceId`, success and duration. Two
  implementations:
  - `ObservabilityToolCallObserver` — **always installed**. Emits the structured log line and the
    metric of the next bullet. This is what makes a nested call visible on a surface that has no
    audit sink at all.
  - `RecordToolCallObserver` — wraps the surface's existing
    `Consumer<AgentRunContext.ToolCallRecord>` where one exists (the `AgentLoop` strategies and
    agent-review; see `PrWorkflowOrchestrator.java:128`).
- `AgentRunContext.ToolCallRecord` gains an `origin` component (`Origin.LLM` /
  `Origin.EXECUTE_CODE`). Cost check, per the repo's own rule about shared records: exactly **one**
  construction site in main (`AgentLoop.java:193`) plus test consumers, so a new component is
  cheap here. `origin` is what separates a direct call from a nested one in the same stream.
- **Residual, named:** the four PR-workflow runners (`E2e`, `UnitTest`, `ReadmeSync`, `I18n`) have
  no `ToolCallRecord` consumer at all, so a nested call on those surfaces yields a log line and a
  metric but no record. Installing a consumer there is a change in *their* boundary, not this
  feature's, and lands as a follow-up (§9 row 18, §11).
- **Metrics.** `AgentMetrics` is a single concrete class (`agent/shared/AgentMetrics.java`:35) with
  `recordToolCall(String provider)` at :108, so an overload needs no interface ripple. Do **not**
  re-purpose `recordToolCall`; add a dedicated counter
  `agent.code_execution.tool_calls{source,server,status}` plus
  `agent.code_execution.duration_seconds` and `...tool_call_limit_exceeded_total`, published through
  `AgentMetricsHolder` (the holder is a no-op outside Spring, which is what keeps the unit tests
  simple).

### 3.10 Config and limits

New nested `AgentConfigProperties.CodeExecutionConfig` (prefix `agent.code-execution.*`,
`src/main/resources/application.properties`, never duplicated into
`application-docker.properties` — the base file is always loaded and the profile file layers on
top):

```properties
# Opt-in: the tool is registered but a bot only sees it once it is on the bot's tool whitelist.
agent.code-execution.enabled=${AGENT_CODE_EXECUTION_ENABLED:false}
agent.code-execution.timeout-seconds=${AGENT_CODE_EXECUTION_TIMEOUT_SECONDS:60}
agent.code-execution.max-tool-calls=${AGENT_CODE_EXECUTION_MAX_TOOL_CALLS:50}
agent.code-execution.max-output-size=${AGENT_CODE_EXECUTION_MAX_OUTPUT_SIZE:100KB}
agent.code-execution.max-nested-result-chars=${AGENT_CODE_EXECUTION_MAX_NESTED_RESULT_CHARS:50000}
agent.code-execution.max-code-size=${AGENT_CODE_EXECUTION_MAX_CODE_SIZE:100KB}
agent.code-execution.max-memory-mb=${AGENT_CODE_EXECUTION_MAX_MEMORY_MB:256}
agent.code-execution.cpu-seconds=${AGENT_CODE_EXECUTION_CPU_SECONDS:120}
agent.code-execution.python-binary=${AGENT_CODE_EXECUTION_PYTHON:python3}
# Layer 2 (sandbox-approach.md §3.2) pass-through; false keeps the Layer 1 posture.
agent.code-execution.hardened=${AGENT_CODE_EXECUTION_HARDENED:false}
agent.code-execution.network=${AGENT_CODE_EXECUTION_NETWORK:none}
```

Limits live in the config class and are read into `CodeExecutionLimits` — **not** as `DEFAULT_`
constants on the record (the repo's single-source-of-truth rule for records vs config). Test call
sites use inline literals matching the shipped defaults.

### 3.11 Prompt guidance

LLM-facing text stays English and lives in the classpath prompt templates — not in a DB
`system_prompts` row, which would cost a migration and get clobbered by the next blunt `UPDATE`.
Add the guidance to all three native templates
(`src/main/resources/prompts/native/{issue-agent,writer-agent,e2e-agent}-tool-protocol.md`):

> Use `execute-code` when several deterministic operations or tool calls can run without additional
> semantic reasoning between them — loops, filtering, sorting, aggregation, parsing, calculations,
> repeated reads. Intermediate tool results stay outside your context, so this is cheaper than
> separate tool calls. Call `tools.call(name, arguments)`; use `tools.list()` / `tools.describe()`
> to discover the tools available to you. Do not use it when the next step needs interpretation
> that only you can make.

`ToolCatalog`'s own description stays one line (the house register for that map).

---

## 4. Architecture Decision Records

### ADR-1: Python runs as a restricted local subprocess, not in a container

**Status:** Accepted
**Context** Generated Python is untrusted code. `sandbox-approach.md` already settled this question
for LLM-chosen build commands (same threat model: an LLM-influenced program with a workspace to
read and an app full of credentials to leak) and rejected the Docker route for a concrete reason —
`docker-compose.sandbox.yml` mounting `/var/run/docker.sock` puts root on the host in the hands of
the very user the sandbox defends against (§2.1 of that doc).

**Options Considered**
1. **Docker-in-Docker / sidecar with `--network none`** — ✅ strongest isolation, real cgroups;
   ❌ reintroduces the socket mount and its §2.1 escalation, plus an image, a shared-path
   requirement, orphan reaping, availability probing and a silent fallback policy.
2. **bubblewrap (`--unshare-net`)** — ✅ real network isolation, no daemon; ❌ an extra binary with
   its own flag surface, and unprivileged userns is AppArmor-restricted on Ubuntu ≥ 23.10.
3. **Restricted local subprocess (Layer 1) + optional util-linux prefix (Layer 2)** — ✅ zero
   operational change, reuses `ProcessSupport`, matches the recorded decision, single execution
   model; ❌ no hard network or filesystem boundary in the default posture.

**Decision** Option 3. `python3 -I`, scrubbed environment (`ProcessSupport.scrubEnvironment`), 0700
per-execution temp cwd, in-process `setrlimit` for memory/CPU/file-size, process-group kill and a
Java-side timeout, bounded output. Layer 2 (`unshare --net`, `prlimit`, `setpriv`) is a
config-driven **argv prefix** built by Java per `sandbox-approach.md` ADR-2, opt-in and
fail-closed — not a second implementation.

**Consequences**
- Import-level network blocking is defence-in-depth only and must be documented as such.
- V1 does **not** provide an OS-level filesystem boundary. See §9 row 16 for how this deviates from
  the feature request's acceptance criterion 28, stated plainly rather than papered over.
- No new Dockerfile content beyond a test-only dependency.
- If Layer 2 ever lands, this feature inherits it by config, with no code change.

### ADR-2: The control channel is a per-execution AF_UNIX socket; stdout carries only output

**Status:** Accepted
**Context** Python must call back into Java. Multiplexing protocol frames and user output on stdout
lets generated Python emit something that parses as a protocol message.
**Options Considered** 1. **AF_UNIX socket in the exec temp dir** — ✅ no port, no secret,
filesystem permissions are the gate, trivially per-execution; ❌ a serving thread must run while
stdout is drained (deadlock hazard, §3.5). 2. **Inherited file descriptors** — ✅ no path;
❌ requires a Python bootstrap to reconstruct the socket and a nontrivial fd protocol. 3. **Loopback
TCP + per-execution secret** — ✅ works everywhere; ❌ a bound port, an issued secret, and one more
auth path.
**Decision** Option 1.
**Consequences** stdout is reserved for the final result; the serving and draining threads must be
distinct; the socket path never leaves the temp dir.

### ADR-3: No convergence into `ToolExecutionService`; delegate per family instead

**Status:** Accepted (supersedes the feature request's prose on this point)
**Context** The request proposes several families converging on a new common `ToolExecutionService`.
That name is occupied by the built-in CLI executor, and
`workflow-tool-execution-spi-plan.md` explicitly forbids folding the PR-workflow executors into it
(workflow-specific persistence, per-workflow scope guards, typed contexts, package-independence
rules).
**Options Considered** 1. **Extract a real common execution layer, move all four families under
it** — ✅ one code path; ❌ deletes a decision made deliberately, drags `PrTestCase`/`UnitTestCase`
persistence and two path guards into a generic executor, and touches every tool call site and test.
2. **Delegate: a read-only resolution layer of `ToolInvoker` adapters, one per family, with the
dispatchers untouched** — ✅ satisfies "one authoritative layer" at resolution/authorization/audit
without moving execution; a fraction of the diff; ❌ two arg shapes must be normalised at the edge.
3. **No abstraction: one bridge per family** — ✅ smallest single step; ❌ four copies of the
protocol, four places to fix a bug.
**Decision** Option 2.
**Consequences** `ResolvedTool` uses the same tool *names*, not the same arg shape; the bridge's
`ToolInvoker` is where positional-vs-structured is reconciled. The SPI plan stays valid and
independent.

### ADR-4: MCP names stay `mcp:<server>:<tool>`; results are normalised, not renamed

**Status:** Accepted
**Context** The request proposes dotted `github.search_issues`. Renaming to dotted would change the
names in `ToolCatalog.nativeDescriptors` (LLM-visible) and the persisted `mcp_selected_tools` rows
(bot configuration), i.e. a wire change plus a data migration, for a purely cosmetic gain.
**Options Considered** 1. **Keep the canonical name; expose it to Python verbatim** — ✅ no migration,
one name everywhere, `McpToolCatalog.find()` already keys on it; ❌ the Python names look slightly
noisier. 2. **Dotted alias only inside Python** — ✅ prettier; ❌ a second name for the same tool, so
`tools.call("github.search_issues")` and `tools.call("mcp:github:search_issues")` both exist and
the model will use both, doubling the audit dimensions for no benefit. 3. **Rename everywhere** — ❌
data migration + LLM-visible change, for cosmetics.
**Decision** Option 1. Normalisation happens on the **result**, not the name: the bridge collapses
`ToolResult` and the PR-workflow `"OK:"/"ERROR:"` strings into one
`{success, output}` envelope, which is the request's "MCP result normalization" requirement
generalised to every family.

---

## 5. Behaviour by scenario

| # | Scenario | Observable behaviour |
|---|---|---|
| 1 | Program calls 3 read-only tools, prints 40 chars | Conversation grows by **one** tool message (the 40 chars). The three intermediates exist only in Python. |
| 2 | Program prints nothing, exits 0 | `ToolResult(true, 0, "(no output)", "")` — the model is told, not left guessing. |
| 3 | Program raises `NameError` | Syntax/runtime failure returned compactly (`Python execution failed: NameError …`, last frames only) so the model can correct the program. |
| 4 | `tools.call("does_not_exist")` | Closed-set rejection before execution → `tool_error` → Python `ToolError`; catchable. |
| 5 | `tools.call("execute-code")` | Rejected — the tool is not in the resolved set (`without("execute-code")`). |
| 6 | `tools.call("branch-switcher")` | Rejected — excluded from the set (§3.8). |
| 7 | Tool the bot has not enabled | Not in the set → same as #4. Never reaches an executor. |
| 8 | MCP tool selected for the bot | Routed bridge → `McpOrchestrationService` → the configured client. Server, transport and credentials never leave Java. |
| 9 | MCP server unreachable | `tool_error` with a normalised code; the program may handle it or let it propagate. |
| 10 | `while True: pass` | Process group killed at `timeout-seconds`; `ToolResult(false, …, "timed out after Ns")`. |
| 11 | `for i in range(100000): read(...)` | Terminated at `max-tool-calls`; the program sees a `ToolError`, then the run ends cleanly. |
| 12 | Program allocates unbounded memory | `MemoryError` from the inherited `RLIMIT_AS`; reported as a normal Python failure. |
| 13 | Program prints 10 MiB | Output truncated at `max-output-size` with an explicit marker. |
| 14 | A nested tool returns 10 MiB | Truncated at `max-nested-result-chars` before crossing the bridge. |
| 15 | `python3` missing / not executable | Fail closed at startup of the execution: clear `ToolResult` error naming the binary. Not a crash, not a silent no-op. |
| 16 | `agent.code-execution.enabled=false` | The tool is not advertised at all; nothing else changes. |
| 17 | Program calls `open()` on a path outside the temp dir | **Succeeds** in the default posture (same uid, no filesystem boundary — ADR-1). Mitigations are deployment-side: `chmod 700` on the config/credential dirs (`sandbox-approach.md` ADR-3), and the Layer 2 `setpriv --reuid` prefix, under which the read raises `PermissionError`. Recorded as a limit, never as a guard. |
| 18 | Program writes a file inside the temp dir | Succeeds and is discarded with the temp dir at teardown. Nothing it writes reaches the checkout — the read-only *tool* policy leaves it no tool that could. |
| 19 | `hardened=true` but `unshare`/`setpriv` unavailable | Fail closed before execution with an error naming the missing binary (`sandbox-approach.md` §3.2 policy). Never a silent degrade to Layer 1. |

---

## 6. Tests

Test-style rule for this repo: read the neighbouring test class first — the dominant style in
`agent/*` and `prworkflow/*` is **flat `@Test` methods, no `@Nested`** — and match it.

Unit (no real process):

- `ResolvedToolSetTest` — resolution, `without(...)`, unknown-name rejection, namespaced MCP names
  round-trip. Covers the whitelist and MCP-server-isolation acceptance criteria.
- `PythonToolBridgeTest` — protocol round-trip over a real AF_UNIX socket against a stub invoker;
  `tool_error` envelope; tool-call budget stop; per-result size cap.
- `ProcessPythonExecutionServiceTest` — argv assertion in the `sandbox-approach.md` ADR-2 style
  (`python3 -I`, no shell metacharacters, prefix order when hardened, scrubbed env, rlimit env
  pass-through), timeout kill, output truncation, missing-binary failure.
- `ExecuteCodeToolCatalogTest` — `ToolKind.AGENT_CONTROL`, `isSilent()` true, `bucketOf`, and that
  `BuiltinToolRegistry.builtinTools()` contains `execute-code` (guards the silent-no-op trap).

Integration (real `python3`; skip gracefully when absent, the repo's established convention for
process-dependent tests):

- nested built-in call (`cat`) returns a normalised envelope and the *outer* result is only what was
  printed;
- recursion blocked; infinite loop terminated; 100 000-iteration loop stops at the budget;
- **result isolation**: assert the conversation history contains exactly one tool message and that
  it does not contain the intermediate payload;
- PR-workflow routing: a mocked `ReadmeSyncToolExecutor` receives `doc-write` with a
  `Map<String,Object>` (proves the arg-shape adaptation, not just the happy path).

MCP routing: mocked `McpOrchestrationService`, asserting `executeTool(cfg, catalog,
"mcp:github:search_issues", args)` — i.e. the *native* name and the right config, not just "was
called".

Agent-level: one test in which a **single** `execute-code` LLM tool invocation performs ≥ 2 nested
tool calls (the feature request's acceptance criterion 48), asserted on the agent `Result` plus the
captured history length.

- `PythonSandboxConfinementTest` — the limits are actually in force in the child (allocating past
  the memory cap raises `MemoryError`; a CPU burner dies on the signal); the import guard refuses
  `socket`/`ssl`/`urllib`; `sys.path` holds only the temp dir; and, when `hardened=true` and the
  binaries exist, an outbound connection fails under `unshare --net`. Skip gracefully when
  unavailable, and assert the built **argv prefix** in that case.
- `McpDenyListTest` — a qualified name, and a whole `<server>:` prefix, in
  `agent.code-execution.mcp-deny-tools` each remove the tool from the resolved set (and therefore
  from `tools.list()`).
- Envelope fidelity — a built-in tool returning a non-zero exit code surfaces `exitCode` in the
  envelope, not only in prose.

Config: an `AgentConfigPropertiesTest` addition for `CodeExecutionConfig` defaults.

`ArchitectureTest` must stay green — a cheap run, but it is the gate for the new package placement.

---

## 7. Non-goals

- Unrestricted Python execution on the host; exposing the Spring context, git credentials, AI-provider
  credentials, MCP credentials or MCP server URLs to Python.
- Python connecting to MCP servers, or to any network destination, directly.
- A second, independent tool-execution framework (ADR-3).
- Recursive `execute-code`.
- Generated per-tool Python wrappers (`ai_git_bot_tools`) — follow-up (§3.6).
- Parallel nested tool calls — V1 is sequential; the protocol shape leaves room.
- Python as a replacement for semantic reasoning. The prompt says so explicitly.

---

## 8. Trade-offs and honest limits

Every mismatch with the feature request is registered in §9; this section records what the chosen
boundaries cost in operation.

- **Filesystem confinement does not exist in V1.** Acceptance criterion 28 ("Python has no
  unrestricted application filesystem access") is **not met as an OS guarantee**. Python runs as
  the same uid (`appuser`) with a temp cwd, so it can `open()` any path that uid can read. What *is*
  met: no path is handed to Python, and there is no API to escape the tool layer — Python cannot
  reach the application through the *tool* surface. The boundary is the container, per
  `sandbox-approach.md` ADR-3. Two mitigations are available without leaving the chosen boundary
  and are operational, not code: `chmod 700` on the config/credential dirs, and `hardened=true`
  with `setpriv --reuid` to a sandbox uid, under which the read fails. Both go in
  `doc/DEPLOYMENT.md`; the plan must say this, not imply a boundary (§9 row 16).
- **Network is not blocked as a hard guarantee** in Layer 1 (criterion 31): the import guard is
  bypassable (`ctypes` is the obvious hole). The real answer is the Layer 2 `unshare --net`
  prefix, which satisfies the criterion when `hardened=true` and fails closed when the binary is
  unavailable. Scenarios #17–#19 in §5 are the visible consequences (§9 row 17).
- **`RLIMIT_AS` bounds address space, not RSS** — a program may hit `MemoryError` slightly before it
  hits the configured figure. Coarse by design, same class of caveat as `prlimit` in
  `sandbox-approach.md`.
- **Cost of the descriptor.** `execute-code`'s schema is small, but every advertised tool adds
  fixed per-round prompt overhead (native rounds resend the system prompt and one schema per tool).
  The plan does not add a second descriptor to compensate.
- **Nested calls are observed everywhere but recorded only where a sink exists.** Log line and
  metric on all six surfaces; a forwarded `ToolCallRecord` on the ones that already had a consumer
  (§3.9, §9 row 18). Stated rather than glossed.
- **`execute-code` is snake_case** while every sibling tool is kebab-case. Kept deliberately: the
  request specifies it, it is LLM-facing, and it is a primitive models already associate with
  in-loop code execution. A one-line deviation from the house register, recorded here so it is not
  "corrected" at review by accident.
- **`ToolResult` vs `"OK:"/"ERROR:"` normalisation is lossless by construction** — the envelope
  carries `exitCode` and `error` (§3.3, §3.7), so no built-in outcome is degraded to prose. This
  replaces an earlier draft's folded-into-`output` approach, which would have lost the exit code.
- **Read-only by default widens nothing but also buys little for write workflows.** A program that
  wants to write the README still cannot in V1 (§3.8) — enable-able later, policy permitting, and
  the MCP half of that policy is operator-tunable now via `mcp-deny-tools` in the other direction
  (removing tools rather than adding them).

---

## 9. Deviation register — accepted mismatches with the feature request

Everything in the feature request that is **not** listed below is implemented as written. Each row
is a decision, not an oversight: the last column names the boundary that was preserved, which is
why the deviation was preferred to the alternative. Acceptance criteria 1–23, 25, 26, 27, 29,
32–48 are met as written.

| # | The request says | This plan does | Boundary preserved — why |
|---|---|---|---|
| 1 | Both tool paths "converge at `ToolExecutionService`"; extract a common execution service beneath the normal LLM tool path | Convergence happens at **resolution, authorisation and audit**; the four dispatchers keep their own execution | `ToolExecutionService` is the built-in CLI executor (name occupied), and `workflow-tool-execution-spi-plan.md` forbids folding the PR-workflow executors into it (ADR-3) |
| 2 | `enum ToolSource { BUILTIN, MCP }` | adds `PR_WORKFLOW` | a third tool family exists here and is in scope (settled decision 3) |
| 3 | `ResolvedTool(..., inputSchema, source, sourceId, nativeToolName)` | the same six fields, but the `ToolInvoker` is **not** a record component — it lives in a parallel map in `ResolvedToolSet` | keeps the descriptor comparable, loggable and unit-testable instead of carrying a lambda |
| 4 | `PythonExecutionContext(UUID workflowRunId, Long botId, Long repositoryId, String traceId, ResolvedToolSet tools)` | `CodeExecutionScope(String owner, String repo, Path workspaceDir, Long runId, Long botId, ResolvedToolSet tools, ToolCallObserver observer, CodeExecutionLimits limits)` | the tools take `owner`/`repo`/`workspaceDir`, not a repository id; run ids are numeric, not UUIDs; there is no trace id in this codebase (`AiAuditContext`'s session id is the equivalent and is already thread-local); limits keep a single owner |
| 5 | `PythonExecutionService.execute(String code, ToolContext toolContext)` | `execute(String code, CodeExecutionScope scope)` | no `ToolContext` type exists in this codebase |
| 6 | Bridge socket at `/run/ai-git-bot/exec/<execution-id>.sock` | socket inside the per-execution 0700 temp dir | `/run` is not writable by `appuser` in this image; the temp dir already exists and is torn down with the execution (ADR-2) |
| 7 | Exclude only `execute-code` from the nested set | also excludes `branch-switcher` | it is classified `CONTEXT` but mutates git state and would desync `AgentRunContext.baseBranch` (§3.8) |
| 8 | Also exclude `delegate_task`, `change_model`, `change_workflow` | nothing to exclude — those three tools do not exist in this codebase | if one is added later it belongs in the same `without(...)` set |
| 9 | Name MCP tools `<mcp-server-alias>.<native-tool-name>` (dotted) | stays `mcp:<server>:<tool>` verbatim | the dotted form would be LLM-visible *and* is what the persisted `mcp_selected_tools` rows key on, so renaming is a wire change plus a data migration for cosmetics (ADR-4) |
| 10 | Java-side `McpClientRegistry`, `McpClient.callTool`, `McpToolResultNormalizer` | the real components are `McpOrchestrationService`, `McpToolCatalog`/`McpToolDefinition`, `McpSyncClient`; transport, headers and credentials stay inside them | the request's isolation goals are met by construction — Python never receives a URL, a header or a token |
| 11 | Expose "read MCP-backed data / search MCP-backed data" in V1 | exposes the tools the bot already selected, minus a new **default-empty** `agent.code-execution.mcp-deny-tools` list | MCP `inputSchema` carries no read/write capability, so a genuine read-only split cannot be derived; the existing selection remains the gate, and operators can deny by name or `<server>:` prefix (§3.8) |
| 12 | Generated bindings `from ai_git_bot_tools import github_search_issues` | follow-up, not V1 | the request itself sequences them after the generic `tools.call()` works; `tools.describe()` covers schema discovery |
| 13 | Top-level `execute-code:` config block | `agent.code-execution.*` via `AgentConfigProperties.CodeExecutionConfig`, plus `cpu-seconds`, `max-nested-result-chars`, `python-binary`, `hardened`, `network`, `mcp-deny-tools` | agent behaviour has one config home in this repo, and the caps rule says so |
| 14 | `return_result(...)` left open alongside stdout | stdout only, as the request's own default | fewer mechanisms to teach the model; `tools.list()` / `tools.describe()` are the only other additions |
| 15 | Sandbox left open: local process / Docker / gVisor / Firecracker | restricted local subprocess, with the util-linux Layer 2 prefix as opt-in | ADR-1: a `docker.sock` mount hands the app user root on the host (`sandbox-approach.md` §2.1) |
| 16 | Criterion 28 & 30 — Python has no unrestricted application filesystem access, no Spring-bean access | **not met as an OS guarantee** in the default posture; met under `hardened=true` (`setpriv --reuid`), mitigated by `chmod 700` on the config/credential dirs | same uid is the Layer 1 posture (ADR-1; `sandbox-approach.md` ADR-3). What *is* met: no path is handed to Python and no tool bypasses the layer |
| 17 | Criterion 31 — no unrestricted network access | not met in Layer 1 (the import guard is bypassable); met under `hardened=true` via `unshare --net`, which fails closed when unavailable | ADR-1; otherwise the container remains the boundary |
| 18 | Criterion 24 — nested calls still produce audit events | structured audit log + metric on **every** surface; a forwarded `ToolCallRecord` only where a sink already exists | the four PR-workflow runners have no audit sink at all; installing one changes their boundary, so it is a follow-up (§3.9) |
| 19 | Criterion 29 — Python has no direct access to application environment variables | met, with a note: the child environment is scrubbed by `ProcessSupport.scrubEnvironment`; the only variables added are the bridge socket path and the numeric limits | no application, database or provider secret is present in the child environment at any point |
| 20 | "Should nested tool calls count against the same workflow-level quota?" | per-execution budget only; the outer `execute-code` call counts once as a normal tool call | otherwise the deterministic-batching win is taxed away (§11) |
| 21 | "Should independent nested tool calls support parallel execution?" | sequential; the protocol shape allows a later change | keeps one outstanding request and makes the audit order deterministic |
| 22 | Prompt guidance taught to the model | classpath templates (`prompts/native/*-tool-protocol.md`), LLM-facing English | a DB `system_prompts` change costs a migration and is clobbered by the next blunt `UPDATE` |
| 23 | Tool name `execute_code` | renamed to `execute-code` | the house register is kebab-case (`write-file`, `get-issue`, `pr-diff`) and the maintainer chose it at phase-2 start — recorded because it is the one place the plan departs from the request's identifier |

## 10. Implementation order

Phase 0 (prerequisite, if the decorator route in §3.4 is taken — skippable, see the alternative):

1. `prworkflow/tools/PrWorkflowToolExecutor<C>` interface; rename the E2E concrete class to
   `E2eToolExecutor`; update its injectors and mocks. Compile.
   (`workflow-tool-execution-spi-plan.md` steps 1–3. Do **not** merge the runners here.)

Phase 1 — resolution layer (no process, fully unit-testable):

2. `ResolvedTool`, `ToolSource`, `ToolInvocationResult`, `ToolInvoker`, `ResolvedToolSet`,
   `ToolNotAllowedException`.
3. `BuiltinToolInvokers` + `McpToolInvokers`; `AgentToolResolver.forAgentLoop(...)`;
   `ToolCallObserver` + `ObservabilityToolCallObserver` + `RecordToolCallObserver`. Tests.

Phase 2 — the tool surface:

4. `ToolKind.AGENT_CONTROL`; the `ToolCatalog` entry + `agentControlToolNames()`;
   `isSilent`/`bucketOf` arms; `BuiltinToolRegistry` line; `tool.execute-code.description` in all
   seven bundles. Tests, including the registry guard.
5. `execute-code` added to the five PR-workflow `ALLOWED_TOOLS` sets.
6. `AgentToolRouter` dispatch branch. Strategy-level test.

Phase 3 — the execution engine:

7. `CodeExecutionConfig` in `AgentConfigProperties` + `application.properties`, including
   `mcp-deny-tools` (all three `application*.properties` reviewed; base file only).
8. `bootstrap.py` + `ai_git_bot.py` as resources.
9. `PythonToolBridge` + protocol records. Socket-level unit test.
10. `ProcessPythonExecutionService` (temp dir, argv build, stdout drain thread, serving thread,
    timeout, teardown). Test for argv + timeout + truncation.
11. `ExecuteCodeTool` + `CodeExecutionScope`; wire into `AgentToolRouter`.

Phase 4 — the remaining surfaces:

12. `CodeExecutionToolExecutor<C>` decorator; wire into `TestAuthorAgent`, `TestRunnerAgent`,
    `UnitTestAuthorAgent`, `ReadmeSyncAgent`, `I18nCoverageAgent` (+ their per-family invoker
    adapters). One test per family asserting the arg-shape adaptation.

Phase 5 — observability and prompts:

13. `origin` on `AgentRunContext.ToolCallRecord`; nested-call audit on the `AgentLoop` path.
14. `AgentMetrics` counters + `AgentMetricsHolder` setters.
15. Prompt guidance in the three native templates.

Phase 6 — verification and docs:

16. Agent-level acceptance test (one invocation, ≥ 2 nested calls), plus the sandbox-confinement,
    MCP deny-list and envelope-fidelity tests from §6.
17. Full suite in the background, then `ArchitectureTest`.
18. Docs: a short section in the doc that owns agent behaviour (`doc/AGENT.md` /
    `doc/CODING_AGENT.md`), the `CHANGELOG.md` "Unreleased → Added" entry, and the `llms.txt` /
    `llms-full.txt` source-area map. **Not** a `README.md` bullet and **not** the four translated
    READMEs — this is a capability, not a workflow, and a modest opt-in one; if the maintainer wants
    the bullet it is an explicit ask, and then all four READMEs are reverted-or-updated as a set.
    Env vars go in the table that already holds sibling variables (`doc/DEPLOYMENT.md` for the
    sandbox posture, `doc/USER_GUIDE.md` for the limits). `doc/DEPLOYMENT.md` additionally gains
    the two operational requirements §9 rows 16/17 depend on: `chmod 700` on the config/credential
    dirs, and the fail-closed behaviour when `hardened=true` is requested but `unshare`/`setpriv`
    are absent.

**Commit grouping** for hand-over (the maintainer commits; nothing is staged on his behalf):
(1) resolution layer + tool-kind/tool-catalog surface + config; (2) the execution engine + bridge +
resources; (3) per-surface wiring + decorator; (4) observability + prompts; (5) docs.

---

### Phase 1-2 as implemented (deltas from this plan)

The plan is the design of record, so where the code deliberately differs from it, it says so here.
None of these changes the architecture, and §9 remains the register of deviations from the feature
request.

| This plan said | Built as | Why |
|---|---|---|
| `BuiltinToolInvokers` in `agent/tools/`, `McpToolInvokers` in `mcp/` | both package-private in `agent.codeexecution` | keeps every new class in one package; no existing package gains one |
| `forAgentLoop(…, Mode, Role, …)` | mode derived from `Role` inside the factory | a CODING-role/WRITER-mode mismatch is now unconstructable |
| nine-parameter factory | a `McpToolAccess` record carries the three MCP collaborators | `references/coding-style.md`: an aggregate over a long parameter list |
| `RecordToolCallObserver` in phase 1 | deferred to the audit phase | it needs `ToolCallRecord.origin`, which does not exist yet |
| the observer "emits the metric" | logs only (warn on failure, debug on success) | `AgentMetrics` has no counter that can carry tool name and source, and feeding a tool name to `recordToolCall(provider)` would be a lie |
| the invoker "unwraps `arguments.get(\"args\")`" | `ToolArguments` holds the extracted JSON-to-positional mapping | one source of truth for the property order, shared with `CodingAgentStrategy.toRequest` |

Two sequencing corrections made while implementing, both to avoid shipping a hazard:

- **The five PR-workflow `ALLOWED_TOOLS` sets moved out of phase 2.** Those sets are hardcoded, so
  adding `execute-code` there advertises it to those agents' models *unconditionally*, while the
  decorator that would handle a call does not exist yet. The four `AgentLoop` surfaces are safe to
  register now because the bot whitelist is the gate and no `bot_tool_selections` row is seeded, so
  nothing is advertised until an operator opts in. The five edits land with the decorator.
- **`pr-diff` is `ToolKind.CONTEXT` with roles `CODING` + `WRITER`, not `REPOSITORY`.** The read-only
  filter (`CONTEXT` + `REPOSITORY`) therefore lets it through, but it reads
  `ToolCallContext.diffSummary`, which is null outside a PR-review context — so on a coding-issue run
  a program would see `pr-diff` as available and every call to it would error. Still open: drop it
  from the resolved set when `diffSummary` is null.
- **A tool that cannot be executed is never advertised to Python.** `ResolvedToolSet.of` keeps only
  tools that have an invoker, so a bot with an MCP selection but no usable MCP configuration does not
  expose those tools — stronger than this plan stated, and asserted in `AgentToolResolverTest`.

## 11. Open at implementation time

- **Default-configuration seed or not?** Recommendation: **no `bot_tool_selections` seed row**.
  `execute-code` is not repository exploration, it changes model behaviour, and it should be an
  explicit operator opt-in — reachable through the admin tool-configuration UI (hence the
  `BuiltinToolRegistry` line in §3.4) plus `agent.code-execution.enabled`. If the maintainer prefers
  it on by default, the seed is an idempotent `INSERT … WHERE c.default_entry = TRUE AND NOT EXISTS
  (...)` in **both** `db/migration/h2/` and `db/migration/postgresql/`, plus the `data.sql` test
  seed. Either way, **take the version from `git log --all --name-only --pretty=format: --
  src/main/resources/db/migration/`**: the head is V56, but V52 and V53 are each claimed by multiple
  unmerged branches, so a bare "next number" read from this checkout is not safe.
- Does a nested tool call count against the workflow-level tool quota
  (`agent.validation.max-tool-executions`) or only against `max-tool-calls`? Recommendation: only
  the per-execution budget, with the outer `execute-code` call itself counting once as a normal tool
  call — otherwise the deterministic-batching win is taxed away.
- Should the read-only policy (§3.8) be a config list rather than code, so an operator can opt into
  nested writes per deployment? Recommendation: config list in `CodeExecutionConfig`, defaulting to
  the read-only set, evaluated once at resolution time.
- `tools.list()` payload: names + one-line descriptions, or names only? Recommendation: names +
  short descriptions (it is one call, and the description is what lets the model pick without a
  `describe()` round-trip per candidate).
- Whether the four PR-workflow runners get a real `ToolCallRecord` consumer in the same change or
  in a follow-up; §3.9 assumes follow-up, so on those four surfaces a nested call produces a log
  line and a metric but no record. Say so when reporting rather than implying parity (§9 row 18).
- Whether to derive per-MCP-tool read/write capability metadata (a selection flag, or a convention
  over MCP tool annotations) so `mcp-deny-tools` becomes unnecessary. V1 keeps the operator list
  because an MCP `inputSchema` carries no such signal (§9 row 11).
