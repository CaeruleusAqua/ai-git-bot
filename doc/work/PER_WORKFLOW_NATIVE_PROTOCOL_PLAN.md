# Implementation Plan — per-workflow native tool protocol + usage hints on tool definitions

> Status: **IMPLEMENTED** · Branch: `feature/code-execution` · Scope: prompt assembly + `ToolCatalog`
>
> Related: [`../AGENT.md`](../AGENT.md), [`../TOOL_CALLING.md`](../TOOL_CALLING.md)

## 1. Problem

The `feature/code-execution` changeset renders a "Tool Selection Strategy" into the **native**
agent prompt from `ToolCatalog.USAGE_HINTS`, a standalone `Map<String, String>` keyed by tool
name. Two defects:

**A — the hints are detached from the tool definitions and miss the PR-workflow surface.**
`USAGE_HINTS` keeps strategy text about a tool away from the tool's own definition, so the text
can drift from the catalogue, and it is rendered over a fixed order
(`file → context → validation → writer-repository → agent-control`) built by
`SystemPromptAssembler.catalogueOrder`. That order never includes the `PR_WORKFLOW` tools, so for
every PR-workflow agent (whose whitelist contains only `PR_WORKFLOW` names) the rendered strategy
is empty — the hints do not follow the tools the bot/workflow actually has.

**B — the native protocol is not per workflow.** `PromptKind` has three values and five
PR-workflow agents share `E2E_AGENT` (e2e test author, e2e test runner, readme-sync,
i18n-coverage, unit-test author), while issue-triage and agentic-review share `WRITER_AGENT`.
Each agent therefore receives a tool protocol written for a different workflow.

## 2. Decision (ADR)

**Status:** Accepted

**Context.** The native protocol has two halves: the static *invocation rules* (how to call tools
at all) and the *tool-selection strategy* (when to reach for which tool). The rules are stable
per workflow; the strategy is a pure function of the tools that workflow's agent can call. Both
defects come from mixing those halves in shared, name-keyed stores.

**Options considered**

1. *Keep name-keyed stores, add more entries.* ❌ The strategy still cannot be derived from the
   tool set, and `PR_WORKFLOW` tools still need a second place to be registered.
2. *Generate the whole native block from the catalogue (no markdown).* ❌ Loses per-workflow
   invocation rules (the "never narrate a tool call" rules, read-only constraints, security
   notes) that are genuinely workflow-specific.
3. **Move the hint onto the tool definition and give every agent stage its own protocol file.**
   ✅ The hint travels with the tool, so strategy = the tools the agent can call, in catalogue
   order. The invocation rules stay hand-written per workflow.

**Decision.** Option 3.

* `ToolCatalog.Entry` gains a `usageHint` component. `ToolCatalog.usageHint(tool)` reads it from
  the definition; validation tools (config-driven, not `Entry`s) keep a small companion map.
* `SystemPromptAssembler` renders the strategy over the tools of the **kind's role**, filtered by
  the bot's whitelist — so it is empty for a workflow that has none of the hinted tools.
* `PromptKind` gets one constant per agent stage, each carrying its own `fileBase` and its
  `ToolCatalog.Role`, so `{fileBase}-tool-protocol.md` is loaded per workflow.
* Each per-workflow `.md` holds only the invocation rules + `{{TOOL_STRATEGY}}`. Persona and
  domain guidance stay in the `*PromptLibrary` classes and the operator-editable `SystemPrompt`
  (the existing two-layer split is preserved — these files are not operator-editable).

**Consequences**

* Adding a tool to the catalogue is again a one-place change: declare the entry with its hint and
  every workflow whose agent can call it picks the hint up automatically.
* A tool with no hint contributes nothing (there is no "must have a hint" rule), keeping the
  per-round prompt cost proportional to the selection.
* Nine protocol files instead of three; a missing file degrades to an empty protocol section and
  logs a warning, so a typo is caught by the new "every kind has a file" test rather than at runtime.

## 3. Components

- [x] `ToolCatalog` — `usageHint` on `Entry`; `usageHint(tool)` reads the definition; new
      `VALIDATION_USAGE_HINTS`; new `builtinToolNames(Role)`; drop `USAGE_HINTS`.
- [x] `SystemPromptAssembler` — `PromptKind` = one constant per agent stage (`fileBase`, `role`);
      strategy rendered per role in catalogue order; drop `catalogueOrder`.
- [x] `prompts/native/*-tool-protocol.md` — 9 files (3 existing, 1 renamed, 5 new).
- [x] Call sites — readme-sync, e2e author, e2e runner, i18n-coverage, unit-test author,
      issue-triage, agentic-review pick their own `PromptKind`.
- [x] Tests — `SystemPromptAssemblerTest`, `ToolCatalogTest`.

## 4. Tool → protocol file

| Agent stage | `PromptKind` | file | tools (role) |
|---|---|---|---|
| issue coding | `ISSUE_AGENT` | `issue-agent-tool-protocol.md` | file / context / validation (CODING) |
| issue writer | `WRITER_AGENT` | `writer-agent-tool-protocol.md` | context / repository (WRITER) |
| issue triage | `TRIAGE_AGENT` | `triage-agent-tool-protocol.md` | context / repository (WRITER) |
| e2e test author | `E2E_TEST_AUTHOR` | `e2e-test-author-tool-protocol.md` | `pr-test-write` |
| e2e test runner | `E2E_TEST_RUNNER` | `e2e-test-runner-tool-protocol.md` | `preview-url`, `preview-status`, `pr-test-run`, `attach-artifact` |
| readme-sync | `README_SYNC_AGENT` | `readme-sync-tool-protocol.md` | `doc-write`, `doc-delete` |
| i18n-coverage | `I18N_COVERAGE_AGENT` | `i18n-coverage-tool-protocol.md` | `i18n-write`, `i18n-delete` |
| unit-test author | `UNIT_TEST_AUTHOR_AGENT` | `unit-test-author-tool-protocol.md` | `unit-test-write` |
| agentic review | `AGENT_REVIEW_AGENT` | `agentic-review-tool-protocol.md` | context / repository (WRITER) |

## 5. Assumptions

* Legacy (JSON-envelope) mode keeps its three renderers; the new kinds map onto
  `renderIssueAgent` / `renderWriterAgent` / `renderE2eAgent` as before.
* The per-workflow `.md` files remain **non-editable**; operator-editable text stays in the
  `SystemPrompt` rows.
* `PR_WORKFLOW` tools stay out of the bot tool-selection UI — their whitelist is fixed per agent.
