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

### Changed

- A non-2xx response now always throws `RestInPeaceHttpException` (status +
  raw body), whatever the method's return type - previously the response
  flowed through as a normal return value with no error signal.

### Fixed

- The hosted Javadoc site now always reflects the exact commit that was
  released, instead of `master`'s post-release `-SNAPSHOT` version bump.
- `@PathParam` values are now percent-encoded before being substituted
  into the URL, instead of spliced in raw - a `/`, `?`, `#`, or a space in
  the value previously produced a broken or subtly wrong URL (e.g. an
  unencoded `?` silently starting a query string partway through the
  path).

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
