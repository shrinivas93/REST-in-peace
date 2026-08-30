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
- [ ] **Javadocs** — the public API currently has no doc comments (surfaced
      by the `release-central` Maven profile's javadoc generation). Worth
      doing before wider external adoption.
- [ ] **CONTRIBUTING.md + CHANGELOG.md** — light process maturity now that
      the project has real, automated releases going out.
- [ ] **Maven Central publishing** — full setup (groupId change to
      `io.github.shrinivas93`, POM metadata, GPG signing, publish workflow)
      is built and verified but paused pending account-level setup (Sonatype
      Central Portal account, GPG key, publishing token). Preserved on the
      `feature/maven-central-publishing` branch (was PR #9, closed
      without merging) — pick it back up when ready.
