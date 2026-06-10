<p align="center">
  <img src="docs/mascot.jpg" width="180"/>
</p>

<h1 align="center">LZA 🫧 Pinkie Software Factory</h1>

<p align="center">
  <strong>"Your idea is the start of the production line." — Pinkie (粉圓)</strong><br>
  Turn ideas into reality, amplify developers — a <b>human-in-the-loop</b> AI software factory.
</p>

<p align="center">
  <a href="README.md">正體中文</a> | <b>English</b>
</p>

---

## 🌟 What can it do for me?

**AI Factory** is an AI automation platform built for people who want to solve a
problem but don't want to (or don't have time to) write code.

Just describe what you need in plain language (e.g. "add a dark-blue feedback
button to the top-left of the web page"), and the AI will automatically:

1. **Plan** — turn your wish into concrete engineering steps.
2. **Build in parallel** — send several AI agents to try different approaches at once.
3. **Pick the best** — automatically choose the most stable, compliant result.
4. **Deliver** — open a Pull Request for an engineer to review, or just let you download the whole project.

Works with **GitHub / GitLab / Bitbucket**, and is designed to drop into systems you already have.

> 🤔 **No idea where to start?** Try **"Discovery"** on the home page — answer two quick questions, pick a concrete starter card, and you're on your way even from a blank slate.

---

## ✅ What it does / ❌ What it does NOT do yet (the honest version)

Our principle is **"claims must match reality — never overpromise"**, so here's the boundary up front:

**✅ What it can do today (v1)**
- Generate **static web pages**: digital name cards, one-page shop intros, online menus, FAQs, portfolios…
- Generate **single-user, browser-local tools**: expense tracker, to-do list, habit tracker, countdown, calculator, CSV viewer
- **Discovery**: stuck on a blank page? Two questions + concrete starter cards turn a vague itch into a buildable request
- **Human-in-the-loop throughout**: the AI only produces a reviewable draft (downloadable / previewable, or a Draft PR) and never touches `main`

**❌ What it can't do yet (needs a future backend tier)**
- No backend server, database, accounts/login, online payments, or third-party integrations
- "Collect-from-others" outputs (appointment / signup / forms) are an **honest handoff**: the visitor fills the page and sends it to you themselves — **nothing lands in an inbox automatically**
- It is **not** a "type one sentence, get a full business system" tool — it's a **high-speed prototype draft generator**, not a production-ready product builder

> In short: it gets you **from 0 to a reviewable draft** — the final decision and responsibility are always yours.

> 🔐 **Where does your data go? (the honest answer)** AI Factory is a *self-hosted orchestrator*, but under the hood it calls the **official Claude / Codex CLIs** — so your **request text and code are sent to Anthropic / OpenAI cloud APIs** for processing. What you self-host is the *pipeline and the output* (your API keys and the generated code stay with you) — it is **not** "your data never leaves your machine." Judge accordingly if privacy matters to you.

---

## 🎬 How "Discovery" works

<p align="center">
  <img src="docs/screenshots/discovery-flow.png" width="300" alt="Discovery flow: entry → two questions → starter cards → name it → prefilled request">
</p>

From a blank slate to a buildable request in five steps:

1. **Home entry** — tap "🤔 No idea where to start?"
2. **Two quick questions** — who's it for? what do you want it to do? (tap-only, no typing)
3. **Pick a starter card** — see one you like and say "Yes, that one!"
4. **Name it** (optional) — and it becomes *yours*
5. **Request auto-filled** — even the capability boundary is written for you, then **you review before anything is built**

> The card library *is* the v1 capability boundary: whatever has a card is buildable; what it can't do (backend, payments, auto-receiving submissions) never appears as an option — and can't be smuggled into the request.

---

## 🎯 Our philosophy: human-in-the-loop, you stay in control

We believe technology should empower creativity, not get in its way. AI Factory has three goals:

- **Empower ideas** — let non-coders turn a plain-language wish into a usable result.
- **Augment developers** — a powerful sidekick that frees up productivity through AI coding, so you can focus on the architecture and decisions that matter.
- **Human + machine** — we are **not here to replace developers**. The AI only produces **reviewable drafts**; the final say and the responsibility always rest with a human (the developer).

This isn't just a slogan — it's built into the guardrails:

- 🧭 **Confirm before building** — the AI plans first and waits; nothing is built until you approve.
- ⏸️ **Pause / stop anytime** — a running task can be paused, resumed, or stopped.
- 📦 **Drafts, not deployments** — new projects ship as a downloadable / previewable `result.zip`; existing projects get a **draft PR** (clearly marked as AI-generated and needing human review), and the AI **never touches `main` or protected branches**.
- 🛡️ **Two layers of review** — Codex runs an automated safety review to assist you, but the AI **never merges or ships on its own** — the merge and the call are yours.

---

## 🚀 Get started in 3 steps

If your computer has Docker, open a terminal and run:

```bash
# 1. Setup wizard (auto-detects your git remote, generates ai-factory.yml and .env)
bash scripts/ai-factory-init.sh

# 2. Health check (confirms tokens, AI tools and connectivity; errors explained in plain language)
bash scripts/doctor.sh

# 3. Start — pulls a prebuilt image from GHCR, no local build needed (serves http://localhost:8080)
docker compose up
```

Then open your browser at **http://localhost:8080**

> Prefer to build from source (developers)? `docker compose -f compose.yml -f compose.build.yml up --build`

---

## 💡 Two modes (choose on the home page)

### ✨ Mode A: "Make something brand-new" (local mode)
- **For**: non-technical users, founders validating an idea quickly.
- **What you get**: **no git account or token required**. The AI generates a whole
  project from scratch and gives you a downloadable `.zip` when it's done.

### 🔧 Mode B: "Change an existing project"
- **For**: engineering teams, product managers.
- **What you get**: set the repo and token in `ai-factory.yml`. The AI opens a draft
  Pull Request on GitHub/GitLab/Bitbucket for a human to review.

---

## 🎨 The non-technical user journey

1. **Wish** — on the home page, fill in a title and description and pick a
   "development strength": ⚡ Quick / ⚖️ Balanced / 🔬 Thorough.
2. **Watch** — the progress page shows an emoji progress bar and "⏳ about N minutes
   left" (times shown in UTC+8), auto-refreshing every 3 seconds.
3. **📝 Pre-flight confirmation** — after planning, the AI pauses and tells you in
   plain language what it intends to do. You press **✅ Start building** or **❌ Cancel**.
4. **Review** —
   - New project: click "⬇️ Download your project" and read the AI's plain-language
     change summary.
   - Existing project: click "View the draft result" to open the PR; if unsure, just
     send the page link to an engineer. The AI never touches your production branch.

> If something fails, the page tells you why (e.g. which AI tool needs installing) — it never silently hangs.

---

## 🛠️ Technical reference (for engineering teams)

### Flow

```text
A request (Jira / Telegram / web form / curl)
   ↓
Issue Gateway
   ↓
[ local / docker: bash run-task.sh ]       [ k8s mode: kubectl apply Job ]
   ↓
Orchestrator
   ├─ codex-plan.sh         → plan + plain-language plan summary (plan_summary.md)
   ├─ (pre-flight gate: wait for the user's ✅ / ❌)
   ├─ claude-dev.sh × N     → parallel candidate branches
   ├─ select-best-branch.sh → pick the best
   ├─ existing repo: git/create-pr.sh → Pull/Merge request
   │  new project:   skip PR, package result.zip
   ├─ codex-review.sh       → review
   └─ claude-fix.sh         → apply fixes + change summary (summary.md)
   ↓
Human review / download
```

### Required AI tools (auto-detected)

Detected from `PATH` and common dirs (e.g. `~/.local/bin`); if missing, the web page
prompts you to install:

- **Codex**: `npm i -g @openai/codex`
- **Claude Code**: see [claude.com/claude-code](https://claude.com/claude-code) (the CLI is `claude`)

### Run locally (Java mode)

If you'd rather not use Docker. **Pass these via environment variables** (not `-D`,
otherwise the pipeline child process won't see them):

```bash
cd gateway
export AI_FACTORY_WORK_DIR="$(pwd)/../.work"
export AI_FACTORY_PIPELINE_SCRIPT="$(pwd)/../scripts/run-task.sh"
./mvnw spring-boot:run
# change port: ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

### Configuration in `ai-factory.yml`

```yaml
git:
  provider: github          # github | gitlab | bitbucket
  repo: https://github.com/acme/app.git
  targetBranch: main
agents:
  maxAgents: 3              # max number of AI agents running in parallel
security:
  allowRepositories: [ "https://github.com/acme/*" ]
  protectedBranches: [ main, "release/*" ]
  requireHumanMerge: true
  draftPullRequests: true
  confirmBeforeBuild: true        # the pre-flight confirmation gate
  confirmationTimeoutMinutes: 30
```

Secrets stay in environment / `.env`. Load order: `AI_FACTORY_CONFIG` →
`./ai-factory.yml` → `~/.ai-factory/config.yml`; env vars override the file (for CI/k8s).

### Modules

- `gateway/` — Spring Boot gateway: receives requests, validates the allowlist,
  fires the pipeline, serves the progress page and download endpoint.
- `scripts/` — Bash pipeline; status written to `<workdir>/<taskId>/status.txt`.
  - `scripts/git/` — provider-neutral PR/MR creation and the protected-branch push guard.
  - `scripts/config/` — `ai-factory.yml` loader and CLI auto-detection.
  - `scripts/ai-factory-init.sh`, `scripts/doctor.sh` — setup wizard and preflight checks.
- `config/`, `examples/` — example configuration and sample payloads.
- `charts/` — minimal Helm chart; `k8s/` — raw manifests.

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Submission form for non-technical users |
| GET | `/gateway/ui` | Friendly list of all tasks |
| GET | `/gateway/ui/{taskId}` | Human-friendly progress page |
| POST | `/gateway/issue` | Submit a structured `IssueDto` |
| POST | `/gateway/confirm/{taskId}` | Pre-flight: start building |
| POST | `/gateway/cancel/{taskId}` | Pre-flight: cancel |
| GET | `/gateway/result/{taskId}` | Download a new-project result (zip) |
| GET | `/gateway/status/{taskId}` | Task status (JSON) |
| POST | `/webhook/jira`, `/webhook/telegram` | Webhook submission |
| GET | `/actuator/health` | Readiness / liveness |

### State machine

`SUBMITTED → RUNNING → PLANNING → AWAITING_CONFIRMATION → DEVELOPING → SELECTING → MR_CREATED → REVIEWING → FIXING → COMPLETED`

`FAILED` can replace any stage. Each status has a plain-language label on the progress
page, plus an estimated time remaining (UTC+8) while running.

### 🛡️ Safety guardrails

- **Never pushes to main**: agents only push `ai/<task-id>/*`; protected branches are
  refused even if the token allows it.
- **Draft review**: every PR opens as a draft, clearly marked as AI-generated
  (GitHub/GitLab add an `ai-generated` label; Bitbucket uses a `[Draft]` title
  prefix), and needs a human to review and merge it.
- **Allowlist**: only repos listed in `ai-factory.yml` are accepted.
- **Resource safety**: `maxAgents` is capped; a crashed pipeline is reconciled to
  failed (never hangs); `status.txt` is written atomically.
- **Result safety**: the download zip excludes `.git`; the download endpoint is
  protected against path traversal.

### Deployment

- **docker compose** (recommended): `docker compose up`
- **Local Java**: see above
- **Kubernetes**: `make build && make apply-k8s`, or use the Helm chart in `charts/`

---

## 📖 Further reading

- [User guide — docs/USER_GUIDE.md](docs/USER_GUIDE.md) — for operators
- [Setup guide — docs/SETUP.md](docs/SETUP.md) — for administrators
- [正體中文 README](README.md)

---
<p align="center">
  Maintained by the LZA team and Pinkie 🫧
</p>
