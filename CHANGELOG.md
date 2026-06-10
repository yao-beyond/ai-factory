# Changelog

All notable changes to this project are documented here.
Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project follows [Semantic Versioning](https://semver.org/) once it reaches `1.0.0`.

## [Unreleased]

### Added
- Initial public release scaffolding (License, Contributing, Security, Code of Conduct).
- GitHub Actions CI: gateway Maven build + shellcheck.
- Issue and PR templates.
- Release workflow builds a **multi-arch** image (`linux/amd64` + `linux/arm64`);
  the Dockerfile fetches kubectl per `TARGETARCH`.

### Changed
- Release workflow advances the `:latest` tag only for **stable** releases; a
  semver prerelease tag (e.g. `v0.2.0-rc1`) publishes its version tag without
  moving `:latest`.

## [0.1.0] - 2026-05-03

### Added
- Spring Boot 3.3 / Java 21 gateway with `/gateway/issue`, `/gateway/status/{taskId}`,
  `/gateway/tasks`, `/webhook/jira`, `/webhook/telegram`, `/actuator/health`.
- In-memory task registry that hydrates from on-disk `status.txt`.
- Pipeline scripts: `run-task.sh`, `codex-plan.sh`, `claude-dev.sh`,
  `select-best-branch.sh`, `git-create-mr.sh`, `codex-review.sh`, `claude-fix.sh`.
- Kubernetes manifests: namespace, secrets template, gateway Deployment, orchestrator
  Job template, RBAC, ConfigMap stub.
- Atlassian Document Format flattening for Jira webhooks.
- Telegram webhook secret token validation.

[Unreleased]: https://github.com/yao-beyond/ai-factory/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/yao-beyond/ai-factory/releases/tag/v0.1.0
