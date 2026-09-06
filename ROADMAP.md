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
            doesn't use the extension.
      - [x] **Follow-up: two correctness fixes found by re-reading the
            implementation.** `MockRestServer.routes` was a bare
            `ArrayList`, unlike the already-`synchronized`-wrapped `queue`
            and `recorded` fields - a route registered (`.on(...)`) while a
            prior async (`CompletableFuture`) request from the same test
            was still being served raced a plain `ArrayList` read against a
            write. Wrapped in `Collections.synchronizedList(...)` to match
            the other two fields, with `reset()` and the route-matching
            loop in `handle(...)` both now synchronizing on it explicitly
            (a `synchronizedList`'s iteration still needs external
            synchronization - wrapping alone isn't enough). Not covered by
            a new test - a race condition doesn't have a deterministic
            repro, so this is a code-inspection fix verified by matching
            the existing pattern, not a red-then-green test.
            Separately, `MockResponse.header(name, value)` stored into a
            `Map<String, String>`, so calling it twice for the same name
            silently replaced the first value instead of adding a second -
            unlike `RecordedRequest`/`RipResponse`, both of which already
            support a header repeating (e.g. multiple `Set-Cookie`
            headers). Changed to `Map<String, List<String>>`, appending on
            each call; `writeTo` now calls the underlying
            `HttpExchange`'s `Headers.add(...)` per value instead of
            `.set(...)`. Covered by a new test exercising two `.header(...)`
            calls for the same name through a real request/response round
            trip.
      - [x] **Follow-up: the two must-have gaps from the triage below.**
            `MockRestServer`'s own javadoc claims `@Retry`, `@Timeout`,
            and every registered `RequestInterceptor` "run completely
            unmodified" through it - these closed the two cases that
            couldn't actually be exercised:
            - `MockResponse.connectionFailure()` closes the connection
              before sending any response, instead of returning an HTTP
              status - proving RIP's "no response at all" error path (a
              `kong.unirest.UnirestException` thrown directly, never
              wrapped in `RestInPeaceHttpException`) behaves as
              documented, and that `@Retry` treats it as unconditionally
              retryable regardless of `retryOnStatus`. Verified Apache
              HttpClient's own internal `NoHttpResponseException` retry
              (visible in test output as several automatic retries against
              the same closed connection) doesn't interfere - RIP's own
              retry logic and single-shot calls both still see the correct
              final outcome.
            - `MockResponse.delay(millis)` sleeps before sending the
              response, to simulate a slow server - the only way to prove
              `@Timeout(readMillis = ...)` (or
              `RipClientConfig.readTimeoutMillis(...)`) actually fires,
              rather than assuming it does because the annotation is
              present. `RipIntegrationTest` already hand-rolled this exact
              pattern (a raw `HttpServer` handler with `Thread.sleep(...)`)
              to test `@Timeout` - this formalizes it as a first-class
              `MockRestServer` capability instead of requiring every
              consumer to duplicate that setup themselves.
      - [x] **Follow-up: fixed the `on`/`enqueue` interaction bug, then
            per-route enqueue and flaky mode.** `enqueue(...)`'s own
            javadoc claimed it was "the way to script a sequence of
            responses to the same endpoint," but that was only true for a
            path with no route registered via `on(...)` at all - route
            matching happens unconditionally before the queue is ever
            consulted, so a route always shadowed it completely for that
            path. Fixed by giving each route its own one-time-response
            queue: `MockRestServer.enqueueFor(httpMethod, pathTemplate,
            response)` scripts a response for a route already registered
            via `on(...)`, consumed before that route's sticky response -
            so "fail twice then succeed forever" can now be expressed for
            a route that also has a sticky final answer, which was
            previously impossible to combine. `onFlaky(httpMethod,
            pathTemplate, failuresBeforeSuccess, failureResponse,
            successResponse)` is sugar on top of the same mechanism
            (register the sticky success, then call `enqueueFor` with the
            failure that many times) - the actual "flaky mode" ask.
            `enqueue(...)`'s javadoc now correctly documents the
            limitation instead of overpromising.
      - [x] **Follow-up: `on(...)` upsert instead of append, plus
            `remove(...)`, closing a related dead-code footgun.**
            Registering the same `(httpMethod, pathTemplate,
            requiredQueryParams)` route twice used to append a second,
            permanently-shadowed `Route` (the first registration always
            wins, since routes match in registration order) - silently
            dead code, and no way to change or remove a route's behavior
            mid-test without a full `reset()`, which also wipes queued
            responses and recorded-request history. `on(...)` now
            replaces the existing route in place (same list position, so
            precedence relative to other routes is unaffected) when
            called again with the same key; `MockRestServer.remove(httpMethod,
            pathTemplate)` removes a route outright (returning `false` if
            none matched), for a test that wants an endpoint to stop
            being covered by any route rather than replacing what it
            returns.
      - [x] **Follow-up: matching on request headers or body content.**
            `MockRestServer.on(httpMethod, pathTemplate, matcher,
            response)` takes a `Predicate<RecordedRequest>` checked
            alongside the path/method match, for a constraint the
            `requiredQueryParams` overload can't express - a header value
            (`request -> "v2".equals(request.getHeader("X-Api-Version"))`)
            or the request body
            (`request -> request.getBody().contains("premium")`). Kept as
            a general predicate rather than a second `requiredHeaders`
            map (which would've meant two same-typed `Map` parameters on
            one overload) - one mechanism covers headers, body content,
            or any combination, instead of needing a new parameter for
            each. Deliberately excluded from `on(...)`'s upsert and from
            `enqueueFor`/`remove`'s lookup: two arbitrary `Predicate`s
            can't be compared for equality the way a `requiredQueryParams`
            map can, so re-registering with a matcher always appends
            rather than replacing.
      - [x] **Follow-up: decoded multipart-part access on
            `RecordedRequest`.** `getParts()` decodes a
            `multipart/form-data` body into individual `Part`s (name,
            optional file name, optional content type, content), for
            asserting on what a `@Multipart` method actually sent instead
            of substring-matching the raw encoded body. Required a
            correctness fix underneath: `RecordedRequest` previously
            captured the body as a single UTF-8-decoded `String` at
            capture time - lossy for a `@Multipart` request's binary parts
            (a `byte[]`/`InputStream`/non-text `File` part), since the raw
            bytes were already gone by the time anything tried to read
            them back. Now captures raw `byte[]` instead, with `getBody()`
            decoding to UTF-8 on demand (unchanged observable behavior for
            a text body) and a new `getRawBody()` for binary-safe access.
            The multipart parser itself is hand-written (the JDK has none
            built in): finds the boundary from the `Content-Type` header,
            splits the raw bytes on it, and parses each part's
            `Content-Disposition`/`Content-Type` sub-headers - verified
            directly against Unirest's own real wire format (not assumed),
            passing on the first attempt.
      - [x] **Follow-up: verification sugar - `countOf(httpMethod,
            pathTemplate)`.** Answers "was this endpoint called, and how
            many times" without manually filtering
            `getRecordedRequests()` or looping over `takeRequest()`.
            Deliberately a plain `int`-returning primitive a test wraps in
            its own `assertEquals(...)`, rather than a `server.verify(...)`
            assertion DSL - this library hasn't added a custom assertion
            framework anywhere else, and the roadmap's own original
            phrasing for this item was just a sketch, not a committed
            API shape. Reuses `Route`'s private path-template-to-`Pattern`
            compilation (accessible from the enclosing `MockRestServer`
            class, since a nested class's private members are visible to
            its enclosing class in Java) rather than duplicating that
            logic.
      - [x] **Follow-up: route-coverage assertion -
            `getUnhitRoutes()`.** Returns every registered route that
            hasn't matched any recorded request yet, as `"METHOD path"`
            strings - catches a route left registered after the
            code path that used to exercise it was removed, which
            otherwise causes no failure at all. Each `Route` now tracks
            whether it's ever been selected as a match (a `volatile
            boolean`, flipped in `handle(...)` alongside sending its
            response - a one-directional flag needs no stronger
            synchronization than that), reported back as a plain
            `"METHOD pathTemplate"` string since `Route` itself is
            private and can't be handed out directly. With this, every
            item from the original "good to have" tier is now done -
            only the "not needed now" tier remains, unless real usage
            surfaces a reason to reconsider one:
            - **Not needed now** - niche or speculative; no known use
              case yet:
              - Multi-segment wildcard paths (`/orders/**`), not just
                single-segment `{name}`.
              - Chunked/delayed response body writing (today's `writeTo`
                does one `OutputStream.write` for the whole body), so a
                `DownloadProgressListener`-consuming test could
                deterministically observe more than one progress
                callback.
              - Auto-dumping recorded requests/responses when a test
                fails.
              - HTTPS/TLS support (loopback plain-HTTP only today -
                relevant only if a client under test hardcodes a TLS
                assumption).
            Same reasoning as the transport-swap option and the first
            follow-up round above applies here too - these are documented
            for when real usage shows a need, not built speculatively.
      - [x] **Follow-up: a second must-have found on a fresh discovery
            pass - `RecordedRequest.getReceivedAt()`.** `@Retry`'s own
            javadoc makes a precise, numeric claim: waiting
            `delayMillis()` between attempts and "multiplying that wait
            by `backoffMultiplier()` after each one." Until now there was
            no way to verify that claim at all through `MockRestServer` -
            only that N attempts happened, never that the gap between
            them actually grew. A silently-broken backoff multiplier
            (hardcoded to `1.0`, or applied in the wrong direction) would
            have passed every existing test. `getReceivedAt()` timestamps
            each request as it's captured (`Instant.now()`, taken after
            the body is fully read, so it reflects "fully received" for
            every request consistently), letting a test measure the gap
            between consecutive attempts directly. Two other fresh-pass
            candidates - simulating a redirect response and a
            gzip-compressed one - were considered but didn't clear the
            same bar: RIP has no code and makes no documented claim about
            either, so there's no broken promise to prove, only an
            unverified reliance on Apache HttpClient's default behavior -
            a real but weaker risk, left as good-to-have-tier candidates
            rather than built here.
      - [ ] **Parked: record/replay against real traffic captured once**
            (pulled out of the "not needed now" list above - explicit
            interest, revisit this before the rest of that tier). A
            "record mode" that proxies real requests through to a real
            base URL, capturing method/path/query/headers/body/response
            into a persisted format (a VCR/WireMock-style "cassette"),
            and a "replay mode" that reads that format back and
            auto-registers matching routes - so a complex third-party
            API's actual responses can be captured once and replayed
            offline/deterministically forever after, instead of
            hand-writing every `MockResponse`. Substantially bigger than
            every other `MockRestServer` follow-up so far - closer to a
            second, small feature (a minimal WireMock) than an
            incremental addition to the existing `on`/`enqueue` model.
            For testing *your* client code against a third-party API you
            don't control - a different use case from the rest of
            `MockRestServer`, which exists to test RIP's own `@Retry`/
            `@Timeout`/interceptor behavior. Not designed yet; a few
            things worth keeping from an expansion pass:
            - **Record mode can likely reuse existing plumbing almost for
              free**, rather than needing a separate proxy class:
              `handle(...)`'s current fallback for an unmatched request
              (the "no response was queued or registered" `500`) is
              exactly the hook point - replace it with "forward to a
              configured upstream base URL and capture the real
              request/response" instead of failing loudly.
            - **Replay of the same request recorded more than once**
              (e.g. a real `503` followed by a real retry's `200`) maps
              directly onto `enqueueFor(...)` (already built) - script
              the exact recorded sequence instead of one fixed response,
              no new mechanism needed.
            - **Replay of the same path distinguished by query params**
              (e.g. pagination) maps directly onto the existing
              `requiredQueryParams` overload of `on(...)` - also no new
              matching logic needed.
            - **A real, non-optional risk**: a cassette can capture
              sensitive headers (`Authorization`, API keys) or response
              fields, and cassette files are the kind of thing that end
              up committed to source control. A redaction/filter hook
              before anything is persisted isn't a nice-to-have, it's a
              precondition for this being safe to ship.
            - A **replay-only first slice** (cassette produced some other
              way, no recording/forwarding mode yet) would prove the
              concept - given the two synergies above, most of what
              replay needs may already exist - before taking on the
              real-network-forwarding and redaction complexity that
              record mode requires.

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
- [x] **Form-urlencoded bodies** — `@FormUrlEncoded` + `@Field`/`@FieldMap`,
      for OAuth token endpoints and classic HTML forms (previously only
      JSON/raw-string via `@Body` or multipart). Mirrors `@Multipart`/`@Part`/
      `@PartMap`'s design end to end: same validation rules (unsupported HTTP
      method, no fields, combined with `@Body`), plus a new one - combined
      with `@Multipart` - since a method now has two mutually exclusive
      body-encoding strategies to pick between. Implemented in both dispatch
      paths (the compile-time generator and the reflective proxy fallback).
      One real design constraint: Unirest 3.x's `field(...)` always upgrades
      a request to `MultipartBody`, so there's no dedicated url-encoded body
      builder to accumulate into the way `@Multipart` does - the encoded
      `name=value` pairs are accumulated into a `List<String>` instead and
      joined into a single `body(...)` call with `Content-Type:
      application/x-www-form-urlencoded` once every parameter is applied. A
      `Collection`-valued `@Field`/`@FieldMap` entry repeats the key once per
      element (`tag=a&tag=b`), the same convention `@QueryParam` uses.
      `MockRestServer`'s `RecordedRequest` gained a matching
      `getFormFields()` decoder, the `@FormUrlEncoded` counterpart to
      `getParts()`, so a test can assert on a decoded field value instead of
      substring-matching the raw encoded body.
- [x] **Response caching** — honoring `ETag`/`If-None-Match`/`Cache-Control`
      instead of hitting the network every time. A pluggable `Cache`
      (`get`/`put`/`evict`/`clear`), attached per-client via
      `RipClientConfig.Builder.cache(Cache)` or globally via
      `RIP.setCache(Cache)`, with `InMemoryCache` shipped as the default
      implementation - zero new dependency. Only ever engages for a `GET`
      whose response carries a `Cache-Control max-age`, an `ETag`, or a
      `Last-Modified` to act on; a response with none of those is never
      stored, matching "honoring what the server says" rather than
      inventing caching the server never asked for. A fresh entry
      (`age < max-age`) is served with zero network call; a stale
      revalidatable entry (has an `ETag`/`Last-Modified`) sends
      `If-None-Match`/`If-Modified-Since` automatically, and a `304 Not
      Modified` refreshes the entry's freshness window and returns the
      previously-cached body without re-decoding anything. `@NoCache` opts
      a single method out even when its client has a cache configured.
      Scoped to `String`/POJO `GET` responses only for this slice - not
      `byte[]`/`File` downloads (`Vary`-aware keying was a known
      simplification here too, since resolved - see the follow-up entry
      below). Lives entirely in
      `RestRequestProcessor` (wrapping the same `Supplier<HttpResponse<B>>`
      seam `@Retry` already wraps) rather than the `RequestInterceptor`
      abstraction, since caching needs to skip the network call entirely
      or splice a cached body into a `304` - strictly more than an
      interceptor's "add headers or abort" contract allows, by its own
      javadoc. `@NoCache` is the only piece that touches the compile-time
      generator at all (one conditional `markNoCache(...)` call emitted
      right after building the call's `RequestContext`); every other
      dispatch-path difference is already absorbed by the shared processor.
      `MockRestServer`'s existing `on(method, path,
      Predicate<RecordedRequest>, response)` matcher (item 9) turned out to
      be exactly what's needed to script a conditional-GET-aware fixture
      server with no new `MockRestServer` feature; `MockResponse` gained
      one small addition anyway - `notModified()`, a `304` shorthand
      mirroring `noContent()`'s `204` one - since the new feature
      specifically produces and expects `304`s often enough to be worth it.
- [x] **Response caching: `Vary` header support** — a stored `GET` response
      whose `Vary` header names request headers (e.g.
      `Vary: Accept-Language`) is never served to a request whose current
      values for those headers differ from the ones snapshotted when it
      was stored, so a cache no longer risks serving the wrong
      language/format variant. `CachedResponse` gained a
      `varyRequestHeaders` snapshot (a new, backwards-compatible
      constructor overload - the existing 4-arg one still works, defaulting
      to "no `Vary`"), captured from the outgoing request's own headers via
      Unirest's `HttpRequest.getHeaders()` at store time - so this only
      sees header values the calling code set itself (`@HeaderParam`/
      `@HeaderMap`/`@Headers`), not ones a lower transport layer might add
      later (e.g. `Accept-Encoding` for compression negotiation), a
      documented gap rather than a silent one. `Vary: *` (meaning "varies
      unpredictably, don't try to cache this via header comparison") is
      never stored at all, same as `no-store`. One real bug caught during
      implementation, not just a missing feature: a cache miss for one
      variant (say, the French version) must never evict a different,
      still-valid variant already stored (the English one) just because
      the French response itself turned out to be non-cacheable - fixed by
      only evicting when the request that just went out was for the
      *same* variant that was already there. Deliberately still a
      single-slot-per-URL store (the newest variant replaces the previous
      one, rather than keeping every variant alive at once) - correctness
      (never serving the wrong variant) over maximizing hit rate on an
      alternating-variant workload; true multi-variant storage would need
      the `Cache` interface itself to hold more than one entry per key,
      which is a bigger change than this slice needed.
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
- [x] **Idempotency-key support baked into `@Retry`** — `@Retry(idempotent =
      true)` generates one `Idempotency-Key` header value per logical call
      and holds it constant across every retry attempt (Stripe/PayPal/Adyen/
      Square's own convention), solving the real distributed-systems hazard
      `@Retry`'s own javadoc already called out: a `POST` that succeeded
      server-side but whose response was lost in transit, then gets blindly
      retried, risking a duplicate charge/order. Default `false` - zero
      behavior change for every existing `@Retry` usage. The smallest of the
      recent additions by far: unlike form-urlencoded/caching, this needed
      no new package or public type - one boolean field on the existing
      `Retry` annotation, and it slots into the exact seam
      `applyFixedHeaders` already occupies (set once, before any attempt,
      on the same `HttpRequest` object every retry re-sends), so the header
      stays identical across attempts with no retry-loop-aware logic at
      all. `@NoCache`-style precedent followed for the codegen side too:
      `RestClientProcessor` only emits the
      `applyIdempotencyKeyIfNeeded(...)` call when `idempotent = true`,
      otherwise the generated code doesn't mention it. No `MockRestServer`
      changes needed - `RecordedRequest.getHeader("Idempotency-Key")` and
      `getRecordedRequests()` (both pre-existing) are enough to assert the
      key is identical across every recorded attempt.
- [ ] **Circuit breaker / bulkhead per client** — a natural extension of
      `RipClientConfig`: stop hammering a downstream that's clearly down,
      the natural next step after retry and timeout.
- [x] **A pre-built `MetricsInterceptor`** — times every request and reports
      it, once its response comes back, to a small `MetricsSink` interface
      (`recordCall(httpMethod, url, status, durationMillis)`) - the metrics
      counterpart of `LoggingInterceptor`, following the exact same
      "bring your own sink" shape so RIP doesn't depend on Micrometer or any
      other metrics library. A consumer wires the sink to Micrometer, a
      homegrown registry, or a `System.out` printer for local debugging.
      Same start-time-stashed-on-`RequestContext` mechanism
      `LoggingInterceptor` already uses; same registration-order tradeoff
      documented on `RequestInterceptor` (register first to measure total
      call overhead, last to measure only the network call). One documented
      gap, inherited from the interceptor contract itself rather than
      introduced here: a call that fails at the transport level (connection
      refused, timeout with no response at all) never reaches
      `afterResponse`, so it produces no sample - only a call that actually
      gets a response is measured. A `@Retry`'d call reports one sample per
      attempt, since every attempt gets its own `afterResponse` notification
      - verified with a dedicated test asserting three samples
      (`503, 503, 200`) for a call that fails twice before succeeding.
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
