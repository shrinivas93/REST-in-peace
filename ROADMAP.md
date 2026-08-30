# Roadmap

Library-maturity items identified while building out REST-in-peace, kept here
for reference rather than tracked in code. Check items off as they land.

- [ ] **Response deserialization** — methods currently always return the raw
      response body as `String`. Support return-type-driven deserialization
      (e.g. `User getUser(...)`) so callers get a POJO back instead of
      hand-parsing JSON themselves. *(In progress.)*
- [ ] **Async support** — every call currently blocks. Unirest supports
      `asStringAsync()`/`asObjectAsync()`; expose an async calling
      convention (e.g. methods returning `CompletableFuture<T>`).
- [ ] **Interceptors** — a way to hook into every request/response (auth
      token injection, logging, retry policy) without modifying each
      `@RestClient` interface.
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
