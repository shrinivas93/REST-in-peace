# Notes for Claude Code

## Working preferences

- **When monitoring CI, a PR, or a triggered workflow (e.g. the `Release`
  workflow), check back frequently and quickly rather than waiting long
  intervals between checks.** Prefer short polling intervals over long
  scheduled wakeups when actively watching something in progress.
