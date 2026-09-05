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
- [x] **A `MockRestServer` test double** — let consumers unit-test their
      `@RestClient` interfaces without hitting real HTTP. Originally scoped
      as "a `MockInterceptor`" in this item's own title, but
      `RequestInterceptor` turned out to be architecturally unable to do
      this - it's a pure observer (`beforeRequest`/`afterResponse`), with no
      way to short-circuit the real network call and substitute a canned
      response. Implemented instead as `com.shri.restinpeace.mock.MockRestServer`
      - a real, local `com.sun.net.httpserver.HttpServer` (the same one this
      project's own integration test suite already uses), not a fake
      transport swapped in underneath Unirest. Deliberate tradeoff: a
      transport-swap approach (implementing Unirest's own `Client`/
      `AsyncClient` SPI, ~20 methods across two interfaces) would run
      without real sockets, but leaks a third-party SPI into RIP's public
      surface, skips real request serialization entirely (the swap point
      sits above where Unirest turns a request into wire bytes), and needs
      two parallel fake implementations kept in lockstep with every future
      return-type shape. A real embedded server has none of those costs -
      `@Retry`, `@Timeout`, and every registered `RequestInterceptor` all
      run completely unmodified, against both the reflective and
      compile-time-generated dispatch paths - at the cost of using real
      loopback sockets instead of an in-memory fake. `MockRestServer.on(...)`
      registers a sticky response for a method+path (with `{name}`
      placeholder matching); `MockRestServer.enqueue(...)` scripts a
      one-time sequence (e.g. a `503` then a `200`, to prove `@Retry`
      recovers); `RecordedRequest` exposes the path, query params, headers,
      and body of what was actually received; an unmatched request fails
      loudly (a `500` with a clear message) instead of silently succeeding
      for the wrong reason. A transport-swap version remains a possible
      future addition if real usage ever shows the socket overhead is
      actually a problem - not before.
      - [x] **Follow-up: routing/ergonomics enhancements.** Four of the
            enhancement ideas noted when this shipped, picked for being
            cheap and immediately useful rather than speculative:
            `MockRestServer.reset()` clears queued responses, registered
            routes, and recorded requests, so one server can be reused
            across a test class's methods instead of paying to start a new
            one each time; `MockResponse.json(Object)` serializes a plain
            object with the same Unirest `ObjectMapper` RIP itself
            delegates to, instead of hand-writing JSON strings;
            `MockRestServer.on(httpMethod, pathTemplate, queryParams,
            response)` adds an optional exact-match query-param constraint
            to route matching, for an endpoint that behaves differently by
            query param (e.g. `?status=active` vs. `?status=archived`);
            and `MockRestServerExtension`, a JUnit 5 extension registered
            with `@ExtendWith`, starts one server per test class,
            `reset()`s it before each test, and resolves it as a test (or
            `@BeforeEach`) method parameter - removing the
            `@BeforeEach`/`@AfterEach` `MockRestServer.start()`/`.close()`
            boilerplate, and, because the server's base URL is now stable
            for the whole class, the need to rebuild a `@RestClient` proxy
            per test too. Sharing one server across a class's tests isn't
            safe under parallel test execution within that class - not a
            concern for the common case, but worth knowing. The extension
            required promoting `junit-jupiter` from `test` to `provided`
            scope in `pom.xml`, since a main-source class now implements
            JUnit 5 extension interfaces - verified non-transitive (a
            consumer's own `dependency:tree` shows no `junit-jupiter`
            entry at all), so this costs nothing for a consumer who
            doesn't use the extension. Remaining enhancement ideas (slow/
            broken-connection simulation, multipart part introspection,
            a "flaky mode", auto-logging on failure, record/replay) are
            still deliberately deferred, per the same "don't build the
            expensive version before real usage shows you need it"
            reasoning as the transport-swap option above.

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
- [x] **Compile-time proxy generation instead of a JDK dynamic proxy** — an
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
      - [x] **Step 2, third slice: `@Multipart`/`@Part`/`@PartMap`/
            `UploadProgressListener` support**: the smallest slice so far,
            since the reflective path's own part-application methods were
            already non-reflective - mostly a matter of widening their
            visibility and adding `beginGeneratedMultipart` as the
            generated-code counterpart of the reflective path's
            `multiPartContent()` cast-and-call. Generalized the `@Url`
            codegen-safety fix from the previous slice into an explicit
            rule: any parameter/return kind whose generated code depends on
            another feature also being present must be cross-checked in
            `toSupportedMethodModel`, disqualifying the whole method if
            that precondition doesn't hold - applied here for
            `@Part`/`@PartMap`/`UploadProgressListener` needing
            `@Multipart`. Still unsupported: a `DownloadProgressListener`/
            `@Destination` parameter, and every non-`String`/POJO return
            type. See the design doc's §9.6.
      - [x] **Step 2, fourth slice: `byte[]`/`File`+`@Destination`+
            `DownloadProgressListener`/`RipResponse<T>` return types**: the
            first slice to change what a generated method returns, not
            just what it accepts - added `finishGeneratedSyncBytes`/
            `finishGeneratedSyncFile`/`finishGeneratedSyncRipResponse`/
            `finishGeneratedSyncRipResponseBytes` as sibling terminal
            calls alongside `finishGeneratedSync`, one per return-type
            shape, picked at compile time from the interface's declared
            return type. `MethodModel`'s return type went from a bare
            string to a `ReturnModel` (kind + literal type name +, for
            `RipResponse<T>`, `T`'s name) to support this. Extended the
            §9.6 codegen-safety cross-check rule to return types:
            `@Destination`/`DownloadProgressListener` now require a
            `File`-returning method, and exactly one `@Destination`
            parameter is required whenever the return type is `File`.
            Only `CompletableFuture<T>` (async) remains unsupported -
            the last item in §5's table. See the design doc's §9.7.
      - [x] **Step 2, fifth and final slice: `CompletableFuture<T>` (async),
            for every return-type shape** — the last item in §5's table,
            completing step 2's full feature parity. Unlike every earlier
            slice, this doesn't add a new `ReturnKind` case - a
            `CompletableFuture<T>` can wrap any of the existing
            `PLAIN`/`BYTES`/`FILE`/`RIP_RESPONSE` kinds, so
            `RestRequestProcessor` gained one async sibling per existing
            sync terminal method (`finishGeneratedAsync`,
            `finishGeneratedAsyncBytes`, `finishGeneratedAsyncFile`,
            `finishGeneratedAsyncRipResponse`,
            `finishGeneratedAsyncRipResponseBytes`), each reusing the same
            `executeAsyncWithRetry`/`decodeOrThrow` machinery the
            reflective path's own async support already shares.
            `ReturnModel` gained an `isAsync` flag (detected via the same
            type-erasure comparison `RipResponse<T>` detection uses) and
            its `innerTypeName` field was renamed to `decodeTypeName` -
            the class to decode the response body into, which now
            genuinely differs from the method's own return type once
            `CompletableFuture` is involved. `ReturnKind.VOID` was folded
            into `PLAIN` (distinguished by `decodeTypeName` being
            `"void"`) since it needed no separate dispatch case anymore.
            With every item in §5's table now supported, a new
            permanently-unsupported regression interface,
            `GeneratedApiWithListReturn` (a generic `List<String>` return
            type - never decodable via a single `Class<?>` literal), and
            sample-project example replaced the now-obsolete
            `CompletableFuture`-based ones. **Step 2 (full feature parity)
            is now complete.** See the design doc's §9.8.
      - [x] **Step 3: native-image smoke test** — built
            `samples/compile-time-proxy-consumer` into a real GraalVM
            native executable and ran it, the concrete proof the "GraalVM
            native-image friendly out of the box with zero reflection
            config" claim actually holds, rather than an assumption
            resting on "we removed the reflective calls from the
            generated path." The first attempt immediately falsified that
            claim: `RIP.getClient`'s own generated-impl lookup
            (`Class.forName(restClient.getName() + "_RipImpl")`) is itself
            a dynamically-computed reflective call GraalVM's static
            analysis can't resolve, so every single generated class was
            silently unusable under native-image, for any interface, from
            the moment `tryGeneratedImpl` was first introduced (step 1).
            Fixed at the source: `RestClientProcessor` now emits a
            `reflect-config.json` resource alongside every generated
            `<Interface>_RipImpl`, registering exactly the one constructor
            the lookup needs - zero hand-written configuration anywhere in
            the consumer project, keeping the same "zero extra
            configuration" property the rest of this feature already has.
            A new `NativeMain` entry point and `native` Maven profile in
            the sample project, plus a new `native-image-smoke-test` CI
            job, exercise only the fully-covered path (not the reflective
            proxy fallback, which still needs its own hand-written
            `proxy-config.json` under native-image - correct, expected
            behavior for an interface the generator doesn't cover, not a
            bug). See the design doc's §9.9.
      - [x] **Step 4: the compile-testing validation suite** — the exit
            criterion for this whole roadmap item. A new
            `CompileTimeValidator` reimplements every semantic rule
            `RestClientValidator` enforces reflectively (HTTP-method
            count, `@Body`, `@Retry`, `@Timeout`, `@Headers`,
            `@Multipart`, `@QueryMap`/`@HeaderMap`/`@PartMap`,
            `@Destination`, `@Url`, upload/download listeners,
            `CompletableFuture<T>`/`RipResponse<T>` return-type shape)
            against `javax.lang.model` instead of `java.lang.reflect`, so
            a semantically invalid `@RestClient` interface now fails
            **compilation** outright - the same message
            `RestClientValidator` would otherwise only report at the
            first `RIP.getClient(...)` call - instead of silently
            compiling and blowing up on first use. Runs on every
            `@RestClient` interface seen, not only ones within the
            codegen-supported shape, and skips codegen entirely on any
            error found. Deliberately a second, independent
            implementation rather than a shared abstraction with
            `RestClientValidator`, per the design doc's own §7 open
            question - and deliberately does *not* enforce one rule
            (a relative URL needing `@BaseUrl`), since which
            `RIP.getClient(...)` overload ends up used is an inherently
            runtime fact. Also found and fixed a real, if minor,
            regression this same change caused: a long-dormant fixture
            interface (`SampleApi`) had two unused methods purely to hold
            invalid annotation combinations for a different (reflective)
            test, which the new compile-time check now correctly flagged
            - deleted as pure duplication of coverage
            `RestClientValidatorTest` already has via its own nested
            interfaces. A new `CompileTimeValidationTest` proves both
            directions via a real, isolated `javac` invocation
            (`javax.tools.JavaCompiler`, no new dependency): 14 invalid
            interfaces each fail compilation with the expected message,
            and a valid one compiles clean and produces a real generated
            class. See the design doc's §9.10. **All four steps are now
            complete - this roadmap item is done.**
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
