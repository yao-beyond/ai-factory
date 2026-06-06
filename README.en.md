<p align="center">
  <img src="docs/mascot.jpg" alt="AI Factory mascot: Pinkie 🫧" width="180"/>
</p>

<h1 align="center">AI Factory</h1>
<p align="center">
  <b>Turn a plain-language request into a reviewed Pull Request — automatically.</b><br/>
  <sub>Mascot: Pinkie (粉圓) 🫧 — just as she blows bubbles, AI Factory blows your one-line request into a reviewable result</sub>
</p>

<p align="center"><a href="README.md">繁體中文</a> | <b>English</b></p>

---

You describe what you want. AI Factory plans it, has several AI engineers build it in
parallel, picks the best version, runs an automated review, fixes the findings, and opens
a draft Pull Request for a human to approve. It works with **GitHub, GitLab, or Bitbucket**
and is designed to drop into systems you already have.

```text
A request (Jira / Telegram / a form / curl)
   ↓
🧠 Plan  →  👩‍💻 Build ×N  →  🧪 Pick best  →  📬 Open PR  →  🔍 Review  →  🛠️ Fix
   ↓
✅ A draft Pull Request, labeled "ai-generated", waiting for a human to merge
```

---

## Who should read what

| You are… | Start here |
| --- | --- |
| Deciding if this is worth it | This page (below) |
| Going to **use** it (no coding) | [docs/USER_GUIDE.md](docs/USER_GUIDE.md) |
| Going to **install** it | [docs/SETUP.md](docs/SETUP.md) |
| A developer extending it | This page → *Technical reference* |

---

## Why use it

- **Faster delivery** — routine changes go from request to reviewable PR without a developer
  starting from scratch.
- **Built-in quality** — every change is reviewed and fixed by AI before a human sees it, and
  trading/payment-style logic is required to ship with tests.
- **Safe by default** — AI never pushes to `main`; every result is a *draft* PR a human must
  approve. Nothing in your existing process breaks.

---

## Get started in 3 steps

```bash
# 1. Configure (interactive wizard — no need to edit files by hand)
bash scripts/ai-factory-init.sh

# 2. Check everything is connected
bash scripts/doctor.sh

# 3. Start
docker compose up        # serves http://localhost:8080
```

Then submit the demo task and watch it run:

```bash
curl -X POST http://localhost:8080/gateway/issue \
  -H 'Content-Type: application/json' \
  -d @examples/hello-world-issue.json
# open the human-friendly progress page:
#   http://localhost:8080/gateway/ui/<taskId>
```

Prefer to configure by hand or deploy another way (plain Java, Kubernetes)? See
[docs/SETUP.md](docs/SETUP.md).

---

## Configuration in one file

Everything except secrets lives in `ai-factory.yml` (see
[config/ai-factory.example.yml](config/ai-factory.example.yml) and the
[github](examples/ai-factory.github.yml) / [gitlab](examples/ai-factory.gitlab.yml) examples):

```yaml
git:
  provider: github          # github | gitlab | bitbucket
  repo: https://github.com/acme/app.git
  targetBranch: main
agents:
  maxAgents: 3
security:
  allowRepositories: [ "https://github.com/acme/*" ]
  protectedBranches: [ main, "release/*" ]
  requireHumanMerge: true
  draftPullRequests: true
```

Secrets stay in the environment / `.env` (see [.env.example](.env.example)). Config file
precedence: `AI_FACTORY_CONFIG` → `./ai-factory.yml` → `~/.ai-factory/config.yml`; environment
variables override the file for CI/Kubernetes.

---

## Technical reference

### Flow

