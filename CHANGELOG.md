# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- `CONTRIBUTING.md` and this changelog.
- `@Retry(times, delayMillis, backoffMultiplier, retryOnStatus)` re-issues a
  request that fails with a transport error or a matching status code, for
  both synchronous and `CompletableFuture` return types.
- `@BaseUrl` on a `@RestClient` interface, so methods can use a relative
  path instead of repeating the full URL. An absolute method URL ignores
  `@BaseUrl` and is used as-is.
- `RIP.getClient(Class, String)` resolves relative method URLs against a
  base URL supplied at call time, for multi-environment deployments where
  the base URL isn't known until runtime. Takes priority over `@BaseUrl`.
- `@ErrorType(SomeClass.class)` deserializes a non-2xx response's error body
  into that class instead of the raw string.
- `@QueryMap`/`@HeaderMap` add one query param/header per entry of an
  annotated `Map<String, ?>` parameter, for a set of names not known until
  runtime. Combines with fixed `@QueryParam`/`@HeaderParam` on the same
  method.
- `@Multipart`/`@Part` send a `multipart/form-data` body - a `String` part
  as a plain form field, a `File`/`byte[]`/`InputStream` part as a file
  upload (`@Part`'s `fileName` names a `byte[]`/`InputStream` part or
  overrides a `File`'s own name) - instead of `@Body`'s JSON/raw-string body.
- `@PartMap` adds one multipart part per entry of an annotated
  `Map<String, ?>` parameter, for a set of part names not known until
  runtime. Combines with fixed `@Part`s on the same method. Wrap a
  `File`/`byte[]`/`InputStream` entry value in `PartValue.of(value,
  fileName)` to send it under a name other than its map key.
- `RipResponse<T>` return type (or `CompletableFuture<RipResponse<T>>` for
  an async method) wraps `T` with the response's status code and headers,
  for a method that needs more than just the body. `T` is decoded by the
  same rules as a plain return type. A non-2xx response still throws
  `RestInPeaceHttpException` rather than being wrapped.
- `@Timeout(connectMillis, readMillis)` overrides the connect/read timeout
  for one method's calls only.
- `RipClientConfig`, passed to a new `RIP.getClient(Class, RipClientConfig)`
  overload, overrides base URL, connect/read timeout, and proxy for one
  client. `@Timeout` takes priority over `RipClientConfig`'s timeout, which
  takes priority over the shared client's own configured default.
- `byte[]` return type (or `CompletableFuture<byte[]>`/`RipResponse<byte[]>`)
  for a binary response, decoded as exact bytes instead of being corrupted
  by the previous always-`String` decoding.
- `File` return type with a `@Destination File` parameter streams a binary
  response straight to disk instead of buffering it into a `byte[]`, for
  both synchronous and `CompletableFuture<File>` methods.
- `DownloadProgressListener` parameter reports `bytesWritten`/`totalBytes`
  as a `byte[]`/`File` method's response streams in.
- `UploadProgressListener` parameter reports `field`/`bytesWritten`/
  `totalBytes` as a `@Multipart` method's `File`/`InputStream` parts are
  written to the request body.
- A `Collection` argument to `@QueryParam`/a `@QueryMap` entry now repeats
  the query param once per element (`?tag=a&tag=b`) instead of being sent
  as one mangled value.
- `@Url` binds a full URL as a `String` parameter, bypassing `@BaseUrl`/a
  runtime base URL/`@PathParam` entirely, for a call whose URL isn't a
  fixed template - a pagination `next` link, a HATEOAS action link from a
  previous response. Only valid alongside an HTTP method annotation with
  no static `value()`; `@QueryParam`/`@HeaderParam`/etc. still work
  normally, appended to the given URL.
- `RIP.setObjectMapper(ObjectMapper)` sets the JSON `ObjectMapper` used by
  every client sharing the app-wide static Unirest client (Jackson, a
  configured Gson, ...) instead of Unirest's default Gson-backed one.
  `RipClientConfig.builder().objectMapper(...)` sets one for a single
  `RipClientConfig`-configured client instead, since that client's own
  dedicated Unirest instance isn't reachable via `RIP.setObjectMapper(...)`.
- `@Headers({"Name: Value", ...})` sets one or more fixed headers on a
  method, for a header whose value never varies (`Accept`, `Cache-Control`,
  an API version) - unlike `@HeaderParam`/`@HeaderMap`, no call argument is
  involved. Each entry is split on its first `:` with whitespace trimmed
  around both sides. Combines with `@HeaderParam`/`@HeaderMap` on the same
  method, which win over a `@Headers` entry of the same name.
- (Step 1 of the "compile-time proxy generation" roadmap item) A
  `RestClientProcessor` annotation processor now generates a real
  `<Interface>_RipImpl` class - instead of a `java.lang.reflect.Proxy` - for
  a `@RestClient` interface whose methods are all a single fixed HTTP verb
  with only `@PathParam`/plain `@QueryParam` params and a
  `void`/`String`/POJO return type; `RIP.getClient(...)` prefers it when
  present. An interface with any method outside that shape is left entirely
  to the existing reflective proxy - see
  `docs/design/compile-time-proxy-generation.md`.
- `samples/compile-time-proxy-consumer`, a standalone project (built and run
  in CI on every push/PR) showing the feature above from a real downstream
  consumer's point of view - see its README.
- Compile-time proxy generation now also covers `@Timeout` and `@Retry` -
  a method combining the step-1 supported shape with either annotation is
  generated for (honoring it) instead of falling back to the reflective
  proxy.
- Compile-time proxy generation now also covers `@Headers`, `@HeaderParam`,
  `@HeaderMap`, `@QueryMap`, required-or-defaulted `@QueryParam`/
  `@HeaderParam`, `@Body`, `@Url`, and `@ErrorType`.
- Compile-time proxy generation now also covers `@Multipart`, `@Part`,
  `@PartMap`, and `UploadProgressListener`.
- Compile-time proxy generation now also covers `byte[]`, `File` (with
  `@Destination`/`DownloadProgressListener`), and `RipResponse<T>` return
  types - only `CompletableFuture<T>` (async) remains unsupported.
- Compile-time proxy generation now also covers `CompletableFuture<T>`
  (async), for every supported return-type shape - `String`/POJO,
  `byte[]`, `File`, and `RipResponse<T>` alike. This was the last item on
  the design doc's feature-parity table: compile-time proxy generation now
  has full feature parity with the reflective proxy.
- A native-image smoke test: `samples/compile-time-proxy-consumer` gained
  a `native` Maven profile and CI job building it into a real GraalVM
  native executable, the concrete proof that compile-time proxy
  generation's covered path is genuinely reflection-free under
  native-image's closed-world analysis, with zero hand-written
  configuration in the consumer project.
- A compile-testing validation suite: a `@RestClient` interface that fails
  `RestClientValidator`'s semantic rules at runtime (an invalid `@Retry`, a
  malformed `@Headers` entry, an unmatched path param, ...) now fails
  **compilation** outright, with a matching error message, via a new
  compile-time counterpart of that validator. This was the exit criterion
  for the "compile-time proxy generation" roadmap item - full feature
  parity plus this validation suite - which is now complete.
