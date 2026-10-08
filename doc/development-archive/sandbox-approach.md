# Sandboxed Command Execution — Docker-free Approach

> **Goal:** Evaluate whether the security goal of branch
> `CaeruleusAqua/scholle/sandboxed-command-execution-hardening` (run LLM-chosen build/test
> commands so they cannot harm the host or leak secrets) can be reached **without Docker** —
> lightweight, mostly in pure Java — and define the replacement design.
>
> **Relationship to `sandbox-review.md`:** that document keeps Docker and simplifies the
> execution model (rw bind mount, no shell script). This document goes one step further and
> questions the container boundary itself. If the outcome here were "keep Docker", the
> simplifications in `sandbox-review.md` still apply.

---

## 1. As-built design on the branch (summary)

21 files, +1726/−99. Core pieces:

- `agent/validation/SandboxedCommandExecutor.java` (439 lines, new) — runs commands either
  directly (scrubbed env, `setsid` process group) or in an ephemeral `docker run --rm`
  container (network/memory/cpu/pids limits, dropped caps, read-only rootfs, tmpfs workspace).
- `util/ProcessSupport.java` (280 lines, new) — env allowlist scrubbing, bounded async output
  drain, descendant tracking/termination, UTF-8-safe decode. **Pure Java, no Docker
  dependency.**
- `WORKSPACE_SETUP_COMMAND` — a 28-line shell script compiled into the executor as a Java text
  block. It does workspace staging (`cp -R /source/. /ws/`), conditional
  `npm install -D "$AI_GIT_BOT_INSTALL_PACKAGE"`, timeout wrapping, output capture, and a
  base64 artifact-export framing protocol (`__AI_GIT_BOT_ARTIFACTS__` marker). Five unrelated
  concerns in one shell script owned by a generic executor — the separation-of-concerns
  violation is real, and the E2E framework knowledge (Playwright/Cypress package names and
  report directories) leaking into `agent.validation` is the sharpest edge of it.
- `WorkspaceProcessRunner` was rewritten around the artifact return channel (budget inflation
  by `ARTIFACT_CHANNEL_RESERVE_BYTES`, marker parsing, base64 restore into the host
  workspace); `PrTestWorkspaceManager` defers `npm install` because the tmpfs copy discards
  `node_modules`; `PrWorkflowToolExecutor` injects `INSTALL_PACKAGE_ENV`.
- Ops surface: `docker.io` in the app image, `docker-compose.sandbox.yml` mounting
  `/var/run/docker.sock` into the app container, 9 new `AGENT_SANDBOX_*` variables, orphan
  container reaping, Docker availability probing, fallback-to-direct policy.

Full component-by-component analysis: `sandbox-review.md` §1–2 (findings F1–F7 hold).

---

## 2. Threat model — what are we actually protecting?

The commands at risk are build/test invocations (`mvn`, `gradle`, `npm`, `pytest`, `k6`, …)
whose **executable** comes from a server-side allowlist (`ToolCatalog.validationToolNames()`)
but whose **arguments and workspace content** are influenced by the LLM and by untrusted PR
code (package-manager lifecycle scripts, test fixtures, Makefiles). Concrete threats:

| # | Threat | Branch's answer | Docker actually needed? |
|---|--------|-----------------|-------------------------|
| T1 | Exfiltration of app **environment** secrets (DB creds, Gitea/AI API keys are env vars) | `ProcessSupport.scrubEnvironment` allowlist | **No — pure Java, works today, no container involved** |
| T2 | Reading secrets from the **filesystem** (git credential-store files, app config, other workspaces) | Container user + mount masking | Partly — see §3.2; develop already keeps git credentials **outside** the workspace (`WorkspaceService.credentialConfigArgs`) and mounts nothing sensitive |
| T3 | **Network** abuse (exfiltration channel, malicious downloads) | `--network none` | Yes, for a hard guarantee — but `unshare --net` gives the same kernel primitive without a daemon (§3.3) |
| T4 | **Resource** exhaustion (fork bomb, memory, disk fill, runaway CPU) | `--memory/--cpus/--pids-limit`, bounded tmpfs | Timeout + process-group kill (pure Java, already in `ProcessSupport`) covers runaway processes; `prlimit` covers the rest cheaply (§3.3) |
| T5 | **Workspace tampering** (build modifies/deletes files) | tmpfs copy — workspace is immutable | Not needed: workspaces are disposable (agent clones are deleted after the run, E2E scaffolds are throwaway). Default direct mode offers zero protection here and nobody missed it |
| T6 | **Persistence** after the command (background daemons, planted git hooks, modified `.git/config`) | Container death + `.git/config` overlay | Process-group kill (done) + develop's existing hardening: host git commands already force `core.hooksPath=<empty dir>`, `credential.helper=`, `GIT_CONFIG_NOSYSTEM`, a blank global config (`WorkspaceService.runCommand`) |