```text
Jira / Telegram / direct issue
   ↓
Issue Gateway  (POST /webhook/jira | /webhook/telegram | /gateway/issue)
   ↓
[ local / docker: bash run-task.sh ]   [ k8s mode: kubectl apply Job ]
   ↓
Orchestrator
   ├─ codex-plan.sh         → docs/ai/IMPLEMENTATION_PLAN.md  + branch ai/<id>/plan
   ├─ claude-dev.sh × N     → branches ai/<id>/dev-1..N (parallel, off plan)
   ├─ select-best-branch.sh → branch ai/<id>/final
   ├─ git/create-pr.sh      → Pull/Merge request (github | gitlab | bitbucket)
   ├─ codex-review.sh       → docs/ai/CODEX_REVIEW.md
   └─ claude-fix.sh         → docs/ai/FIX_SUMMARY.md
   ↓
Human review & merge
```

### Modules

- `gateway/` — Spring Boot gateway. Receives issues, validates them against the repository
  allowlist, persists `issue.json`, fires the pipeline, and exposes status + a human-friendly
  status page.
- `scripts/` — Bash pipeline. Status is written to `<workdir>/<taskId>/status.txt` at each stage.
  - `scripts/git/` — provider-neutral PR/MR creation (`create-pr.sh` + `providers/*.sh`) and the
    protected-branch push guard.
  - `scripts/config/` — `ai-factory.yml` loader.
  - `scripts/ai-factory-init.sh`, `scripts/doctor.sh` — setup wizard and preflight checks.
- `config/`, `examples/` — example configuration and sample payloads.
- `k8s/` — Namespace, secrets template, gateway Deployment, orchestrator Job template, RBAC.
- `charts/` — minimal Helm chart for the gateway.
- `docs/ai/` — review checklist and a plan template. Real per-task artifacts
  (`IMPLEMENTATION_PLAN.md`, `CODEX_REVIEW.md`, `FIX_SUMMARY.md`, `CLAUDE_SUMMARY_*.md`) are
  generated at runtime inside the *target* repo, not here.

### Endpoints

| Method | Path                       | Purpose                                     |
| ------ | -------------------------- | ------------------------------------------- |
| GET    | `/`                        | **Submission form for non-technical users (HTML)** |
| GET    | `/gateway/ui`              | **All tasks, friendly list (HTML)**         |
| GET    | `/gateway/ui/{taskId}`     | **Human-friendly progress page (HTML)**     |
| POST   | `/gateway/issue`           | Submit a structured `IssueDto` directly     |
| POST   | `/webhook/jira`            | Jira webhook (parses `issue.fields.*`)      |
| POST   | `/webhook/telegram`        | Telegram bot webhook (validates secret)     |
| GET    | `/gateway/status/{taskId}` | Latest status for a task (JSON)             |
| GET    | `/gateway/tasks`           | List all known tasks (JSON)                 |
| GET    | `/actuator/health`         | Readiness / liveness                        |

### Status state machine

`SUBMITTED → RUNNING → PLANNING → DEVELOPING → SELECTING → MR_CREATED → REVIEWING → FIXING → COMPLETED`

`FAILED` can replace any stage; the `MESSAGE` field carries `stage:<name> rc:<exit-code>`.
Each status also has a plain-language label shown on the progress page, plus an estimated
time remaining while running.

### Kubernetes deployment

```bash
make build            # build gateway + agent images
make apply-k8s        # namespace, secrets, RBAC, configmaps, gateway deployment
```

The gateway pod mounts the pipeline scripts and Job template as ConfigMaps. On submit it runs
`create-k8s-job.sh`, which embeds `issue.json` as a base64 env var (`ISSUE_JSON_B64`) into the
orchestrator Job; the orchestrator runs from the agent image (which contains the full
`scripts/` tree, including `git/` and `config/`). A Helm chart is also available under `charts/`.

### Production guardrails

- Agents only ever push `ai/<task-id>/*` branches; `scripts/git/branch-guard.sh` refuses to push
  to a protected branch even if the token would allow it.
- Every result is a **draft** Pull/Merge request labeled `ai-generated`, requiring human merge.
- The gateway rejects any repository not in `security.allowRepositories`
  (`AI_FACTORY_ALLOW_REPOSITORIES`), caps `maxAgents`, and reconciles a crashed pipeline to
  `FAILED` instead of hanging.
- `select-best-branch.sh` uses `--force-with-lease` for the `final` branch (never `--force`).
