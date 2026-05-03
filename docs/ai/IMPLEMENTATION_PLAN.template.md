# Implementation Plan — TEMPLATE

> This file is a **reference template** showing the structure of the plan that
> `scripts/codex-plan.sh` produces at pipeline runtime inside the *target* repo
> (the one cloned from `REPO_URL`), at path `docs/ai/IMPLEMENTATION_PLAN.md`.
> It is NOT loaded by the pipeline itself — `claude-dev.sh` reads the runtime
> file, not this template. Use this as a guide if you want to hand-author a
> plan or run without the `codex` CLI.

---

## 1. Background and goals

Brief restatement of the issue (from `docs/ai/issue.json`) and the outcome
the change should produce. One paragraph.

## 2. System design

High-level architecture. Diagram or bullet list of the components involved
and how requests / data flow between them.

## 3. Module breakdown

- `module-a/` — what changes
- `module-b/` — what changes
- new files / packages introduced

## 4. API / DB schema / config changes

- HTTP endpoints added or modified (path, method, request, response)
- DB schema migrations (new columns, indexes, default values, backfill plan)
- Config keys added (default, env var name)

## 5. Domain risks

For trading systems specifically, but applicable to any high-stakes domain:

- Funding rate precision and rounding
- Liquidation price and margin calculation
- Idempotency of order / settlement operations
- Concurrency and transaction boundaries
- BigDecimal scale and rounding mode explicitly defined
- Time zone / clock assumptions

## 6. Test strategy

- Unit tests: which pure functions get coverage
- Integration tests: which DB / Redis / Kafka / HTTP boundaries get exercised
- Regression tests: the exact failing scenario from the issue

## 7. Claude Code task list

Numbered list of self-contained edits the dev agents will make, each
small enough to verify in isolation.

1. ...
2. ...
3. ...

## 8. Acceptance criteria

- All tests pass: `./gradlew test` (or project-specific equivalent)
- New regression test reproduces the original bug and now passes
- Manual smoke test path: ...
- No changes outside the modules listed in section 3