Two observations fall out of this table:

1. **The highest-value protection (T1) is already pure Java and lives in `ProcessSupport`.**
   The branch's real security win over develop is the env scrubbing — that code never touches
   Docker.
2. **The Docker mount is protecting the least-valuable asset** (a disposable workspace, T5)
   while the genuinely valuable guards for T2/T6 either already exist on develop or don't
   strictly need a container.

### 2.1 The Docker approach worsens the app's own attack surface

`docker-compose.sandbox.yml` mounts `/var/run/docker.sock` into the application container.
**Anyone who can run a command as the app user — precisely the scenario this branch defends
against — can start a privileged container and own the host.** A sandbox escape stops being
the worst case; the sandbox *plumbing* becomes the worst case. Add the operational price:
a sibling-container path-visibility requirement (`GITEABOT_WORKSPACES_DIR` must resolve
identically for app and daemon), a toolchain image to keep in sync, orphan reaping,
availability probing, and a fallback policy that silently degrades to non-isolated execution.

That is a lot of machinery to defend against `npm test` in a throwaway directory.

---

## 3. The Docker-free approach

### 3.1 Layer 1 — pure Java (keep, already built)

Everything in `ProcessSupport` plus the surrounding discipline, none of which needs a
container:

- **No shell, ever.** Commands execute as direct argv (`List<String>` → `ProcessBuilder`).
  There is no `sh -c` anywhere in the replacement design, so there is no shell-injection
  surface and no place for a `WORKSPACE_SETUP_COMMAND`-style script to exist at all.
- **Tool allowlist.** `ToolCatalog.validationToolNames()` already restricts the executable.
  Arguments stay free-form (the LLM needs `-Dtest=…`), which is acceptable because argv
  without a shell cannot escape into command substitution.
- **Environment scrubbing.** `ProcessSupport.scrubEnvironment` replaces the child env with a
  minimal toolchain allowlist (T1). Git transport gets the stricter
  `scrubEnvironmentForGit` variant.
- **Lifetime control.** `setsid` process group + descendant tracking + timeout +
  `destroyForcibly` + bounded output drain (T4 runaway, T6 daemons).
- **Git hygiene.** `WorkspaceService` on develop already neutralizes repo-controlled git
  behavior for host-side commands (`core.hooksPath`, blank global config, disabled credential
  helper, `GIT_CONFIG_NOSYSTEM`) and stores clone credentials **outside** the workspace (T2,
  T6). The branch's `.git/config` overlay was load-bearing only because its base still
  embedded the token in the clone URL; on current develop that is gone.

This layer alone is a strict improvement over develop and requires **zero** operational
changes. It is the proposed default.

### 3.2 Layer 2 — optional kernel-level confinement via util-linux, driven from Java

For deployments that want T2/T3/T4 as hard guarantees, the same kernel primitives Docker uses
(namespaces, rlimits, uid separation) are reachable through three single-purpose binaries
that ship with util-linux / are one package away. The Java executor composes them as an
**argv prefix** — still no shell:

```
setpriv --reuid=<uid> --regid=<gid> --clear-groups \   # T2: dedicated unprivileged user
unshare --user --map-current-user --net \             # T3: network namespace, unprivileged
prlimit --cpu=<s> --as=<bytes> --nproc=<n> --fsize=<bytes> \  # T4: rlimits
setsid <tool argv…>                                    # existing process-group discipline
```

Compared to Docker this keeps every security flag the branch actually values and deletes the
daemon, the socket mount, the image, the shared-path requirement, the orphan reaping, the
probing, the fallback policy, the tmpfs copy, and — because the real workspace is used
directly — the entire `WORKSPACE_SETUP_COMMAND` / `INSTALL_PACKAGE_ENV` / base64-artifact
apparatus. `node_modules` and build caches stay warm; E2E artifacts land where the consumers
already read them.

