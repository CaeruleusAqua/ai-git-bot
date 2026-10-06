# Run AI Code Reviews for the Cost of a $5 VPS — No Per-Seat SaaS Required

> A self-hosted AI workflow automation bot that reviews pull requests, generates
> tests, and keeps docs in sync — powered by a 7B model you already own, on
> hardware you already run.

**TL;DR:** AI code review SaaS pricing scales per developer, per month, and your
source code goes to someone else's cloud. The setup in this post runs the same
class of workflow entirely self-hosted: one Docker container, one Ollama
service, and a ~4.7 GB open-weight coder model. Total incremental cost: a small
VPS or a machine you already have. The project behind this post,
[AI-Git-Bot](https://github.com/tmseidel/ai-git-bot), has been
[self-pulled from Docker Hub over 16,000 times](https://hub.docker.com/r/tmseidel/ai-git-bot)
and is MIT licensed.

---

## The real cost of "per-seat AI"

By now most of us have the invoice: Copilot at $10–39/seat/month, CodeRabbit and
similar review SaaS at per-PR or per-seat tiers, enterprise tiers on top.
Multiply that by a 15-person team and you're at several thousand dollars a
month — before a single review is useful.

Two things about that model bother me:

1. **The price scales with headcount, not value.** A reviewer bot doesn't
   "need a seat." You're paying a per-developer tax for infrastructure.
2. **Your code leaves the building.** For teams in fintech, health, or
   government — and honestly for anyone with contractual data-residency
   requirements — sending diffs to a third-party SaaS is a non-starter, not a
   preference.

You don't have to accept either of those. Local open-weight models have been
genuinely good at code review for over a year now, and the missing piece was
never the model — it was the **workflow glue**: turning "a PR was opened" into
"reviewed, findings posted, tests generated, docs updated, automatically, on
every platform we use."

That's the gap AI-Git-Bot fills.

## What it actually does

AI-Git-Bot is a self-hosted bot that lives inside your Git platform — Gitea,
GitHub/GitHub Enterprise, GitLab, or Bitbucket Cloud — and reacts to events you
already emit:

| Workflow | Trigger | Result |
|---|---|---|
| PR review | PR opened / re-requested | Summary + inline findings on the diff |
| Interactive Q&A | `@bot` mention in a PR comment | Context-aware answer in-thread |
| Unit test generation | PR opened | Regression tests committed to the branch |
| E2E / Full-Stack QA | PR opened | Playwright suite run against a preview, results posted |
| Issue triage & routing | Issue opened/assigned | One assignee chosen, reason posted |
| Issue → Pull request | Issue assigned to the coding agent | Implementation PR opened |
| README / docs sync | PR opened | Docs updated to match code |
| i18n coverage | PR opened | Missing translations drafted across locale files |

No browser extension, no Slack bot to babysit, no new process. Developers just
see the bot's comments where they already look.

## The $5 stack

The whole thing is two containers.

```yaml
services:
  app:
    image: tmseidel/ai-git-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: giteabot
      DATABASE_PASSWORD: change-me
      APP_ENCRYPTION_KEY: your-secure-encryption-key-here
    depends_on:
      db:
        condition: service_healthy
    restart: unless-stopped

  db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: giteabot
      POSTGRES_USER: giteabot
      POSTGRES_PASSWORD: change-me
    restart: unless-stopped

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    restart: unless-stopped

  ollama-pull:
    image: ollama/ollama:latest
    entrypoint: ["sh", "-c", "sleep 5 && ollama pull qwen2.5-coder:7b"]
    environment:
      OLLAMA_HOST: http://ollama:11434
    depends_on:
      - ollama

volumes:
  ollama_data:
```

That's it. The image is published as a **multi-arch manifest** (`linux/amd64`
and `linux/arm64`), so the same compose file runs on an x86 VPS, an Apple
Silicon laptop, a Graviton instance, or a 64-bit Raspberry Pi — which matters a
lot if "low budget" includes "the hardware is a Pi in a closet."

Then point it at your model. In the web UI: **AI Integrations → New
Integration → provider: `ollama` → API URL: `http://ollama:11434` → model:
`qwen2.5-coder:7b`**. No API keys. No per-token billing. Nothing to export.

### Model choice and honest expectations

The project ships a ready-to-use compose for exactly this (`systemtest/`), and
the docs are direct about what local models can and can't do:

| Workload | 7B class | 14–32B class |
|---|---|---|
| **PR reviews** (natural-language output) | ✅ works well | ✅ |
| **Issue-based agents** (require strict JSON) | ❌ unreliable | ⚠️ 32B+ is the sweet spot |

So the honest, low-budget recipe is:

- **Reviews, Q&A, doc/i18n sync on a 7B model** — `qwen2.5-coder:7b` (~4.7 GB)
  or `codellama:7b` run comfortably in 8 GB of RAM, no GPU required, and
  reviews come back in a reasonable time on a small VPS.
- **If you later want the coding agent to open PRs**, graduate to
  `qwen2.5-coder:32b` or `deepseek-coder:33b` on a bigger box — or just use a
  cloud provider for that one workflow. The bot mixes providers per-bot, so
  you can run reviews on Ollama and agent work on Claude if you want.

That's the whole "low budget" pitch: **you pay for the workflow, not per seat,
and the marginal cost of adding a developer is zero.**

## Where this is actually useful

- **You run Gitea** and are tired of every AI dev tool being GitHub-only. This
  is arguably the best AI tooling for a self-hosted Gitea, including
  Gitea-compatible instances.
- **You can't send source to a SaaS.** Compliance, data residency, or plain
  "not a chance" — the bot, the model, the prompts, and the diffs all stay on
  your network. `APP_ENCRYPTION_KEY` gives you AES-256-GCM at rest for secrets.
- **You already own the hardware.** A 7B Q4/Q5 coder model fits in 8 GB of RAM.
  The Pi and the old workstation are both fine starting points.

## What it's not

- It's not a code *completion* assistant. Copilot still makes you type faster;
  this makes the boring-but-important work (review, regression tests, docs,
  triage) happen without someone deciding to do it.
- It's not a SaaS. There is no hosted tier; you run it, and that's the point.

## Try it

```bash
docker run -p 8080:8080 tmseidel/ai-git-bot:latest
```

Open `http://localhost:8080`, create your admin account, wire one AI
integration (Ollama works), one Git integration, one bot, and the webhook.
That's the whole setup — it's the path I use for the demo videos in the
[README](https://github.com/tmseidel/ai-git-bot).

- **Repo:** <https://github.com/tmseidel/ai-git-bot> (MIT)
- **Docker image:** <https://hub.docker.com/r/tmseidel/ai-git-bot>
- **Deploy guide:** [doc/DEPLOYMENT.md](https://github.com/tmseidel/ai-git-bot/blob/develop/doc/DEPLOYMENT.md)
- **Ollama guide:** [doc/OLLAMA.md](https://github.com/tmseidel/ai-git-bot/blob/develop/doc/OLLAMA.md)

If you get it running on a budget box (especially a Pi or an arm64 VPS), drop
a comment or open an issue — real-world hardware reports are the best way to
keep the docs honest.
