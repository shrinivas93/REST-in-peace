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
- [x] **Multipart/file upload** — `@Multipart` on a method builds a
      `multipart/form-data` body from its `@Part`/`@PartMap`-annotated
      parameters instead of `@Body`'s JSON/raw-string one, via Unirest's
      `MultipartBody`. `@Part` supports `String` (a form field), `File`,
      `byte[]`, and `InputStream` (all sent as a file part - `@Part`'s
      `fileName` names a `byte[]`/`InputStream` part or overrides a `File`'s
      own name, defaulting to the part's field name). `@PartMap` on a
      `Map<String, ?>` parameter adds one part per entry for a set of names
      not known until runtime, mirroring `@QueryMap`/`@HeaderMap`; a `null`
      map or `null` entry value is skipped, and at most one `@PartMap`
      parameter per method is allowed. Wrap a `File`/`byte[]`/`InputStream`
      entry value in `PartValue.of(value, fileName)` to send it under a name
      other than its map key, since a `@PartMap` entry has no per-entry
      `fileName` attribute the way a fixed `@Part` does. `@Multipart` and a `@Body` parameter
      are mutually exclusive on one method; a `@Part`/`@PartMap` with no
      `@Multipart`, a `@Multipart` with no `@Part`/`@PartMap`, a wrong-typed
      `@Part`, a non-`Map` `@PartMap`, and `@Multipart` on a
      non-body-supporting HTTP method are all validation errors.
- [x] **Response headers** — `RipResponse<T>` (or
      `CompletableFuture<RipResponse<T>>` for an async method) wraps `T`
      with the response's status code and headers, for a method that needs
      more than just the body. `T` is decoded by the same rules as a plain
      return type; `RipResponse<T>` only ever wraps a successful response -
      a non-2xx status still throws `RestInPeaceHttpException` rather than
      being wrapped. `getHeader(name)` looks up a header case-insensitively
      and returns its first value; `getHeaders()` returns every value as a
      `Map<String, List<String>>`. A raw `RipResponse` with no type
      parameter, or one with an unsupported type parameter (same rule as
      `CompletableFuture<T>`), fails validation.
- [x] **Per-call timeout and per-client config** — `@Timeout(connectMillis,
      readMillis)` overrides the connect/read timeout for one method's
      calls only; `RipClientConfig` (passed to `RIP.getClient(Class,
      RipClientConfig)`) overrides base URL, connect/read timeout, and
      proxy for one client, for an environment that differs from every
      other client's. Precedence, most specific first: `@Timeout` (or an
      absolute method URL) beats `RipClientConfig`, which beats the shared
      client's own configured default. Setting a timeout or proxy on
      `RipClientConfig` gives that client its own dedicated Unirest client
      instance instead of sharing the app-wide static one; a config with
      only a base URL keeps sharing it. Everything else `kong.unirest.Config`
      exposes (TLS, connection pooling, the JSON `ObjectMapper`, ...) is
      configured directly on `kong.unirest.Unirest`'s shared client rather
      than wrapped by RIP - deliberately, to avoid owning security-sensitive
      settings (`verifySsl`, mutual TLS) or duplicating a mechanism RIP
      already has a better answer for (default headers, via
      `HeaderInterceptor`).
- [ ] **A `MockInterceptor`/test double** — let consumers unit-test their
      `@RestClient` interfaces without hitting real HTTP.

Items below are from a full-codebase gap analysis and feature brainstorm
(2026-09-01), grouped as found: concrete gaps/bugs in the current code,
missing table-stakes features other declarative REST clients have, and
bigger ideas that would make this library stand out rather than just catch
up.