Honest limitations (to be stated in DEPLOYMENT.md):

- **uid separation needs privilege.** `setpriv --reuid` to a *different* uid requires the JVM
  to run as root (or with CAP_SETUID). In the stock deployment the app runs as uid 1000, so
  Layer 2 either (a) runs commands as the same uid — filesystem protection of app files then
  rests on ordinary permissions (chmod 700 on config/credential dirs, which is good practice
  anyway) — or (b) is enabled only where the operator provisions it. `unshare --net`, by
  contrast, works unprivileged via a user namespace on mainstream kernels
  (note: Ubuntu ≥ 23.10 restricts unprivileged user namespaces via AppArmor; probe at
  startup and fail closed or degrade per config).
- **rlimits are coarser than cgroups.** `prlimit --nproc` counts per real uid (effective fork
  bomb protection needs a dedicated uid, i.e. (b) above); `--as` is address space, not RSS.
  Acceptable for a build command under a timeout; not a multi-tenant guarantee.
- **No disk bound.** Same as develop's direct mode; the tmpfs disk bound was a Docker-only
  feature protecting a disposable asset (T5).

Layer 2 is **opt-in** (`agent.sandbox.hardened=true`), probed once at startup, fail-closed
when enabled-but-unavailable unless `fallback-to-direct` is explicitly set — same policy
shape as the branch, minus the Docker daemon.

### 3.3 What is deliberately NOT proposed

- **Java SecurityManager** — deprecated by JEP 411, disabled by default since JDK 18, removed
  from the platform's future; and it governs JVM-internal code, not subprocesses. Dead end.
- **In-process execution of builds** (running Maven/npm logic inside the bot JVM) — destroys
  the process boundary that makes output capture, timeouts and killing sane. Dead end.
- **bubblewrap** — the right middle ground if Layer 2 ever proves too weak (full mount
  namespace, no daemon), but it is an extra binary with its own flag surface; documented as
  an upgrade path, not built.
- **Keeping Docker as an alternative backend** — rejected. Two backends means every future
  hardening is implemented, reviewed and tested twice, and the sock mount keeps the §2.1
  problem alive. Operators who genuinely need container-grade isolation for *untrusted PR
  code with network access* should run the whole bot in a throwaway environment, which is a
  deployment decision, not a code feature.

---

## ADR-1: Replace the Docker sandbox with layered Java + util-linux hardening

**Status:** Proposed

**Context**
The branch adds ~1200 lines of main code plus operational machinery to run build/test
commands in ephemeral containers. Analysis (§2) shows the highest-value protections are
either already pure Java (`ProcessSupport`, env scrubbing) or already on develop (git
credential/hooks hardening), while the container adds a root-equivalent socket mount to the
app and protects mainly a disposable workspace.

**Options Considered**

1. **Keep Docker, simplified per `sandbox-review.md`** (rw bind mount, no setup script)
   - ✅ Pros: strongest per-command isolation; resource limits via cgroups; review doc's
     simplification removes the SoC violation.
   - ❌ Cons: docker.sock = root on the host for the app user (§2.1); daemon + image +
     shared-path ops burden; two execution models to maintain; fails closed when Docker is
     absent.
2. **Pure Java only (Layer 1)**
   - ✅ Pros: zero ops changes; smallest code; covers T1/T4-runaway/T6.
   - ❌ Cons: no network isolation (T3), no filesystem identity separation (T2) as a hard
     guarantee.
3. **Layer 1 + optional util-linux confinement (Layer 2)** (chosen)
   - ✅ Pros: covers T1–T4 and T6 with kernel primitives but no daemon, no socket, no image;
     single execution model (argv dispatch); the SoC-violating shell script and the artifact
     channel disappear *by construction*; opt-in per deployment; degrades to Layer 1.
   - ❌ Cons: weaker than cgroups (rlimits); uid separation needs provisioning; unprivileged
     userns restricted on some distros → startup probing required.

**Decision**
Choose **Option 3**.

**Consequences**
- `SandboxedCommandExecutor` loses all Docker code paths (~300 lines), keeps the `Result`
  contract, and gains a wrapper-prefix builder. Consider renaming to `HardenedCommandExecutor`
  — the class no longer sandboxes in the container sense.
