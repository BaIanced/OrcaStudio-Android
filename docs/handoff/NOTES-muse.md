# Muse: how to use it (reference, not read per turn)

Muse = the second agent that takes audits and larger JVM-testable Kotlin tasks. Read this only when deciding to send it work or when a Muse PR/comment appears.

## Permissions (user)
- 2026-10-25: full discretion. Claude decides whether and how to use Muse (task, ping on stalls, receive, guide) without asking; just say what was delegated.
- 2026-10-22: Claude may merge Muse `muse/task-*` PRs and its own task-list PRs into `muse/work` after the local check (compile, unit tests, forbidden paths, diff read). Main merges and releases still need the user.
- The auto-mode classifier blocks merging Claude's OWN PRs: only allowed when the command starts with `gh pr merge` (use `--repo BaIanced/OrcaStudio-Android`, no `cd &&` prefix). Permission-setting edits stay blocked: ask the user.

## Scope and cost watch (user, 2026-10-23)
- Send: audits + larger JVM-testable Kotlin. Not: native/patch work, small fixes (do those directly; trial cost ~50-60k Claude tokens per small task, about break-even).
- Keep Muse only while it saves usage. Log Claude's effort per Muse task in HANDOFF-ARCHIVE.md; when one costs more than doing it directly, tell the user unprompted and stop that kind of task.

## Comms (GitHub as mailbox)
- Tasks in `MUSE-TASKS.md` on branch `muse/work`; Claude writes the task, then posts `TASK <n> READY` on issue #46. Muse answers `TASK <n> DONE` on #46 with one draft PR per task (`muse/task-<n>-...`).
- Session-start check, run exactly:
  `gh pr list --state open --json number,title,headRefName; gh issue view 46 --json comments --jq '.comments[-2:][]|.createdAt+" "+(.body|.[0:300])'`
- Review recipe: pull the branch, compile locally, run unit tests, check no forbidden paths (src-orca, patches, secrets), read the diff, privacy scan, then merge into `muse/work`; cherry-pick to the work branch as needed.
- Status: Tasks 1-18 merged (2026-10-28).