- [x] **Binary/file downloads** — `byte[]` (or `CompletableFuture<byte[]>`/
      `RipResponse<byte[]>`) decodes a response as exact bytes instead of
      corrupting it through the old always-`String` path. `File` with a
      `@Destination File` parameter streams straight to disk instead of
      buffering into a `byte[]`, for both sync and `CompletableFuture<File>`
      methods. A `DownloadProgressListener` parameter (RIP's own type, not
      Unirest's `ProgressMonitor`) reports `bytesWritten`/`totalBytes` as
      the response streams in. On the upload side, an
      `UploadProgressListener` parameter on a `@Multipart` method reports
      progress per `File`/`InputStream` part (`String`/`byte[]` parts are
      written in one shot and not reported). A non-2xx response still
      throws `RestInPeaceHttpException` with the error body decoded as
      text, and a `File` destination is left untouched rather than written
      with error content. `RipResponse<File>` is intentionally not
      supported - use a plain `File` return with `@Destination` instead.
- [x] **Path values weren't percent-encoded** — `resolvePathParams` now
      runs each `@PathParam` value through `URLEncoder` (then turns its `+`
      for space into `%20`, matching Unirest's own path-segment encoding)
      before substituting it into the URL template, so a `/`, `?`, `#`, or
      a space in the value lands as literal content of that one path
      segment instead of producing a broken or subtly wrong URL. Turned out
      `@QueryParam`/`@QueryMap` values were already safe - Unirest's own
      `queryString(...)` URL-encodes them - so only path substitution
      needed the fix.
- [x] **No multi-value query parameters** — `@QueryParam`/a `@QueryMap`
      entry now dispatches to Unirest's `queryString(String,
      Collection<?>)` overload when the value is a `Collection`, repeating
      the param once per element (`?tag=a&tag=b`) instead of sending one
      mangled `toString()` value.
- [x] **`@Url`: a full dynamic URL as a parameter** — a `String` parameter
      annotated `@Url` is used as the method's entire URL verbatim, for a
      pagination `next` link or a HATEOAS action link that isn't a fixed
      template. Bypasses `@BaseUrl`/a runtime base URL/`@PathParam`
      entirely (there's no template left for them to apply to);
      `@QueryParam`/`@HeaderParam`/etc. still work normally. Only valid
      alongside an HTTP method annotation with no static `value()` -
      combining the two fails validation. Finally consumes the
      `HTTPRequestParam.URL` enum value that had sat reserved and unused.
- [x] **`ObjectMapper` is a silent dependency** — `RIP.setObjectMapper(...)`
      sets a custom mapper for every client sharing the app-wide static
      Unirest client, and `RipClientConfig.builder().objectMapper(...)`
      sets one for a single `RipClientConfig`-configured client whose own
      dedicated Unirest instance `RIP.setObjectMapper(...)` can't reach
      (there was previously no way at all to customize that client's
      mapper, through RIP or around it). A response that fails to decode
      because no mapper is configured at all now throws
      `RestInPeaceException` naming the problem, instead of a bare
      `kong.unirest.UnirestConfigException`. README documents the default
      (Gson-backed `kong.unirest.JsonObjectMapper`, no Jackson dependency
      shipped) and both ways to override it.
- [x] **`@Headers`** — static, method-level fixed headers
      (`@Headers({"Cache-Control: no-cache"})`), separate from the existing
      dynamic `@HeaderParam`/`@HeaderMap`. Each entry is split on its first
      `:` with whitespace trimmed around both sides, so `"Name:Value"`,
      `"Name : Value"`, and `"Name    :     Value"` are all equivalent; an
      entry with no `:` or an empty name fails validation.
      `@HeaderParam`/`@HeaderMap` win over a `@Headers` entry of the same
      name, applied via Unirest's `headerReplace` since the per-call value
      is more specific than the always-on method annotation.
- [ ] **Form-urlencoded bodies** — `@FormUrlEncoded` + `@Field`/`@FieldMap`,
      for OAuth token endpoints and classic HTML forms (currently only
      JSON/raw-string via `@Body` or multipart).
- [ ] **Response caching** — honoring `ETag`/`If-None-Match`/`Cache-Control`
      instead of hitting the network every time.
- [ ] **Compile-time proxy generation instead of a JDK dynamic proxy** — an
      annotation processor that generates a real class implementing each
      `@RestClient` interface at build time (like Dagger/MapStruct do)
      instead of `Proxy.newProxyInstance` + reflection at runtime. Makes the
      library GraalVM native-image friendly out of the box with zero
      reflection config, slightly faster startup, and IDE-navigable
      generated source. The single biggest available differentiator -
      Retrofit/Feign are both stuck with runtime proxies for legacy reasons;
      a from-scratch library doesn't have to be. Design:
      `docs/design/compile-time-proxy-generation.md`.
      - [x] **Step 1 (minimal subset) landed**: `RestClientProcessor`
            generates `<Interface>_RipImpl` for an interface whose methods
            are all a single fixed HTTP verb with only
            `@PathParam`/plain `@QueryParam` params and a
            `void`/`String`/non-generic-POJO return type -
            `RIP.getClient(...)` prefers it over the reflective proxy when
            present. Still goes through the same interceptor/retry/
            error-handling machinery as the reflective path (new
            `RestRequestProcessor.processGeneratedRequest(...)` entry
            point). An interface with any method outside that shape
            (`@Retry`, `@Timeout`, `@Headers`, `@HeaderParam`/`@HeaderMap`,
            `@Body`, `@Multipart`, `@Url`, `@ErrorType`, `@QueryMap`, a
            required/defaulted `@QueryParam`, an async/`RipResponse`/
            `byte[]`/`File` return type, a nested/private interface, ...)
            is silently left to the reflective proxy in its entirety.
            Remaining steps (full feature parity, compile-time validation,
            a native-image smoke test) are tracked in the design doc's
            rollout plan, not done yet.
      - [x] **Step 2, first slice: `@Timeout`/`@Retry` support**: the
            processor now generates for a method carrying either
            annotation instead of disqualifying it, via new non-reflective,
            literal-argument entry points on `RestRequestProcessor`
            (`applyTimeout(request, connectMillis, readMillis)`;
            `executeSyncWithRetry(..., hasRetry, times, delayMillis,
            backoffMultiplier, retryOnStatus)`) alongside its existing
            `Method`-based ones. Also fixed a real bug this surfaced:
            `@Retry`/`@Timeout`/`@Headers`/`@ErrorType` were never actually
            checked for by step 1's disqualification logic (only
            parameter-level features were), so a method combining the
            supported shape with any of the first two would have silently
            generated an implementation that dropped the annotation's
            behavior entirely - `@Headers`/`@ErrorType` are now explicitly
            disqualifying too, closing the same gap for them (still
            unsupported, correctly falling back). See the design doc's
            §9.4.
      - [x] **Step 2, second slice: full header/query/body/URL/error-type
            support**: `@Headers`, `@HeaderParam`, `@HeaderMap`,
            `@QueryMap`, required-or-defaulted `@QueryParam`/`@HeaderParam`,
            `@Body`, `@Url`, and `@ErrorType` are all now generated for.
            Replaced the single `processGeneratedRequest` entry point with
            a set of smaller non-reflective primitives on
            `RestRequestProcessor` that generated code calls in sequence -
            the growing single-call design from step 1 would have
            ballooned past 25 parameters. Also decoupled `@ErrorType`
            handling from `Method` throughout `RestRequestProcessor`
            (a simplification for the reflective path too), and fixed a
            real generated-code bug where a `@Url` parameter named `url`
            collided with the generator's own local variable of the same
            name. Still unsupported: `@Multipart`/`@Part`/`@PartMap`, a
            `DownloadProgressListener`/`UploadProgressListener`/
            `@Destination` parameter, and every non-`String`/POJO return
            type. See the design doc's §9.5.
- [ ] **A pluggable `CallAdapter`-style return-type system** — return types
      are currently hardcoded in `RestRequestProcessor` (String/void/POJO/
      `CompletableFuture`/`RipResponse`). Extracting that into a small
      adapter interface would let someone add Kotlin `suspend fun` support,
      RxJava's `Single`/`Observable`, or Reactor's `Mono`/`Flux` as a
      separate optional module, without RIP itself depending on any of them
      or bloating the core.
- [ ] **Idempotency-key support baked into `@Retry`** — generate a stable
      `Idempotency-Key` header once per logical call and keep it constant
      across all retry attempts (opt-in, e.g. `@Retry(idempotent = true)`).
      Solves a real distributed-systems hazard (a POST that succeeded
      server-side but whose response was lost, then gets blindly retried)
      that most REST clients don't handle at all.
- [ ] **Circuit breaker / bulkhead per client** — a natural extension of
      `RipClientConfig`: stop hammering a downstream that's clearly down,
      the natural next step after retry and timeout.
- [ ] **A pre-built `MetricsInterceptor`** — Micrometer-style counters/timers,
      following the exact pattern `LoggingInterceptor`/`CorrelationIdInterceptor`
      already established. Cheap to build, immediately useful, zero new
      dependency if done as a `Consumer`-based sink like `LoggingInterceptor`.
- [ ] **A pagination helper** — an annotation or small utility that follows a
      `next`/cursor field automatically and hands back a lazy
      `Iterator`/`Stream` of pages, using the `@Url` mechanism above under
      the hood.
- [ ] **Spring/Micronaut integration module** — auto-register every
      `@RestClient` interface found on the classpath as a bean, the way
      OpenFeign integrates with Spring Cloud. This is what actually gets a
      library adopted broadly rather than used standalone.
- [ ] **(Low priority) Fix branch protection on `master`** — repo process,
      not a library feature. A ruleset requiring a pull request before
      merging was set up on `master`, but the bypass entry for the release
      automation didn't work on the first attempt (a role-based
      "Repository admin" bypass doesn't cover a push made as the
      `github-actions[bot]` app - adding the GitHub Actions app itself to
      the bypass list was what actually worked) and the ruleset has since
      been disabled entirely. Needs a proper, verified setup - PRs required
      into `master`, with a working bypass for `release.yml`'s own
      version-bump/tag push - before it's turned back on for real.