- `com.shri.restinpeace.mock.MockRestServer` - a real, local HTTP server for
  unit-testing code that calls a `@RestClient` interface without a real
  network dependency. `MockRestServer.on(...)` registers a sticky response
  for a method+path (with `{name}` placeholder matching); `.enqueue(...)`
  scripts a one-time sequence of responses (e.g. a `503` then a `200`, to
  prove `@Retry` recovers); `RecordedRequest` exposes the path, query
  params, headers, and body actually received. Since it's a real embedded
  server rather than a fake transport, `@Retry`, `@Timeout`, and every
  registered `RequestInterceptor` run completely unmodified, against both
  the reflective and compile-time-generated dispatch paths.
- Four follow-up enhancements to `MockRestServer`: `.reset()` clears queued
  responses, registered routes, and recorded requests, so one server can be
  reused across a test class's methods instead of starting a new one each
  time; `MockResponse.json(Object)` serializes a plain object with the same
  Unirest `ObjectMapper` RIP itself delegates to, instead of hand-writing
  JSON strings; `.on(httpMethod, pathTemplate, queryParams, response)` adds
  an optional exact-match query-param constraint to route matching, for an
  endpoint that behaves differently by query param (e.g. `?status=active`
  vs. `?status=archived`); and `com.shri.restinpeace.mock.MockRestServerExtension`,
  a JUnit 5 extension registered with `@ExtendWith`, starts one server for a
  test class, resets it before each test, and hands it to test (or
  `@BeforeEach`) methods as a parameter - removing not just the
  `start()`/`.close()` boilerplate but the need to rebuild a `@RestClient`
  per test, since the server's base URL is now stable across the whole
  class. The extension required promoting `junit-jupiter` from `test` to
  `provided` scope, since it's now implemented by a main-source class -
  verified non-transitive, so this costs nothing for a consumer who doesn't
  use it.