- `Dockerfile` loses `docker.io`; `docker-compose.sandbox.yml` is deleted.
- `agent.sandbox.*` properties are re-shaped: `enabled` (Layer 1 always on when validation
  runs), `hardened`, `network`, `cpu-seconds`, `address-space-mb`, `nproc`, `max-file-size-mb`,
  `sandbox-uid`/`sandbox-gid` (optional), `fallback-to-direct`. Old `image`, `memory-mb`,
  `cpus`, `pids-limit`, `workspace-mb`, `docker-host` disappear.

---

## ADR-2: No shell anywhere — direct argv dispatch with a Java-built wrapper prefix

**Status:** Proposed

**Context**
`WORKSPACE_SETUP_COMMAND` exists only because the branch chose read-only mount + tmpfs copy.
Once commands run in the real workspace, nothing needs staging, framing or exporting, and
the only remaining "wrapper" is the confinement prefix (§3.2), which is a fixed list of
binaries with flags — expressible as Java data, not shell.

**Options Considered**

1. **Shell script moved into the image / a resource file** — ✅ removes the text block from
   Java; ❌ keeps a second language in the security boundary, keeps review-blind string
   concatenation of env vars.
2. **Java builds the argv prefix** (chosen) — ✅ type-checked, unit-testable, per-flag
   assertions; ❌ operator can't tweak the wrapper without a rebuild (acceptable: the wrapper
   is security-critical and should change only via review).

**Decision**
Choose **Option 2**. The executor appends the tool argv directly after the prefix. Timeout
enforcement stays in Java only (`ProcessSupport.waitFor` + process-group kill) — no
`timeout(1)` wrapper, no duplicated enforcer.

**Consequences**
- Unit tests assert the exact argv for each config combination (prefix order, numeric
  conversions, absence of shell metacharacters).
- `INSTALL_PACKAGE_ENV`, `EXPORT_ARTIFACTS_ENV`, `ARTIFACTS_MARKER`,
  `AI_GIT_BOT_TIMEOUT_SECONDS` are deleted; `PrWorkflowToolExecutor` stops injecting the
  install package; `PrTestWorkspaceManager` always runs scaffold-time `npm install`.

---

## ADR-3: Filesystem identity via file permissions / optional dedicated uid, not mount namespaces

**Status:** Proposed

**Context**
T2 (reading app secrets from disk) is the one threat where "same uid" direct execution is
weaker than a container. But develop already removed the crown jewels from the workspace
(git credentials live in a store file outside it, never in `.git/config`), and the remaining
sensitive files (application config, credential store, DB) are few and static.

**Options Considered**

1. **Dedicated sandbox uid via `setpriv`** — ✅ hard guarantee incl. per-uid `--nproc`;
   ❌ requires root/CAP_SETUID for the JVM, which the stock deployment doesn't have.
2. **Same uid + strict permissions** (chmod 700 on config/credential dirs) as the baseline,
   with Option 1 available as documented opt-in for operators who run hardened (chosen) —
   ✅ zero-ops default, upgrade path exists; ❌ baseline is hygiene, not a boundary.
3. **Mount-namespace isolation (bubblewrap / unshare -m)** — ✅ real boundary without Docker;
   ❌ extra binary and a flag surface of its own; deferred (§3.3).

**Decision**
Choose **Option 2**, document the permission requirements in DEPLOYMENT.md, and accept
Option 1 configs through `sandbox-uid`/`sandbox-gid` for hardened deployments.

**Consequences**
- The `.git/config` overlay machinery (`createGitConfigOverlay`, temp files, overlay mounts)
  is deleted — there is nothing to overlay without a mount namespace.
- DEPLOYMENT.md gains a "hardening without Docker" section: file permissions, optional
  sandbox user provisioning, userns restrictions on Ubuntu.

**Implementation note — options 1 and 2 both went in, for `execute-code` only.** The tool now
takes an optional `sandbox-user`/`sandbox-group` (`agent.code-execution.*`): when set, the
interpreter is prefixed with `setpriv --reuid=… --regid=… --clear-groups` and the throwaway
workspace is opened to the group both accounts share, so the option-1 identity separation is
real where it matters most. A non-root JVM cannot switch uid, so the privilege is a
file-capability grant instead of root: a `setpriv` copy carrying `CAP_SETUID`/`CAP_SETGID`,
executable by the service user only (the sandbox account is kept out of that group, so it cannot
exec it back), plus `CAP_KILL` for the JVM, which can no longer signal a child running as
another uid. The `setpriv`/`unshare --net`/`prlimit` prefix of §3.2 for *validation* commands is
still unbuilt; those continue to run as the service user.

