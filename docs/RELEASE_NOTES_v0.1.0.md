# AI Factory v0.1.0 — first public preview

> **Status: preview / MVP.** This is the first public release. It is meant for
> early adopters and feedback, not production-critical workloads.

## What is this?

AI Factory (粉圓軟體工廠) is a **self-hosted orchestrator** that turns a
plain-language request into reviewable code. You describe what you want; it
plans, sends several Claude / Codex agents to build in parallel, picks the best
result, and delivers it — as a downloadable `result.zip` (new projects) or a
**draft Pull Request** (existing repos). A human always reviews before anything
is merged or shipped.

## Who is it for?

- **Non-technical users / founders** who want a static page or a small
  browser tool without writing code.
- **Engineering teams** who want an AI draft as a PR they can review, on top of
  GitHub / GitLab / Bitbucket.

## What it can do today

- Generate **static web pages** (name cards, one-page sites, menus, FAQs, portfolios).
- Generate **single-user, browser-local tools** (to-do, expense tracker, countdown, CSV viewer…).
- **Discovery** guided flow for users starting from a blank slate.
- Human-in-the-loop guardrails: confirm-before-build, pause/stop, draft-only output, never touches `main`.

## Known limitations (read before you try)

- **No backend tier** — no database, accounts, payments, or third-party integrations.
  "Collect-from-others" outputs are an honest handoff, not an automatic inbox.
- **Your data reaches cloud APIs.** Self-hosting covers the pipeline and the
  output, *not* the model: prompts and code are sent to Anthropic / OpenAI.
- **You still need to supply CLI auth.** The image bundles the `claude` /
  `codex` CLIs, but you must provide credentials at runtime — set
  `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` in `.env` (or mount `~/.claude` /
  `~/.codex`). Without auth the gateway starts but tasks cannot build.
- **`linux/amd64` image only.** arm64 runs under emulation on Apple Silicon.
- Not a "one sentence → full business system" tool. It is a fast draft generator.

## Install

```bash
cp .env.example .env                                   # fill in tokens
cp config/ai-factory.example.yml ai-factory.yml        # then edit
docker compose up                                      # pulls the prebuilt GHCR image
# open http://localhost:8080
```

Build from source instead (developers):

```bash
docker compose -f compose.yml -f compose.build.yml up --build
```

Image: `ghcr.io/yao-beyond/ai-factory-gateway:0.1.0`

## CLI dependencies

The pipeline shells out to the `claude` and `codex` CLIs. **The Docker image
bundles both** (`@anthropic-ai/claude-code`, `@openai/codex`), so the default
`docker compose up` needs no extra install — you only supply auth via `.env`
(`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`).

For **local Java mode** (no Docker) you must install them yourself:

- **Codex** — `npm i -g @openai/codex`
- **Claude Code** — see https://claude.com/claude-code (CLI is `claude`)

## Security reminders

- Never commit `.env`, API keys, or git tokens. `.env.example` holds placeholders only.
- Use a **fine-grained** GitHub PAT (Contents + Pull requests write), not a classic all-repo token.
- The system runs AI-generated code/commands. **v0.1.0 does not provide strong
  sandboxing** — do not run it in a high-privilege environment.
- Apache-2.0 covers this repo only — you remain responsible for complying with
  Anthropic / OpenAI / GitHub terms and for reviewing all generated output.

## Links

- [CHANGELOG](../CHANGELOG.md)
- [Setup guide](SETUP.md) · [User guide](USER_GUIDE.md)
