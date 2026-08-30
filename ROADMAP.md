# Roadmap

Library-maturity items identified while building out REST-in-peace, kept here
for reference rather than tracked in code. Check items off as they land.

- [x] **Response deserialization** — a method's return type now controls
      deserialization: `String` for the raw body, anything else deserialized
      from response JSON.
- [x] **Async support** — a method returning `CompletableFuture<T>` fires
      via Unirest's `asStringAsync()`/`asObjectAsync()` instead of blocking.
- [x] **Interceptors** — `RIP.addInterceptor(...)` hooks into every
      request/response globally: `beforeRequest` can add headers or abort by
      throwing, `afterResponse` observes status/body. Covers auth token
      injection and logging. Does **not** cover retry policy - a passive
      observer can't cause a request to be re-sent, so that would need a
      different mechanism if wanted later.
- [x] **Javadocs** — every public class, annotation, method, and field across
      the library now has a doc comment. Verified with a clean
      `mvn javadoc:javadoc` run (0 warnings) - the `release-central` Maven
      profile's javadoc generation, whenever that's picked back up, won't
      surface anything new.
- [x] **CONTRIBUTING.md + CHANGELOG.md** — `CONTRIBUTING.md` covers building,
      testing, code style, and the branch/release workflow; `CHANGELOG.md`
      (Keep a Changelog format) documents every release from v1.0.0.0
      through v1.0.0.4, plus an `[Unreleased]` section to update going
      forward.
- [ ] **Maven Central publishing** — full setup (groupId change to
      `io.github.shrinivas93`, POM metadata, GPG signing, publish workflow)
      is built and verified but paused pending account-level setup (Sonatype
      Central Portal account, GPG key, publishing token). Preserved on the
      `feature/maven-central-publishing` branch (was PR #9, closed
      without merging) — pick it back up when ready.
- [x] **Refactor to idiomatic Java 8** — swept `RestRequestProcessor`,
      `RestClientValidator`, and `RestClientInvocationHandler` for imperative
      loops and manual `Optional` isPresent/get patterns, replacing them with
      `forEach`, method references, streams, and `orElseThrow`/`ifPresent`.
      Left the parallel-array parameter loops and the `Matcher`-based path
      param loop as-is (no genuine Java 8 improvement without a side-effecting
      stream or a Java 9+ API). Verified with
      `mvn -Dmaven.compiler.release=8 clean test` and a live run against
      httpbin.org (PR #34).