---

## 4. Implementation Plan

### Components to create / modify

- [ ] `util/ProcessSupport.java` — keep as-is (already pure Java); possibly add an
      `availabilityProbe(String binary)` helper used once at startup
- [ ] `agent/validation/SandboxedCommandExecutor.java` — delete Docker machinery
      (`buildDockerCommand`, `probeDocker`, `killContainer`, `reapOrphanedSandboxes`,
      `createGitConfigOverlay`, `WORKSPACE_SETUP_COMMAND`, artifact/install env constants);
      add argv-prefix builder for Layer 2 (`setpriv`/`unshare`/`prlimit`) gated by config and
      a one-time startup probe; keep `Result` and the scrubbed direct path
- [ ] `config/AgentConfigProperties.SandboxConfig` — re-shape properties per ADR-1
      consequences (drop image/memory-mb/cpus/pids-limit/workspace-mb/docker-host; add
      hardened/network/cpu-seconds/address-space-mb/nproc/max-file-size-mb/sandbox-uid/gid)
- [ ] `prworkflow/e2e/tools/WorkspaceProcessRunner.java` — revert to a thin wrapper around
      the executor (delete `restoreArtifacts`, reserve arithmetic, exported-dir list); output
      budget back to caller value
- [ ] `prworkflow/e2e/tools/PrWorkflowToolExecutor.java` — delete `INSTALL_PACKAGE_ENV`
      injection; revert `DEFAULT_RUN_OUTPUT_BYTES` to `256 * 1024`
- [ ] `prworkflow/e2e/workspace/PrTestWorkspaceManager.java` — delete the `isSandboxed()`
      npm-install deferral; rework the `isNetworkIsolated()` guard to the new config
      (`hardened && network=none` still blocks preview-URL E2E)
- [ ] `Dockerfile` — drop `docker.io`; `docker-compose.sandbox.yml` — delete;
      `application.properties` — re-shape `agent.sandbox.*`
- [ ] Tests: rewrite `SandboxedCommandExecutorTest` around argv-prefix building and probe
      caching; delete protocol cases from `WorkspaceProcessRunnerTest` /
      `PrTestWorkspaceManagerSandboxTest`; adapt `ToolExecutionServiceTest`,
      `AgentConfigPropertiesTest`; integration tests for the hardened path must
      skip gracefully when `unshare`/`setpriv` are unavailable
- [ ] `doc/DEPLOYMENT.md` — replace the Docker sandbox section with the Layer 1/2 model and
      the hardening-without-Docker operator guide
- [ ] This file: flip ADR statuses after review

### Sequence

1. Re-shape `SandboxConfig` + `application.properties` (ADR-1 consequences).
2. Rewrite `SandboxedCommandExecutor`: scrubbed direct path (unchanged semantics) + prefix
   builder + startup probe + fail-closed/fallback policy (ADR-1, ADR-2).
3. Simplify the three consumers (`WorkspaceProcessRunner`, `PrWorkflowToolExecutor`,
   `PrTestWorkspaceManager`).
4. Delete Docker ops artifacts (`Dockerfile` change, compose override, overlay machinery).
5. Update tests; add argv assertions per config combination and an availability-skip
   integration test.
6. Docs: `DEPLOYMENT.md` rewrite; ADR status flips here.
7. Verify: `mvn test-compile surefire:test -Dtest='SandboxedCommandExecutorTest,WorkspaceProcessRunnerTest,PrTestWorkspaceManager*,PrWorkflowToolExecutorTest,ToolExecutionService*,AgentConfigPropertiesTest'`
   plus `ArchitectureTest` (package placement unchanged, but the run is cheap).

### Assumptions

- Workspaces are disposable; build-time tampering is accepted (parity with develop).
- Layer 1 (env scrubbing, no-shell argv, allowlist, lifetime control, git hygiene) is the
  default security posture and needs no operator action.
- Layer 2 is opt-in; where userns/uid separation is unavailable, the deployment degrades to
  Layer 1 + file permissions, and that trade-off is documented, not hidden behind a fallback
  flag default.
- Container-grade isolation for network-connected untrusted code is out of scope for the
  codebase and belongs to the deployment environment (ADR-1, §3.3).