- `MockResponse.connectionFailure()` and `.delay(millis)` simulate a
  transport-level failure and a slow response - the two cases
  `MockRestServer`'s own documentation claimed were testable (`@Retry`'s
  "no response at all" retry path, and `@Timeout` actually firing) but
  had no way to be exercised until now.
- `MockRestServer.enqueueFor(httpMethod, pathTemplate, response)` scripts
  a one-time response for a route already registered via `.on(...)`,
  consumed before that route's sticky response - so a route can fail a
  fixed number of times before settling into a sticky final answer,
  which the server-wide `.enqueue(...)` queue can't express once any
  route covers the same path. `.onFlaky(httpMethod, pathTemplate,
  failuresBeforeSuccess, failureResponse, successResponse)` is sugar
  over the same mechanism.
- `MockRestServer.remove(httpMethod, pathTemplate)` removes a registered
  route outright, so a test can stop covering an endpoint mid-test
  without a full `.reset()` (which also wipes every other route, the
  queue, and recorded-request history).
- `MockRestServer.on(httpMethod, pathTemplate, matcher, response)`
  matches on a request header or the request body via a
  `Predicate<RecordedRequest>`, for a constraint `requiredQueryParams`
  can't express (e.g. an `X-Api-Version` header, or a field in the
  request body).
- `RecordedRequest.getParts()` decodes a `multipart/form-data` body into
  its individual parts (name, optional file name, optional content
  type, content), for asserting on what a `@Multipart` method actually
  sent instead of substring-matching the raw encoded body.
  `RecordedRequest` now captures the raw request body as `byte[]`
  internally (exposed via a new `getRawBody()`) instead of eagerly
  UTF-8-decoding it, since the old approach was lossy for a
  `@Multipart` request's binary parts.
- `MockRestServer.countOf(httpMethod, pathTemplate)` returns how many
  recorded requests match, without manually filtering
  `getRecordedRequests()` or looping over `takeRequest()`.
- `MockRestServer.getUnhitRoutes()` returns every registered route that
  hasn't matched any recorded request yet - a coverage check for a
  route left registered after the code path that used to exercise it
  was removed, which otherwise causes no failure at all.
- `RecordedRequest.getReceivedAt()` timestamps each request, so a test
  can verify `@Retry`'s `backoffMultiplier` actually grows the delay
  between attempts, instead of only counting how many attempts were
  made.
- `@FormUrlEncoded`/`@Field`/`@FieldMap` send an
  `application/x-www-form-urlencoded` body - the `@Field` counterpart to
  `@Multipart`/`@Part`, for OAuth token endpoints and classic HTML-form
  APIs. `@FieldMap` adds one form field per entry of an annotated
  `Map<String, ?>` parameter, combining with fixed `@Field`s on the same
  method; a `Collection`-valued `@Field`/`@FieldMap` entry repeats the key
  once per element (`tag=a&tag=b`), the same convention `@QueryParam`
  uses. Can't combine `@FormUrlEncoded` with a `@Body` parameter or
  `@Multipart` on the same method.
- `RecordedRequest.getFormFields()` decodes an
  `application/x-www-form-urlencoded` body into its field name/value
  pairs, the `@FormUrlEncoded` counterpart to `getParts()`.
