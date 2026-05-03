# Contributing to AI Factory

Thanks for your interest! This is an early-stage project, so issues, design discussions, and PRs are all welcome.

## Ways to contribute

- **Bug reports** — file an issue using the bug template; include the failing endpoint, the request, and `run.log` snippets if you have them.
- **Feature proposals** — open a discussion or issue first for anything larger than a small fix; the design can move quickly.
- **Pull requests** — see the workflow below.
- **Docs** — this README and `docs/` always need more clarity, especially around the K8s flow.

## Local development

Prerequisites: JDK 21, `bash`, `git`, `jq`. Optional for full pipeline runs: `codex` CLI, `claude-code` CLI, `kubectl`.

```bash
git clone <your-fork>
cd ai-factory/gateway
./mvnw -DskipTests package
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-DAI_FACTORY_WORK_DIR=$(pwd)/../.work \
                                  -DAI_FACTORY_PIPELINE_SCRIPT=$(pwd)/../scripts/run-task.sh"
```

Submit a sample task:

```bash
curl -X POST http://localhost:8080/gateway/issue \
  -H 'Content-Type: application/json' \
  -d @../examples/issue.json
```

## Tests and lint

```bash
cd gateway && ./mvnw test            # JVM tests
shellcheck scripts/*.sh              # bash lint (install via brew/apt)
```

CI runs both on every PR.

## PR workflow

1. Fork and create a topic branch off `main`. Branch naming: `feat/<short-name>`, `fix/<short-name>`, `docs/<short-name>`.
2. Keep PRs small and focused. One concern per PR.
3. Add or update tests for any behavioral change.
4. Update `CHANGELOG.md` under `[Unreleased]` with a one-line summary.
5. Run `./mvnw test` and `shellcheck scripts/*.sh` locally before pushing.
6. Open a PR. Fill in the template; link related issues.

### Commit messages

We loosely follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat: ...` new feature
- `fix: ...` bug fix
- `docs: ...` documentation only
- `refactor: ...` code change that neither fixes a bug nor adds a feature
- `test: ...` adds or fixes tests
- `chore: ...` build / tooling

Not strictly enforced, but it makes the changelog easier.

## Code style

- Java: standard Spring Boot conventions; avoid Lombok unless agreed.
- Bash: `set -euo pipefail`, quote variables, prefer `printf` over `echo -e`.
- Keep comments minimal; explain *why*, not *what*.

## Licensing

By submitting a contribution, you agree that your contribution will be licensed under the [Apache License 2.0](LICENSE).

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold it.
