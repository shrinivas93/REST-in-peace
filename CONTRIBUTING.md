# Contributing to REST-in-peace

Thanks for considering a contribution. This document covers what you need to
build the project, the conventions the codebase follows, and how changes get
from a branch into a release.

## Requirements

- Java 8 or newer
- Maven

## Building and testing

```bash
mvn clean test
```

If your local JDK is newer than 8 (likely), also run this before pushing:

```bash
mvn -Dmaven.compiler.release=8 clean test
```

A plain `mvn test` compiles against your local JDK's own class library, which
silently accepts APIs added after Java 8 (e.g. `List.of`) even though the
project targets `source`/`target` 8. Only `-Dmaven.compiler.release=8` forces
javac to check against the real Java 8 API surface, which is what CI enforces.
A change that passes locally without this flag but fails CI almost always
means it used a post-8 API.

To check the generated API docs build cleanly:

```bash
mvn javadoc:javadoc
```

## Code style

- Tabs for indentation, matching the existing source.
- No comments unless something is genuinely non-obvious (a hidden constraint,
  a workaround, a subtle invariant) — well-named code and Javadoc cover the
  rest. Every public class, annotation, method, and field should have a
  Javadoc comment.
- Keep changes minimal and scoped to what's being asked — no speculative
  abstractions or unrelated cleanup mixed into a fix.

## Git workflow

- Branch from `develop`, open your PR against `develop`.
- `develop` → `master` only happens when cutting a release — never push or
  merge directly to `master` otherwise.
- Every PR and every push to `develop`/`master` runs the CI workflow
  (`.github/workflows/ci.yml`): build + full test suite on Java 8.

## Release process (maintainers)

Releases are cut by running the **Release** workflow
(`.github/workflows/release.yml`) via `workflow_dispatch` against `master`.
It uses `maven-release-plugin` to tag the release and bump `master` to the
next `-SNAPSHOT`, creates the GitHub Release, and dispatches
`maven-publish.yml` (publishes the jar + javadoc jar to GitHub Packages) and
`javadoc.yml` (rebuilds and deploys the hosted API docs) against the exact
release tag.

Update `CHANGELOG.md` under `[Unreleased]` as part of any user-facing change;
it gets turned into a versioned section when the next release is cut.

## Roadmap

`ROADMAP.md` tracks library-maturity items that aren't tied to a specific
issue. Check items off there as they land.