- Response caching for `GET` requests, honoring the server's own
  `Cache-Control`/`ETag`/`Last-Modified` instead of hitting the network
  every time. Attach a `com.shri.restinpeace.cache.Cache` via
  `RipClientConfig.Builder.cache(Cache)` (one client) or
  `RIP.setCache(Cache)` (the shared default) - `InMemoryCache` ships as
  the default implementation. A fresh entry is served with no network
  call; a stale entry with an `ETag`/`Last-Modified` is revalidated via
  `If-None-Match`/`If-Modified-Since`, and a `304 Not Modified` refreshes
  it and returns the cached body. `@NoCache` opts a single method out.
  Scoped to `String`/POJO `GET` responses for now, not `byte[]`/`File`
  downloads.
- `MockResponse.notModified()` - a `304 Not Modified` shorthand, for
  scripting a mock server's conditional-GET revalidation response.

### Changed

- A non-2xx response now always throws `RestInPeaceHttpException` (status +
  raw body), whatever the method's return type - previously the response
  flowed through as a normal return value with no error signal.

### Fixed

- Compile-time proxy generation: a method combining the supported shape
  with `@Retry`, `@Timeout`, `@Headers`, or `@ErrorType` was silently
  included in the generated implementation, which had no code path
  applying any of the four - dropping that annotation's behavior entirely
  instead of either honoring it or correctly falling back to the
  reflective proxy. `@Timeout`/`@Retry` are now genuinely supported (see
  above); `@Headers`/`@ErrorType` now correctly disqualify a method, same
  as every other not-yet-supported feature.
- Compile-time proxy generation: every generated `<Interface>_RipImpl`
  class was silently unusable under GraalVM native-image - `RIP.getClient`
  looks it up via a dynamically-computed `Class.forName`, which
  native-image's static analysis can't resolve, so every call fell back
  to the reflective proxy, which itself isn't registered for native-image
  either, crashing. `RestClientProcessor` now emits a `reflect-config.json`
  alongside every generated class, closing the gap with zero consumer
  configuration.
- Removed two long-unused methods from the internal `SampleApi` test
  fixture that existed only to hold an invalid HTTP-method-annotation
  combination for a different (reflective-path) test - coverage
  `RestClientValidatorTest` already has via its own dedicated interfaces,
  and which the new compile-time validator above now correctly flags as a
  build error if left in place.
- The hosted Javadoc site now always reflects the exact commit that was
  released, instead of `master`'s post-release `-SNAPSHOT` version bump.
- `@PathParam` values are now percent-encoded before being substituted
  into the URL, instead of spliced in raw - a `/`, `?`, `#`, or a space in
  the value previously produced a broken or subtly wrong URL (e.g. an
  unencoded `?` silently starting a query string partway through the
  path).
- A response that fails to decode because no JSON `ObjectMapper` is
  configured at all now throws `RestInPeaceException` naming the problem
  and pointing at `RIP.setObjectMapper(...)`, instead of a bare
  `kong.unirest.UnirestConfigException` with no mention of RIP.
- `MockRestServer`'s registered routes list wasn't thread-safe, unlike
  its response queue and recorded-request list - a route registered via
  `.on(...)` while a prior async (`CompletableFuture`) request from the
  same test was still being served could race a concurrent read.
- `MockResponse.header(name, value)` silently replaced an earlier value
  for the same header name instead of adding a second one, unlike
  `RecordedRequest`/`RipResponse`, both of which already support a
  header repeating (e.g. multiple `Set-Cookie` headers).
- `MockRestServer.enqueue(...)`'s own documentation claimed it was "the
  way to script a sequence of responses to the same endpoint," but a
  route registered via `.on(...)` for that endpoint shadowed the queue
  entirely, since routes are matched before the queue is ever
  consulted - `.enqueueFor(...)` (above) is the fix.
- `MockRestServer.on(...)`, called twice for the same method and path
  (and query params, if given), silently appended a second,
  permanently-shadowed route instead of replacing the first - it now
  replaces the existing route in place.

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
