# Notes for Claude Code

## Working preferences

- **When monitoring CI, a PR, or a triggered workflow (e.g. the `Release`
  workflow), check back frequently and quickly rather than waiting long
  intervals between checks.** Prefer short polling intervals over long
  scheduled wakeups when actively watching something in progress.
- **Never commit directly to `develop` (or `master`).** Even for a small
  fix (e.g. addressing a review-bot finding on an open release PR), branch
  off `develop`, commit there, push, and open a PR into `develop` for
  review. Let that PR go through the same CI/Greptile/cubic review as any
  other change before it lands on `develop`.
