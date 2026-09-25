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
- [x] **Maven Central publishing** — groupId changed to
      `io.github.shrinivas93`, required POM metadata (name/description/url/
      licenses/developers) added, sources+javadoc jars attached, GPG signing
      and `central-publishing-maven-plugin` wired into a `central` Maven
      profile kept separate from the always-on GitHub Packages `mvn deploy`
      (#190). `autoPublish=true`, so every tagged release publishes live to
      Central automatically (#190) - no manual step in the Portal UI.
      `release.yml` also auto-bumps the README's illustrative installation
      version on every release and pushes it back through the existing
      "sync master into develop" step, so it can't go stale the way it did
      once, right after this shipped (#194). First real releases:
      v1.0.0.45-v1.0.0.47, all confirmed live on
      [Central](https://central.sonatype.com/artifact/io.github.shrinivas93/rest-in-peace).
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
- [ ] **A pluggable `CallAdapter` return-type system, with Project Reactor
      as the first consumer** — return types were previously hardcoded in
      `RequestExecutor`/`RestClientProcessor` (String/void/POJO/
      `CompletableFuture`/`RipResponse`/`byte[]`/`File`, plus `Page`/
      `Stream`/`Iterator` for pagination). No longer parked: this item's two
      original open questions (the dispatch-path split; Kotlin `suspend fun`
      being out of scope) are both resolved in
      [`docs/design/reactor-call-adapter.md`](docs/design/reactor-call-adapter.md),
      which also verified against the actual code that the compile-time
      codegen path *already* disqualifies any `CallAdapter`-shaped return
      type (e.g. `Mono<T>`) to the reflective fallback, unconditionally,
      with no code change needed - a cleaner resolution than the original
      note expected. **Chunk 2 (the general SPI) has landed**: a small
      `CallAdapter`/`CallAdapterFactory` SPI in `core` (zero new
      dependencies), registered globally via
      `RIP.addCallAdapterFactory`/`removeCallAdapterFactory`/
      `clearCallAdapterFactories` (mirroring the interceptor registry), and
      a dispatch hook in `RequestExecutor.processRestRequest` reusing the
      exact already-genuinely-async `CompletableFuture<T>` path any other
      async call already goes through - an adapter never dispatches its own
      call, only transforms the future RIP already produced, so an adapted
      call is dispatched exactly once through the identical retry/cache/
      circuit-breaker/bulkhead/interceptor pipeline. Also closes a real,
      separately-discovered gap, narrower than the design doc's own
      original sketch (a real deviation, caught during implementation - see
      that doc's Status line): a method returning one of a small, explicit
      set of known-opaque reactive wrapper types by name (Project Reactor's
      `Mono`/`Flux`; RxJava 2/3's `Single`/`Observable`/`Maybe`/
      `Completable`/`Flowable`) now fails validation instead of silently
      attempting to decode the response body directly into that type -
      scoped to exactly those known types rather than every unclaimed
      generic return type, since the broader rule would have broken
      already-working generic-collection decoding (`List<User>`, etc.),
      which reaches the same unchecked generic decode path today.
      **Chunk 3 (real `Mono<T>` support) has also landed**: a new
      `rest-in-peace-reactor` module adds `MonoCallAdapterFactory`, claiming
      any `Mono<T>`-returning `@RestClient` method - `Mono<Void>`,
      `Mono<RipResponse<T>>`, and `Mono<byte[]>` all work identically to
      their `CompletableFuture<T>` equivalents - with
      `RestInPeaceReactor.register()`/`unregister()` as the one-call
      registration entry point. Verified via `StepVerifier`-based tests
      against a real `MockRestServer`: dispatch is eager (already
      in-flight before any subscribe, matching `CompletableFuture<T>`'s
      convention rather than Reactor's usual defer-until-subscribed one),
      disposing genuinely cancels the underlying `CompletableFuture`, and a
      raw `Mono` fails validation the same way an unclaimed `Mono<T>`
      already does. **Chunk 4 (`Flux<T>` support, both flavors) has also
      landed**: `FluxListCallAdapterFactory` claims a plain, non-`@Paginated`
      `Flux<T>` method (decode as `List<T>`, emit item by item via
      `Flux.fromIterable` - no real backpressure, since the whole list is
      already in memory); a new `PaginatedCallAdapter`/
      `PaginatedCallAdapterFactory` SPI in `core` (the pagination-aware
      counterpart of `CallAdapter`/`CallAdapterFactory`, consumed from
      `RequestExecutor.processPaginatedRequest` and validated the same way
      in `ReflectiveRestClientValidator.validatePaginated`) lets
      `FluxPaginatedCallAdapterFactory` claim a `@Paginated Flux<T>` method
      instead - a third, genuinely backpressure-aware return-type-driven
      flattening mode for `@Paginated` alongside `Page<T>` and
      `Stream<T>`/`Iterator<T>`, fetching the next page only once
      `FluxSink`'s own accumulated demand exceeds what's already buffered.
      Needing that new SPI at all (rather than teaching `core`'s
      `PaginationCoordinator` about Reactor types directly, which would
      have broken the "zero Reactor dependency in `core`" invariant) was
      itself a real deviation from the design doc's original "no new
      coordinator logic" assumption. Its existence also exposed two latent
      compile-time gaps, both fixed as part of this chunk: a `@Paginated`
      method returning an adapter-claimable declared type other than
      `Page`/`Stream`/`Iterator` no longer unconditionally fails compilation
      (`void`, `RipResponse<T>`, and `CompletableFuture<T>` remain hard errors;
      a registered adapter might legitimately claim it, invisibly to the
      compile-time processor); and
      `RestClientProcessor` now explicitly disqualifies every
      `@Paginated`/`PaginationStrategy<T>` method from compile-time codegen
      regardless of return type, instead of relying on `Page`/`Stream`/
      `Iterator`'s own generic type arguments to do so "by accident" -
      closing a real bug where a `@Paginated` method returning a plain,
      non-generic type would previously have been silently codegen'd into a
      broken, non-paginating method. RxJava remains an explicit non-goal of
      this rollout (the SPI itself is library-agnostic, but a second
      reactive library needs its own concrete consumer to build against,
      the same "don't guess ahead of a real user" instinct that governed
      pagination and
      circuit-breaker/bulkhead before this).
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
- [x] **Circuit breaker / bulkhead per client** — a natural extension of
      `RipClientConfig`: stop hammering a downstream that's clearly down,
      the natural next step after retry and timeout. Design doc:
      [`docs/design/circuit-breaker-bulkhead.md`](docs/design/circuit-breaker-bulkhead.md) -
      build-your-own default (no new dependency) with a pluggable
      `CircuitBreakerProvider`/`BulkheadProvider` override to delegate to
      resilience4j or any other backend a consumer already runs. All six
      chunks of the doc's §9 rollout plan landed: `CircuitBreakerConfig`/
      `CircuitOpenException`/`CircuitBreakerCoordinator` (chunk 2, both
      `COUNT_BASED` and `TIME_BASED` sliding windows) and
      `BulkheadConfig`/`BulkheadFullException`/`BulkheadCoordinator`
      (chunk 3), both wired into `RipClientConfig.Builder`; async
      (`CompletableFuture`) parity for both, including `RetryExecutor`
      never retrying a `CircuitOpenException` (chunk 4); the
      `CircuitBreakerProvider`/`BulkheadProvider` override SPI to delegate
      to resilience4j or any other backend, with no new dependency in
      core's own `pom.xml` (chunk 5); and Spring Boot starter property
      binding under `rest-in-peace.clients.<name>.circuit-breaker.*`/
      `.bulkhead.*`, mirroring the existing timeout/proxy property groups
      (chunk 6). See the design doc's own Status line for every real
      deviation from the original sketch.
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
- [x] **A pagination helper** — an annotation (`@Paginated`) or, for
      whatever it can't reach, a programmatic `PaginationStrategy<T>`
      escape hatch, following a `next`/cursor field or response header
      automatically and handing back a `Page<T>` (manual) or lazy
      `Stream`/`Iterator` (auto-flattened) of items, reusing the `@Url`
      mechanism above under the hood. Design doc:
      [`docs/design/pagination-helper.md`](docs/design/pagination-helper.md) -
      the two earlier sketches (a fixed `Page<T>` interface with
      `getItems()`/`getNextUrl()`, then a flatter `@Paginated(itemsField,
      nextUrlField | nextCursorField, cursorQueryParam)`) were both parked
      for not being generic enough; this doc replaces them with a design
      built from an exhaustive survey of 46 real-world pagination shapes
      (response header/body, top-level/nested, keyset, arithmetic
      offset/total, and every combination of how the client resends
      state), reusing existing `@QueryParam`/`@PathParam`/`@HeaderParam`/
      `@Body` vocabulary via one new `@PaginationCursor` parameter marker
      rather than inventing parallel carrier annotations, plus a
      `PaginationStrategy<T>` override (mirroring
      `CircuitBreakerConfig`/`CircuitBreakerProvider`'s build-your-own-
      default-pluggable-override shape) for the residue no closed
      annotation vocabulary can ever fully anticipate. Chunked rollout
      plan in the doc's §12; every numbered chunk (2-9) has landed:
      `@Paginated`, `@PaginationCursor`, `Page<T>`, `Stream<T>`/
      `Iterator<T>` lazy auto-flattening, `FULL_URL`/`VALUE`/`ITEM_FIELD`
      (keyset, including N-way composite) pointers via `@QueryParam`/
      `@PathParam`/`@HeaderParam`/`@Body`, `hasMore`/`total`/`totalPages`
      termination signals, RFC 8288 `Link` header `rel="next"` parsing for
      a `RESPONSE_HEADER`-sourced `FULL_URL` pointer, `PaginationAdvance`
      client-driven offset/page-number arithmetic for APIs with no
      server-given pointer at all, the fully programmatic
      `PaginationStrategy<T>` escape hatch, and `MockRestServer.onPages(...)`
      for scripting a page sequence in one test call. Synchronous only -
      a fully async `Page<T>.next()` iteration protocol (§11) remains a
      deliberately-deferred future enhancement, left for if/when real
      usage calls for it, not a numbered rollout chunk.
- [x] **Spring integration module** — auto-registers every `@RestClient`
      interface found on the classpath as a bean, the way OpenFeign
      integrates with Spring Cloud, via the optional
      `rest-in-peace-spring-boot-starter` module (Spring Boot 4.x, Java
      17+). Design doc and full chunked rollout history at
      [`docs/design/spring-boot-starter.md`](docs/design/spring-boot-starter.md);
      sample consumer at
      [`samples/spring-boot-consumer`](samples/spring-boot-consumer).
      Published to GitHub Packages alongside core as of `v1.0.0.35` - the
      two share one version and release cadence; see that doc's §8.1.
      Micronaut integration remains a separate, unstarted item below since
      its compile-time DI model needs a structurally different integration
      than Spring's runtime classpath scanning.
- [ ] **Parked: a Micronaut integration module** — split out from the item
      above once the Spring integration shipped. Needs to cooperate with
      `RestClientProcessor`'s own compile-time codegen rather than port the
      Spring starter's `ImportBeanDefinitionRegistrar`-based approach, since
      Micronaut's own DI is itself compile-time - real, new design work,
      not a port. Parked rather than started: Spring remains the dominant
      Java framework by a wide margin, and Micronaut's adoption is real but
      niche, concentrated in teams specifically optimizing for
      startup/memory (serverless, GraalVM native-image, container
      density) - the existing Spring Boot starter almost certainly serves
      the bulk of realistic consumers already. Revisit if a concrete
      Micronaut consumer actually asks for it.
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
- [x] **Codecov integration** — `codecov/codecov-action@v5` added to both
      `ci.yml` and `spring-boot-starter-test.yml` (the latter has since been
      folded into `ci.yml`'s own `spring-boot-starter` job; the action
      itself is now on `v7.1.1`, kept current via Dependabot), uploading
      each module's
      already-generated `jacoco.xml` (from the JaCoCo item above) tagged
      with a `core`/`spring-boot-starter` flag so the two stay distinguishable
      in the Codecov UI rather than overwriting one report with the other.
      `fail_ci_if_error: false` on both, so a Codecov outage or a
      misconfigured token degrades to "no coverage uploaded this run"
      instead of failing CI outright - the plain-artifact upload from the
      JaCoCo item is unaffected either way. Needed a `CODECOV_TOKEN`
      repository secret, generated from the repo's own Codecov settings
      page after enabling it there - account-level setup only the repo
      owner could do, unlike everything else in this item. A
      [![codecov](https://codecov.io/gh/shrinivas93/REST-in-peace/branch/develop/graph/badge.svg)](https://codecov.io/gh/shrinivas93/REST-in-peace)
      badge now lives in `README.md` alongside the CI badge.

## Excavation ideas (2026-09-14) — proposed, not yet designed or scoped

A second full codebase excavation (mock server, internal encoders, both
dispatch paths, pre-built interceptors, the Spring starter, samples, and the
existing roadmap items above) turned up these as candidates - numbered here
so they can be discussed/scoped/picked off individually rather than lost in
chat history. None are started; sizes below are a rough guess, not a
commitment.

**Quick wins (small, self-contained):**

- [x] **E1. `RequestContext.toCurlCommand()`** — `RequestContext` already
      knows the method, URL, headers, and (since `RequestContext.getBody()`
      shipped above) the body. One method turns a failed call into a
      copy-pasteable `curl` repro for logs/bug reports. Shipped with a
      six-level verbosity option, `toCurlCommand(RequestContext.CurlVerbosity)`:
      `NONE` (default, no extra flag), `VERBOSE` (curl's own `-v`,
      request/response headers), `VV`/`VVV`/`VVVV` (repeated `-v`,
      escalating through per-line timestamps + a transfer/connection id,
      then a raw hex-offset dump of the header/body bytes on the wire, then
      curl's own internal DNS/TCP/connection-pool/multi-handle engine
      tracing), and `TRACE` (curl's own `--trace-ascii - --trace-time`, a
      full, per-line-timestamped wire-level trace including both bodies).
      RIP doesn't invent its own verbosity scheme; each level just picks
      which real, current `curl` flag(s) to include.
      **Revised mid-implementation** after direct testing turned up a real
      surprise: an initial pass (verified only against the sandbox's then-
      installed curl 8.5.0) concluded `curl`'s `--verbose` was a plain
      boolean with no `ssh`-style leveled behavior at all - `-v` and
      `-vvvv` produced byte-identical output on that version. After
      upgrading the sandbox's `curl` to the actual latest release (8.22.0,
      built from source since apt's repo only carried 8.5.0) and re-testing,
      repeated `-v` turned out to genuinely scale verbosity on the current
      release (27/31/116/215 output lines for `-v`/`-vv`/`-vvv`/`-vvvv`
      against the same request, confirmed to cap at four repeats - a fifth
      adds nothing further) - so the "no such levels exist" conclusion was
      simply wrong for the version that matters. Added `VV`/`VVV`/`VVVV` to
      cover it, but with an explicit javadoc/doc caveat: unlike `-v`/
      `--trace-ascii`/`--trace-time`, this scaling behavior is **not**
      documented in `curl`'s own `--help`/man page - most likely an
      internal `curl_trc` debug counter responding to how many times `-v`
      was given, not a committed CLI contract, so it could change or
      disappear in a future `curl` release without notice. `TRACE` remains
      the recommended choice over `VVVV` when that stability matters more
      than matching exactly what someone would type by hand.
- [x] **E2. `RestInPeaceHttpException.isRedirect()` + `getRetryAfterMillis()`**
      — `isClientError()`/`isServerError()` covered 4xx/5xx; 3xx had no
      helper. `@Retry` already parsed a response's own `Retry-After`
      (delta-seconds or HTTP-date) internally but discarded it once retries
      were exhausted - the parsed value is now surfaced on the exception too
      (`RetryExecutor.parseRetryAfterMillis` made package-private so
      `ResponseDecoder` can share it instead of duplicating the parsing), via
      a new four-arg constructor (`status, rawBody, errorBody,
      retryAfterMillis`) - the existing three-arg one is unchanged and
      always passes `null`, so no existing caller's behavior changes.
- [x] **E3. Friendlier `MockRestServer` unmatched-request diagnostics** —
      the unmatched-request failure message now appends `"Did you mean:
      METHOD path?"` naming the closest registered route for the same
      HTTP method, computed by a hand-rolled Levenshtein edit-distance
      over the routes' stored path templates (no new dependency). No
      suggestion is added when no route at all is registered for that
      method, so the existing bare message is unchanged in that case.
- [x] **E4. Auto-report `getUnhitRoutes()` in `MockRestServerExtension`** —
      `reportUnhitRoutes()` opts into printing every unhit route to
      `System.err` at `afterAll`, turning dead test setup into a free
      signal instead of requiring `getUnhitRoutes()` to be called by hand.
      Purely a diagnostic - never fails the test class. Only takes effect
      with the `static @RegisterExtension` field style, since
      `@ExtendWith(MockRestServerExtension.class)` has JUnit construct the
      extension itself via the no-arg constructor with no way to call
      `reportUnhitRoutes()` first - documented as a caveat alongside the
      class's existing static-field registration note.

**Medium features (new user-facing value):**

- [x] **E5. Stale-while-revalidate caching mode** — `Cache-Control:
      stale-while-revalidate=N` now serves a stale entry immediately for up
      to `N` further seconds instead of blocking on a synchronous
      revalidation round trip, refreshing the entry in the background for
      the next call. The async (`CompletableFuture`) path gets this for
      free by chaining the refresh onto the same future, never blocking the
      response already being returned; the sync path uses a small,
      lazily-created internal daemon-thread pool instead. A failed
      background refresh is silently swallowed - the stale entry just keeps
      serving until it ages out of its own window too. `CachedResponse`
      gained a new six-arg constructor carrying the deadline explicitly;
      both existing constructors are unchanged and default to no window.
- [x] **E6. Negative caching** — `RipClientConfig.Builder#negativeCacheTtlMillis(long)`
      (and `RIP.setNegativeCacheTtlMillis(long)` for a shared default) opts
      a client into caching a confirmed `404` for a fixed TTL, so it stops
      hammering a downstream for a resource it already confirmed doesn't
      exist - regardless of whatever (if anything) the `404` response's own
      `Cache-Control`/`ETag`/`Last-Modified` say, unlike every other cached
      status. `CacheCoordinator.reconcileCache` stores it directly (bypassing
      `isStorable`), reusing the existing `RestInPeaceHttpException` replay
      path unchanged - a cached `404` is decoded exactly like a real one.
- [x] **E7. `RedactingLoggingInterceptor`** — a direct payoff of
      `RequestContext.getBody()` above: a pre-built interceptor that logs
      the request (via `getBody()`) and response bodies the same way
      `LoggingInterceptor` logs method/URL/status/duration, but masks a
      configured set of field names (`password`, `token`, `secret`,
      `apiKey`, `ssn`, `authorization` by default) case-insensitively via a
      regex match over `"fieldName": value`-shaped text - reliable for a
      flat field, best-effort once its value is itself a nested
      object/array - so logging bodies is safe by default instead of a
      footgun.
- [x] **E8. Per-client retry budget** — `RipClientConfig.Builder#retryBudget(int,
      long)` is a token-bucket cap on *total* retries across a client in a
      rolling window, not per-call (still each call's own
      `@Retry#times()`). Tokens refill continuously (not a once-per-window
      burst); once exhausted, a call that would otherwise retry gives up
      immediately instead, same as reaching its own `times()`. Wired into
      `RetryExecutor`'s sync and async loops alike via a new internal
      `RetryBudget` (a plain synchronized token bucket, no new dependency).
      Addresses the actual production failure mode the circuit-breaker item
      above is reacting to (a retry storm amplifying an outage) without the
      complexity of a full circuit-breaker/bulkhead abstraction.

**Bigger bets (real design work first):**

- [x] **E9. Partial compile-time codegen** — one unsupported method (a
      generic collection return type like `List<User>`, a raw
      `CompletableFuture`, ...) used to disqualify the *entire* interface's
      codegen (`RestClientProcessor`'s own former comment: "generating a
      partially-correct implementation would be worse than not generating
      one at all"). Now only that one method falls back, delegating to a
      lazily-built internal `java.lang.reflect.Proxy` (backed by this same
      client's own `RequestExecutor`, so it shares its retry/cache/
      interceptor/timeout config) - every other method on the same
      interface still gets a real generated implementation. Built directly
      via `Proxy.newProxyInstance`/a new
      `RestClientInvocationHandler(RequestExecutor)` constructor rather than
      `RIP.getClient(...)`, which would look up this very generated class by
      name again and recurse forever. A default method needed no such
      mechanism at all once investigated - it's simply never overridden by
      the generated class, so ordinary Java default-method dispatch already
      resolves it via the class's own inherited implementation (a call back
      into another interface method from inside it still reaches that
      method's real generated override); a static method isn't part of the
      implementing contract and needs nothing generated either - so an
      interface mixing a default/static method with otherwise-fully-
      supported methods now gets real codegen too, instead of falling back
      to the reflective proxy entirely (a nested/private interface
      declaration remains a separate, unrelated precondition that still
      disqualifies the whole interface). An interface with *no*
      codegen-eligible method at all still isn't generated for - the plain
      reflective proxy already covers that with less indirection.
      **Important scope note surfaced while implementing this (since fixed
      by E12 below):** at the time this item landed, it did *not*, by
      itself, make `List<User>` correctly decodable - RIP's response
      decoding was `Class<?>`-based on *both* dispatch paths, so
      delegating to the reflective proxy fixed only the "does one
      unsupported method disqualify everything else" ergonomics problem,
      not the underlying decoding gap. E12 closed that gap on the
      reflective path (see its own entry below); compile-time codegen
      still doesn't generate for such a method, by design - see the
      README's
      ["Why isn't `List<User>` code-generated?"](README.md#why-isnt-listuser-code-generated)
      for the consumer-facing version of this explanation.
- [x] **E10. OpenAPI → `@RestClient` interface generator** — flips the
      current direction: `OpenApiClientGenerator` reads an OpenAPI 3.x JSON
      document and generates the *interface itself* (annotations and all) -
      spec-first client generation, not just annotation-first. A skeleton
      generator, not a full schema-to-POJO tool like swagger-codegen -
      every parameter and body is `String`; OpenAPI's own `{name}` path
      placeholder already matches `@PathParam`'s exactly, so paths need no
      translation. Backed by Gson (already an unconditional transitive
      dependency via `unirest-java`, now declared directly - no new jar for
      any consumer). Proven by a real `javac` compile of the generated
      source through `RestClientProcessor` itself in the test suite, not
      just string-matching the output.
- [x] **E11. Interceptor short-circuit responses** — a new
      `RequestInterceptor.shortCircuit(RequestContext)` default method
      (backward compatible - `beforeRequest` itself couldn't change return
      type without breaking every existing override) hands back a
      synthetic `ShortCircuitResponse` to skip the network call entirely -
      feature-flag bypasses, canary short-circuits, and a lightweight
      record/replay mode built on the interceptor chain. Called after
      every interceptor's `beforeRequest` (same FIFO order); first
      non-`null` wins. Wired into all ~20 call sites across both dispatch
      paths (reflective and compile-time-generated) and every return shape
      (sync/async, plain/`byte[]`/`File`/`RipResponse<T>`) by wrapping each
      one's innermost network supplier in `InterceptorDispatcher`, so
      `notifyAfterResponse`/caching/retry all see a short-circuited
      response exactly like a real one, with zero changes needed to
      `RetryExecutor`/`CacheCoordinator` themselves.
- [x] **E12. Generic `Type`-based response decoding** — fixes the decoding
      gap E9 explicitly called out as out of scope: a method returning
      `List<User>` (or wrapped in `RipResponse<T>`/`CompletableFuture<T>`,
      including `CompletableFuture<RipResponse<List<User>>>`) now decodes
      each element into the declared type, instead of the bare `List`/
      `LinkedTreeMap`s type erasure otherwise leaves. The reflective
      dispatch path (`RequestExecutor.processRestRequest`/`processAsync`)
      now reads `Method.getGenericReturnType()` - a `java.lang.reflect.Type`
      - instead of the type-erased `getReturnType()`, threaded through
      `ResponseDecoder`/`RetryExecutor`/`InterceptorDispatcher` by widening
      their `Class<?> returnType` parameters to `Type` in place (a
      `Class<?>` already satisfies `Type`, so every existing call site
      passing one compiles and behaves unchanged). Decoding itself goes
      through a new internal `RuntimeGenericType`, which adapts an
      arbitrary runtime `Type` into `kong.unirest.GenericType` for
      Unirest's own `ObjectMapper.readValue(String, GenericType)` - the
      already-correct Gson-backed mechanism the zero-config default
      `JsonObjectMapper` needed no change to use, since `GenericType` only
      exposes the `new GenericType<List<User>>(){}` anonymous-subclass
      pattern (no constructor/factory accepting a `Type` value directly,
      since that pattern assumes the type is always known at the call
      site); worked around with a one-time reflective overwrite of its
      already-`protected` `type` field instead of inferring it from a
      generic superclass. Compile-time codegen deliberately keeps its
      existing E9 behavior rather than gaining an equivalent
      generic-signature-aware code path: such a method still falls back to
      the reflective proxy (now correctly decoding it), since an annotation
      processor has no `Class<?>` literal to emit for "a list of `User`" -
      a fundamentally different problem than decoding a `Type` at runtime.
      `ReflectiveRestClientValidator`'s own return-type check was relaxed
      the same way (a `ParameterizedType` inner type is now accepted
      alongside a plain `Class`), so a previously-rejecting-at-validation
      shape like `RipResponse<List<User>>` no longer fails
      `RIP.getClient(...)` up front. See the README's
      [Generic collection return types](README.md#generic-collection-return-types-listuser)
      and the updated
      ["Why isn't `List<User>` code-generated?"](README.md#why-isnt-listuser-code-generated).

## Quality audits (2026-09-19) — in progress, one at a time

Three depth-first audit passes over the existing codebase, requested as a
set but worked one at a time rather than in parallel - each gets its own
findings, fixes, and (where relevant) new tests before the next one starts.
Not new features; the goal is finding and fixing problems in what's already
shipped.

- [x] **Security review** — audit for actual vulnerabilities, not just a
      dependency-CVE scan: injection risk anywhere user/response data
      reaches a sink (logging, the mock server's request parsing,
      `OpenApiClientGenerator`'s generated source), SSRF/URL-validation gaps
      around `@Url`/a runtime base URL (both accept an arbitrary string that
      becomes a real outbound request target with no allowlist), secret
      handling (`RedactingLoggingInterceptor`'s regex-based masking has a
      documented gap for nested JSON - worth a hard look at how exploitable
      that actually is), deserialization safety (Gson's default
      `JsonObjectMapper`, `RuntimeGenericType`'s reflective field overwrite),
      and dependency CVEs across the full tree (`unirest-java`, Apache
      HttpClient/HttpCore/HttpMime, Gson, the GraalVM reachability-metadata
      artifacts). Found and fixed one high-confidence vulnerability:
      `OpenApiClientGenerator` concatenated OpenAPI-spec-derived values
      (a path, `servers[0].url`, a parameter name, `info.title`) directly
      into the generated `.java` source's string literals/Javadoc comment
      with no escaping - unlike `RestClientProcessor`'s own
      `stringLiteral()` for the identical problem - so a malicious spec's
      embedded `"`/`*/` could break out and inject arbitrary Java that
      then compiled and shipped as part of the generated client (see #186).
      Everything else audited (`@Url`/base URL, `RedactingLoggingInterceptor`,
      `RuntimeGenericType`, `MockRestServer`/`RecordedRequest`,
      `FormEncoder`/`MultipartEncoder`) came back clean; dependency CVEs
      are already covered continuously by GitHub Advanced Security/
      Dependabot alerts rather than this manual pass.
- [x] **Tech debt / code quality pass** — duplication and inconsistent
      patterns across `RequestExecutor`'s collaborators and the two dispatch
      paths (reflective vs. compile-time-generated) now that both have grown
      significantly since step 1; anything on a "not needed now" list above
      worth revisiting now that the library has matured; dead code or
      over-broad abstractions the various feature slices left behind;
      consistency of validation error messages between
      `ReflectiveRestClientValidator` and `CompileTimeRestClientValidator`
      (deliberately separate implementations per design, but worth checking
      they haven't drifted in ways that confuse a consumer who hits one
      then the other). Found and fixed one real bug while checking that
      last point: `CompileTimeRestClientValidator` was never updated for
      E12 and still hard-rejected a `CompletableFuture<List<User>>`/
      `RipResponse<List<User>>` return type as a compile error, even though
      that exact shape works fine through `RIP.getClient(...)` since
      `ReflectiveRestClientValidator`'s own equivalent check was relaxed
      for it - so such an interface compiled reflectively but failed to
      build outright the moment `RestClientProcessor` (always active,
      SPI-registered) ran on it (see #187). No dead code or leftover TODOs
      found in `core/src/main`; the `not needed now` list is deliberately
      deferred feature work, not tech debt, so left as-is. The
      `RequestExecutor`/collaborator duplication that exists (e.g.
      `InterceptorDispatcher`'s four near-identical sync/async,
      string/bytes short-circuit wrappers) is small, well-documented, and
      not worth a forced generic abstraction over.
- [x] **Performance review** — reflection overhead on the fallback proxy
      path vs. the compile-time-generated one (is the gap actually
      measurable, and where); allocation hot spots in the retry/cache/
      interceptor pipeline (`RequestContext`/`CachedResponse` construction
      per call); `RuntimeGenericType`'s one-time reflective field overwrite
      (cost paid once or per-call); `MockRestServer`'s route-matching loop
      (linear scan - fine for test-suite-scale route counts, worth
      confirming that assumption still holds); JaCoCo/Codecov CI overhead
      now that the suite has grown substantially this session (~270 new
      test cases across the two coverage pushes). Found and fixed one real
      allocation hot spot: `RetryExecutor.isRetryableStatus()` allocated an
      `IntStream` pipeline on every response for any `@Retry`'d call (not
      just a retried one) to scan an array that's always a handful of
      status codes - replaced with a plain loop, same behavior, zero
      allocation (see #188). Answered the two open questions the item
      itself posed: `RuntimeGenericType`'s reflective field overwrite is
      paid **per call** to `RuntimeGenericType.of(...)`, not once - only
      `setAccessible(true)` is one-time (a static initializer); the actual
      `Field.set(...)` plus a new anonymous `GenericType` subclass
      instance happens on every generic-collection decode. Left as-is
      without profiling evidence it's an actual bottleneck relative to the
      JSON parsing it enables - changing it would mean caching per-`Type`
      adapters, a real design change, not a found-and-fixed bug.
      `MockRestServer`'s linear route scan is still fine - it's test-only
      infrastructure bounded by how many routes a human writes into one
      test file, not a production hot path. No JMH harness exists in this
      repo to put a number on the reflective-vs-compile-time-generated gap
      itself; adding one would be new tooling; the two dispatch paths' own
      test suites, though, agree qualitatively (compile-time skips every
      `Method.getAnnotation(...)`/`Parameter[]` reflective lookup entirely).
      CI job durations observed across this session's own PRs (`test` ~50s,
      `native-image-smoke-test` up to ~140s, everything else under a
      minute) show no evidence of JaCoCo/Codecov overhead being a problem
      worth chasing.

Order: security first (highest blast radius if something's actually wrong),
then tech debt, then performance - revisit the order if the security pass
turns up nothing urgent and something else seems more valuable to do next.
