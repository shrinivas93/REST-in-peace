# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- `CONTRIBUTING.md` and this changelog.

### Fixed

- The hosted Javadoc site now always reflects the exact commit that was
  released, instead of `master`'s post-release `-SNAPSHOT` version bump.

## [1.0.0.4] - 2026-08-30

### Added

- Javadocs across the entire public API — every class, annotation, method,
  and field.
- A `-javadoc.jar` is now attached to every GitHub Packages release
  alongside the main jar, and full API docs are hosted on GitHub Pages.

## [1.0.0.3] - 2026-08-30

### Added

- Response deserialization: a method's return type now controls how the
  response is handled — `String` for the raw body, `void` to discard it,
  anything else deserialized from JSON.
- Async support via `CompletableFuture<T>` return types, plus
  `RIP.useDaemonThreadsForAsync()` to opt out of Unirest's async client
  keeping a short-lived program's JVM alive.
- Global request/response interceptors (`RIP.addInterceptor(...)`):
  `beforeRequest` can add headers or abort the call by throwing,
  `afterResponse` observes the status and body. `afterResponse` runs in LIFO
  order relative to `beforeRequest`'s FIFO order (the first interceptor
  registered wraps every other one, mirroring OkHttp/Servlet-filter chains).
- Pre-built interceptors: `HeaderInterceptor` (static, dynamic, or
  multi-header), `LoggingInterceptor`, and `CorrelationIdInterceptor`.
- `ROADMAP.md` tracking library-maturity items.

## [1.0.0.2] - 2026-08-30

### Fixed

- `@Body` request bodies now get `Content-Type: application/json` when the
  value is a serialized object (previously only explicit `String` bodies
  were sent correctly).
- `release.yml` granted `actions: write` so it can dispatch
  `maven-publish.yml`.

### Verified

- Kotlin and Scala interop confirmed against the built jar.

## [1.0.0.1] - 2026-08-30

### Added

- A reusable consumer-test workflow that verifies a freshly published
  release actually resolves and works for a downstream consumer.

### Fixed

- `release.yml` now explicitly dispatches `maven-publish.yml` after creating
  a release. A release created via the workflow's own `GITHUB_TOKEN` doesn't
  trigger other workflows' `on: release` listeners (GitHub's anti-recursion
  rule), so publishing never used to fire on its own.

## [1.0.0.0] - 2026-08-30

Initial release.

### Added

- Declarative REST clients: annotate an interface with `@RestClient` and get
  a working HTTP client back from `RIP.getClient(...)`, backed by a JDK
  dynamic proxy.
- All seven HTTP verb annotations: `@GET`, `@POST`, `@PUT`, `@PATCH`,
  `@DELETE`, `@HEAD`, `@OPTIONS`.
- `@PathParam`, `@QueryParam`, `@HeaderParam`, and `@Body` parameter binding,
  with `required`/`defaultValue` support on query and header params.
- Upfront interface validation: a misconfigured `@RestClient` interface
  fails fast at `RIP.getClient(...)` time with the full list of problems,
  not on the first call.
- JUnit 5 test suite.
- CI workflow, automated releases via `maven-release-plugin`, and publishing
  to GitHub Packages.
