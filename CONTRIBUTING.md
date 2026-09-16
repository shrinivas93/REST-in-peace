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

`core/` and `spring-boot-starter/` are sibling Maven modules — `mvn` at the
repo root builds both. `-Dmaven.compiler.release=8` applies to `core` only
(the starter targets Java 17); run it scoped before pushing a core change:

```bash
mvn -Dmaven.compiler.release=8 clean test -pl core
```

A plain `mvn test` compiles against your local JDK's own class library, which
silently accepts APIs added after Java 8 (e.g. `List.of`) even though core
targets `source`/`target` 8. Only `-Dmaven.compiler.release=8` forces javac
to check against the real Java 8 API surface, which is what CI enforces for
`core`. A change that passes locally without this flag but fails CI almost
always means it used a post-8 API.

To check the generated API docs build cleanly — this needs to target
`core/` directly, since the parent POM has no javadoc plugin config of its
own:

```bash
mvn javadoc:javadoc --file core/pom.xml
```

### Coverage

`mvn test` also generates a JaCoCo coverage report as a normal part of the
`test` phase (`jacoco-maven-plugin` is configured once on the root `pom.xml`
and inherited by both `core` and `spring-boot-starter` - no separate goal to
remember). Open `core/target/site/jacoco/index.html` (or the equivalent path
under `spring-boot-starter/target/`) in a browser after running tests; `ci.yml`
and `spring-boot-starter-test.yml` also upload it as a build artifact on every
run. Pinned to `0.8.12`, not the newer `0.8.13`, since the plugin itself has
to run under whatever JDK executes the build - `ci.yml`'s `core` job runs
under a real JDK 8, and `0.8.13` raised its own minimum to JDK 11.

Both workflows also upload the same `jacoco.xml` to Codecov
(`codecov/codecov-action`, one upload per module tagged with a `core`/
`spring-boot-starter` flag) for the badge/PR-diff-coverage/trend layer the
plain build artifact doesn't provide - see
[codecov.io/gh/shrinivas93/REST-in-peace](https://codecov.io/gh/shrinivas93/REST-in-peace).
Needs a `CODECOV_TOKEN` repository secret (Settings → Secrets and variables →
Actions), generated from the repo's own Codecov settings page; `fail_ci_if_error: false`
means a Codecov outage or a missing/invalid token degrades to "no coverage
uploaded this run" rather than failing CI outright.

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
- `master` is only ever updated by merging a pull request from `develop` —
  **never push or merge directly to `master`**, including when cutting a
  release. `master` is branch-protected to require a pull request (with CI
  passing) before merging; the one exception is `release.yml`'s own
  version-bump/tag commit (see below), which is bypass-listed for the
  `github-actions` bot specifically since `maven-release-plugin` pushes it
  directly by design.
- Every PR and every push to `develop`/`master` runs `ci.yml` (`core`'s
  test suite on Java 8) and `spring-boot-starter-test.yml` (the starter's
  own tests plus the sample Spring Boot consumer, on Java 17) in parallel.

## Release process (maintainers)

`core` and `spring-boot-starter` share one version (both inherit it from
the root `pom.xml`), so there's a single, unified release for both — no
separate release process or manual pre-bump step for the starter.

1. Open and merge a pull request from `develop` into `master` (this is the
   only way `master` advances outside of the release commit itself).
2. Run the **Release** workflow (`.github/workflows/release.yml`) via
   `workflow_dispatch` against `master`. It uses `maven-release-plugin` at
   the repo root to tag the release and bump the whole reactor (parent,
   `core`, and `spring-boot-starter` together) to the next `-SNAPSHOT` (its
   commits push directly to `master`, exempted from the PR requirement
   above), creates the GitHub Release, and dispatches `maven-publish.yml`
   (publishes the parent POM, both jars, and the javadoc jar to GitHub
   Packages) and `javadoc.yml` (rebuilds and deploys the hosted API docs)
   against the exact release tag.

Update `CHANGELOG.md` under `[Unreleased]` as part of any user-facing change;
it gets turned into a versioned section when the next release is cut.

## Roadmap

`ROADMAP.md` tracks library-maturity items that aren't tied to a specific
issue. Check items off there as they land.
