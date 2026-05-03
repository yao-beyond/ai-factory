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
