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
- [x] **Retry support** — `@Retry(times, delayMillis, backoffMultiplier,
      retryOnStatus)` re-issues a request that fails with a transport error
      or a matching status code. Works for both synchronous methods (a
      blocking loop) and `CompletableFuture` ones (each retry scheduled on a
      background thread instead of blocking the caller). Every attempt is
      still reported to registered interceptors' `afterResponse`.
- [x] **`@BaseUrl` + relative paths** — a `@RestClient` interface can declare
      a base URL once; methods use a relative path instead of repeating the
      full URL. A method URL that's already absolute ignores `@BaseUrl` and
      is used as-is, so a method can opt out with its own full URL. A
      relative method URL with no `@BaseUrl` on the interface fails
      validation. `@BaseUrl` can itself hold a `{placeholder}`, resolved the
      same as any method URL.
- [x] **Runtime base URL for multi-environment deployments** —
      `RIP.getClient(Class, String)` resolves relative method URLs against a
      base URL supplied at call time instead of `@BaseUrl` on the interface,
      since an annotation value has to be a compile-time constant and can't
      itself hold something environment-dependent (an env var, a config
      value). Takes priority over `@BaseUrl` when both are present, so
      `@BaseUrl` is optional once every relative URL is covered by the
      runtime value. Precedence: absolute method URL, then this override,
      then `@BaseUrl`.
- [x] **Typed error handling** — a non-2xx response always throws
      `RestInPeaceHttpException` (status + raw body), whatever the method's
      return type, instead of flowing through as a normal return value like
      before. `@ErrorType(SomeClass.class)` deserializes the error body into
      that class instead of leaving it as the raw string; a transport
      failure (no response at all) still throws directly, not this
      exception. Required unifying `RestRequestProcessor`'s retry executors
      around `HttpResponse<String>` instead of being generic over the
      success return type, since deciding success-vs-error now has to
      happen after fetching the raw body, not by asking Unirest to
      deserialize into the success shape unconditionally.
- [x] **`@QueryMap`/`@HeaderMap`** — a `Map<String, ?>` parameter annotated
      `@QueryMap`/`@HeaderMap` adds one query param/header per entry, for a
      set of names not known until runtime (a search endpoint's open-ended
      filter set, caller-supplied headers in a multi-tenant app). Combines
      with fixed `@QueryParam`/`@HeaderParam` on the same method. A `null`
      map or a `null` entry value is skipped, not an error. At most one
      parameter per method may carry each annotation, and it must be a
      `Map`, both checked at `RestClientValidator` time.
- [ ] **Multipart/file upload** — `@Body` currently only covers a raw string
      or a whole JSON-serialized object; no `@Multipart`/`@Part` support for
      form/file uploads. Split out from the item above since it needs
      Unirest's `MultipartBody` API and a different request-building path,
      not just another parameter annotation.
- [ ] **Per-call timeout** — no way to override the client's configured
      connect/read timeout for one slow endpoint.
- [ ] **A `MockInterceptor`/test double** — let consumers unit-test their
      `@RestClient` interfaces without hitting real HTTP.
