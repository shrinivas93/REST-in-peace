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
- [ ] **CONTRIBUTING.md + CHANGELOG.md** — light process maturity now that
      the project has real, automated releases going out.
- [ ] **Maven Central publishing** — full setup (groupId change to
      `io.github.shrinivas93`, POM metadata, GPG signing, publish workflow)
      is built and verified but paused pending account-level setup (Sonatype
      Central Portal account, GPG key, publishing token). Preserved on the
      `feature/maven-central-publishing` branch (was PR #9, closed
      without merging) — pick it back up when ready.
- [ ] **Refactor to idiomatic Java 8** — most of the codebase predates this
      roadmap's own Java 8 usage (lambdas showed up with interceptors and
      `Supplier`-based headers, but plenty of earlier code still uses
      pre-8 patterns). Sweep it for places lambdas, method references,
      functional interfaces, and streams would replace imperative loops or
      anonymous classes — e.g. the `for` loops in `RestRequestProcessor`
      and `RestClientValidator`. Must stay within the actual Java 8 API
      surface, not just source/target 8 — verify locally with
      `mvn -Dmaven.compiler.release=8 clean test`, since a newer local JDK's
      compiler silently allows post-8 APIs (like `List.of`) that fail CI's
      real Java 8 build.
