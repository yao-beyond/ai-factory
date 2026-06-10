# Security Policy

## Supported Versions

This project is currently in **early MVP** state. Only the `main` branch receives security updates.

| Version | Supported |
| ------- | --------- |
| `main`  | ✅        |
| pre-1.0 tags | ❌  |

## Reporting a Vulnerability

**Please do not file public GitHub issues for security problems.**

Instead, use one of:

1. GitHub's [Private Vulnerability Reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability) on this repo.
2. Email the maintainer at the address listed in the GitHub profile, with `[ai-factory security]` in the subject.

Please include:

- A description of the issue and its impact.
- Steps to reproduce (a minimal payload, request, or script).
- Affected commit / tag.
- Your suggested fix, if any.

We aim to acknowledge reports within 72 hours and to issue a fix or workaround within 14 days for high-severity issues.

## Threat model notes

The gateway runs `bash` pipeline scripts and (in K8s mode) creates Jobs in your cluster. Anyone who can reach `/gateway/issue` or the webhook endpoints can trigger pipeline runs. **Always**:

- Put the gateway behind authentication (mTLS, ingress auth, an API gateway, etc.) or a private network.
- Set `TELEGRAM_WEBHOOK_SECRET` in production.
- Restrict the Kubernetes RBAC for `ai-factory-orchestrator` to only the namespaces and verbs you need.
- Treat `ai-secrets` (Codex / Claude / GitLab tokens) as production secrets — use a real secret store, not the example file.

### Credential handling

- **Your prompts and code reach cloud APIs.** The pipeline shells out to the
  Claude / Codex CLIs, so request text and repository code are sent to
  Anthropic / OpenAI. Self-hosting covers the pipeline and the artifacts, not
  the model. Do not submit data you may not share with those providers.
- **Least-privilege for AI subprocesses.** The `claude` / `codex` CLI steps run
  with git-provider and messaging tokens (`GITHUB_TOKEN`, `GIT_TOKEN`,
  `GITLAB_TOKEN`, `BITBUCKET_TOKEN`, `TELEGRAM_*`) stripped from their
  environment — the agents only see their model key. Git transport still
  authenticates normally via the remote URL / credential helper.
- **Output redaction.** The live activity feed *and* the AI-authored
  `summary.md` / `plan_summary.md` are passed through a secret-redaction filter
  (token shapes, `user:secret@host` URLs, `key=value` secrets, auth headers)
  before they are shown in the UI.
- **Deliverable hygiene.** The downloadable `result.zip` excludes git metadata
  (root *and* nested `.git`), `.env*`, AI CLI auth dirs (`.claude`, `.codex`),
  and common private-key files, so a credential cannot ride out in the archive.
- **No git token on disk in the repo.** Repo-mode transport authenticates via a
  just-in-time askpass helper (`scripts/lib/git-auth.sh`): the clone URL stored
  in `.git/config` is always credential-free, the token is staged in a `0600`
  file *outside* the worktree (removed on exit), and its value is exposed only
  inline for a single git network command — never to the AI CLI's environment.
- **Remaining hardening (tracked):** the gateway process passes its full
  environment to the pipeline; the token file, while outside the worktree, is
  still on a filesystem the same-user AI process could read if it went looking
  (full isolation needs a separate-user/sandbox model); uploaded-zip import does
  not yet strip secrets on the way in; and the Jira webhook has no signature
  check (set network/auth controls in front of it). Review
  `docs/RELEASE_NOTES_v0.1.0.md` before exposing the gateway publicly.
