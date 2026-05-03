# AI Factory: Jira / Telegram → Codex Plan → Claude Code Build → GitLab MR → Codex Review

This project is a Kubernetes-ready MVP for an AI development factory.

## Flow

```text
Telegram / Jira
   ↓
Issue Gateway  (POST /webhook/jira | /webhook/telegram | /gateway/issue)
   ↓
[ local mode: bash run-task.sh ]   [ k8s mode: kubectl apply Job ]
   ↓
Orchestrator pod
   ├─ codex-plan.sh        → docs/ai/IMPLEMENTATION_PLAN.md  +  branch ai/<id>/plan
   ├─ claude-dev.sh × N    → branches ai/<id>/dev-1..N (in parallel, branched off plan)
   ├─ select-best-branch.sh→ branch ai/<id>/final
   ├─ git-create-mr.sh     → GitLab MR (final → main)
   ├─ codex-review.sh      → docs/ai/CODEX_REVIEW.md
   └─ claude-fix.sh        → docs/ai/FIX_SUMMARY.md
   ↓
Human review & merge
```

## Modules

- `gateway/` — Spring Boot MVC gateway. Receives Jira / Telegram / direct issues, persists `issue.json`, fires the pipeline, exposes status.
- `scripts/` — Bash pipeline (plan, dev, select, MR, review, fix). Status is written to `<workdir>/<taskId>/status.txt` at every stage.
- `k8s/` — Namespace, secrets template, gateway Deployment, orchestrator Job template, RBAC.
- `docs/ai/` — generated plan/review artifacts live here per task.
- `examples/` — sample payloads.

## Endpoints

| Method | Path                       | Purpose                                   |
| ------ | -------------------------- | ----------------------------------------- |
| POST   | `/gateway/issue`           | Submit a structured `IssueDto` directly   |
| POST   | `/webhook/jira`            | Jira webhook (parses `issue.fields.*`)    |
| POST   | `/webhook/telegram`        | Telegram bot webhook (validates secret)   |
| GET    | `/gateway/status/{taskId}` | Latest status for a task                  |
| GET    | `/gateway/tasks`           | List all known tasks                      |
| GET    | `/actuator/health`         | Readiness / liveness                      |

`TaskRecord` JSON shape:

```json
{
  "taskId": "JIRA-123",
  "source": "jira",
  "externalId": "JIRA-123",
  "title": "...",
  "repo": "https://gitlab.com/org/repo.git",
  "targetBranch": "main",
  "status": "DEVELOPING",
  "message": "spawning 3 dev agents",
  "submittedAt": "2026-05-03T...",
  "updatedAt": "2026-05-03T...",
  "workDir": "/opt/ai-jobs/JIRA-123"
}
```

## Quick Start (local dev)

```bash
cd gateway
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="\
    -DAI_FACTORY_WORK_DIR=$(pwd)/../.work \
    -DAI_FACTORY_PIPELINE_SCRIPT=$(pwd)/../scripts/run-task.sh"
```

Submit a task:

```bash
curl -X POST http://localhost:8080/gateway/issue \
  -H 'Content-Type: application/json' \
  -d @../examples/issue.json
```

Check status:

```bash
curl http://localhost:8080/gateway/status/JIRA-123
```

## Required environment variables

```bash
export GITLAB_TOKEN=xxx
export GITLAB_PROJECT_ID=123456
export GITLAB_API_BASE=https://gitlab.com/api/v4
export REPO_URL=https://gitlab.com/your-org/your-repo.git    # default; per-issue .repo overrides
export OPENAI_API_KEY=xxx                                    # codex CLI
export ANTHROPIC_API_KEY=xxx                                 # claude-code CLI
export TELEGRAM_BOT_TOKEN=xxx
export TELEGRAM_WEBHOOK_SECRET=xxx                           # optional; checked against X-Telegram-Bot-Api-Secret-Token
```

## Status state machine

`SUBMITTED → PLANNING → DEVELOPING → SELECTING → MR_CREATED → REVIEWING → FIXING → COMPLETED`

`FAILED` can replace any stage; the `MESSAGE` field carries `stage:<name> rc:<exit-code>`.

## K8s deployment

```bash
make build            # build gateway + agent images
make apply-k8s        # namespace, secrets, RBAC, configmaps, gateway deployment
```

`apply-configmaps` packs `scripts/` into `ai-pipeline-scripts` and the orchestrator Job template into `ai-job-template`. The gateway pod mounts both. On submit, the gateway runs `create-k8s-job.sh`, which embeds `issue.json` as a base64 env var (`ISSUE_JSON_B64`) into the orchestrator Job, so the Job needs no shared volume with the gateway.

## Production Guardrails

- Agents must not push to `main`.
- Agents only push `ai/<task-id>/*` branches.
- Agents must create MR and wait for human approval.
- Production deploy must be blocked by protected branch + manual approval.
- `select-best-branch.sh` uses `--force-with-lease` for the `final` branch (never `--force`).
