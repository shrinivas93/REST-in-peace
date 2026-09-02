# REST-in-peace

A Simple, Declarative and Peaceful REST Client

REST-in-peace lets you declare a REST API as a plain Java interface and get a
working HTTP client for it at runtime — no hand-written request-building
boilerplate. Annotate an interface, call `RIP.getClient(...)`, and invoke its
methods like any other Java call.

## Features

- Declarative REST clients defined as annotated Java interfaces
- `@BaseUrl` declares a base URL once instead of repeating it on every
  method, or pass one to `RIP.getClient(...)` when it's only known at
  runtime (e.g. one per deployment environment)
- All seven common HTTP verbs: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`,
  `HEAD`, `OPTIONS`
- `@PathParam`, `@QueryParam`, `@HeaderParam`, and `@Body` parameter binding
- `@QueryMap`/`@HeaderMap` for a dynamic set of query params/headers not
  known until runtime
- `@Multipart`/`@Part`/`@PartMap` for `multipart/form-data` uploads (form
  fields, `File`/`byte[]`/`InputStream` file parts, a dynamic set of parts
  not known until runtime, and an `UploadProgressListener` for progress
  reporting on a large file part)
- Optional params with `required` and `defaultValue`
- Request bodies: raw strings are sent as-is, other objects are
  JSON-serialized automatically
- Responses: a `String` return type gives you the raw body; any other
  return type is deserialized from JSON automatically
- `byte[]` and `File` (via `@Destination`) return types for binary
  downloads, with an optional `DownloadProgressListener` for progress
  reporting
- `RipResponse<T>` wraps `T` with the response's status code and headers,
  for a method that needs more than just the body
- A non-2xx response always throws `RestInPeaceHttpException`, with
  `@ErrorType` to deserialize the error body into a class
- `CompletableFuture<T>` return types fire requests asynchronously
- `@Retry` re-issues a failed request with configurable backoff, for both
  synchronous and async methods
- `@Timeout` overrides the connect/read timeout for one method;
  `RipClientConfig` overrides base URL, timeout, and proxy for one client
  (e.g. one per deployment environment)
- Global interceptors for cross-cutting concerns (auth headers, logging)
  without touching individual `@RestClient` interfaces
- Interfaces are validated up front — misconfigured clients fail fast at
  `RIP.getClient(...)` time with a clear error, not on the first call
- Works from any JVM language (Java, Kotlin, Scala, ...) since it's just an
  annotated interface backed by a JDK dynamic proxy

## Requirements

- Java 8 or newer
- Maven (or any build tool that resolves Maven coordinates)

## Installation

Published to GitHub Packages. Add the repository and dependency to your
`pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/shrinivas93/REST-in-peace</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.shri</groupId>
    <artifactId>rest-in-peace</artifactId>
    <version>1.0.0.0-SNAPSHOT</version>
</dependency>
```

GitHub Packages requires authentication even for public read access — see
[GitHub's Maven registry docs](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)
for configuring credentials in your `settings.xml`.

Full API documentation is browsable at
[shrinivas93.github.io/REST-in-peace](https://shrinivas93.github.io/REST-in-peace/),
rebuilt from the exact commit of each release. Each published version also
ships a `-javadoc.jar` alongside the main jar in GitHub Packages.

## Quick start

Declare your API as an interface annotated with `@RestClient`, with each
method annotated with the HTTP verb and URL it calls:

```java
import com.shri.restinpeace.RIP;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;

@RestClient
public interface UserApi {

    @GET("https://api.example.com/users/{id}")
    String getUser(@PathParam("id") String id,
                   @QueryParam(value = "verbose", defaultValue = "false") Boolean verbose);
}
```

Get a client and call it like a normal method:

```java
UserApi userApi = RIP.getClient(UserApi.class);
String response = userApi.getUser("42", true);
```

`RIP.getClient(...)` validates the interface before handing back a proxy. If
anything is misconfigured — a method with no HTTP verb, an invalid URL, a
`{pathParam}` with no matching `@PathParam`, and so on — it throws a
`RestInPeaceException` immediately, with the full list of problems, instead
of failing later on the first call.

## Annotations

### `@RestClient`

Marks an interface as a REST client. Required on every interface passed to
`RIP.getClient(...)`.

### `@BaseUrl`

Declares the base URL once on the interface, so methods can use a relative
path instead of repeating the full URL every time:

```java
@RestClient
@BaseUrl("https://api.example.com")
interface UserApi {
    @GET("/users/{id}")
    User getUser(@PathParam("id") String id);
}
```

A method URL that's already absolute (starts with `http://` or `https://`)
ignores `@BaseUrl` and is used as-is — a method can always opt out with its
own full URL. A relative method URL on an interface with no `@BaseUrl` fails
validation. `@BaseUrl` can itself contain a `{placeholder}`, resolved by a
`@PathParam` the same as any method URL.

#### Choosing the base URL per environment

`@BaseUrl`'s value has to be a compile-time constant, so it can't itself
hold something environment-dependent. For an app that deploys the same
`@RestClient` interface against a different base URL per environment (dev,
staging, prod), pass the resolved URL to `RIP.getClient(...)` instead:

```java
UserApi api = RIP.getClient(UserApi.class, System.getenv("USER_API_BASE_URL"));
```

This takes priority over `@BaseUrl` on the interface, so `@BaseUrl` is
optional when every relative method URL is covered by the runtime value.
Precedence, most specific first: an absolute method URL always wins, then
the `baseUrl` passed to `getClient(...)`, then `@BaseUrl` on the interface —
a relative method URL covered by none of these fails validation.

For more than just the base URL varying per environment — a connect/read
timeout or a proxy that differs too — pass a
[`RipClientConfig`](#per-client-configuration-timeout-and-proxy) to
`getClient(...)` instead of a plain `String`.

### HTTP method annotations

One of `@GET`, `@POST`, `@PUT`, `@PATCH`, `@DELETE`, `@HEAD`, `@OPTIONS` on
each method, holding the URL template:

```java
@GET("https://api.example.com/items/{id}")
String getItem(@PathParam("id") String id);
```

Every method must have exactly one of these — none or more than one fails
validation.

### `@PathParam`

Substitutes a `{placeholder}` in the URL template with the argument value.
Every `{placeholder}` in the URL must have a matching `@PathParam`, checked
at validation time.

```java
@GET("https://api.example.com/items/{id}")
String getItem(@PathParam("id") String id);
```

The value is percent-encoded before substitution, so a `/`, `?`, `#`, or a
space in it lands as literal content of that one path segment instead of
producing a broken or subtly wrong URL (e.g. an unencoded `?` would
otherwise start a query string partway through the path).

### `@QueryParam`

Appends a query string parameter. Supports `required` (throws at call time
if no value and no default is available) and `defaultValue` (used when the
argument is `null`):

```java
@GET("https://api.example.com/items")
String search(@QueryParam(value = "q", required = true) String query,
               @QueryParam(value = "page", defaultValue = "1") Integer page);
```

A `Collection` argument repeats the param once per element instead of
being sent as one mangled value:

```java
@GET("https://api.example.com/items")
String search(@QueryParam("tag") List<String> tags);

// search(List.of("a", "b")) sends ?tag=a&tag=b
```

### `@HeaderParam`

Sets an HTTP header, with the same `required`/`defaultValue` semantics as
`@QueryParam`:

```java
@GET("https://api.example.com/items")
String search(@HeaderParam(value = "Authorization", required = true) String token);
```

### `@QueryMap` / `@HeaderMap`

For a set of query params or headers whose names aren't known until
runtime — a search endpoint's open-ended filter set, or caller-supplied
headers in a multi-tenant app — annotate a `Map<String, ?>` parameter
instead of adding one `@QueryParam`/`@HeaderParam` per name. Each map entry
becomes one query param or header; a `null` map, or a `null` entry value,
is skipped rather than throwing:

```java
@GET("https://api.example.com/search")
String search(@QueryParam("q") String query, @QueryMap Map<String, String> filters);

@GET("https://api.example.com/users/{id}")
User getUser(@PathParam("id") String id, @HeaderMap Map<String, String> extraHeaders);
```

`@QueryMap`/`@HeaderMap` can be combined with fixed `@QueryParam`/`@HeaderParam`
parameters on the same method — the fixed ones for names you always know,
the map for everything else. At most one parameter per method may be
annotated `@QueryMap`, and at most one `@HeaderMap`. Like `@QueryParam`, a
`@QueryMap` entry whose value is a `Collection` is repeated once per
element.

### `@Body`

Sends a request body. Only valid on `POST`, `PUT`, `PATCH`, and `DELETE` —
using it on `GET`, `HEAD`, or `OPTIONS` fails validation. A `String` value is
sent as-is; any other object is JSON-serialized automatically:

```java
@POST("https://api.example.com/items")
String createItem(@Body Item item);

@POST("https://api.example.com/items/raw")
String createRaw(@Body String rawJson);
```

At most one parameter per method may be annotated `@Body`.

### `@Multipart` / `@Part` / `@PartMap`

Sends a `multipart/form-data` body instead of `@Body`'s JSON/raw-string one —
for file uploads and classic HTML-form-style POSTs:

```java
@POST("https://api.example.com/users/{id}/avatar")
@Multipart
String uploadAvatar(@PathParam("id") String id, @Part("caption") String caption, @Part("file") File avatar);
```

`@Multipart` goes on the method (same HTTP methods `@Body` supports —
`GET`/`HEAD`/`OPTIONS` fail validation); `@Part` goes on each field. A
`String` is sent as a plain form field; `File`, `byte[]`, and `InputStream`
are all sent as a file part. A method can't combine `@Multipart` with a
`@Body` parameter, and needs at least one `@Part`/`@PartMap` to be worth
declaring multipart at all. `@Part`'s `required` works the same as
`@QueryParam`'s — `false` by default, silently skipping a `null` argument;
`true` throws at call time instead.

A `byte[]`/`InputStream` part has no filename of its own, so the multipart
field needs one from somewhere — `@Part`'s `fileName` supplies it (also
usable to override a `File` part's own name), defaulting to the part's
`value()` when left unset:

```java
@POST("https://api.example.com/users/{id}/avatar")
@Multipart
String uploadAvatarBytes(@PathParam("id") String id,
        @Part(value = "file", fileName = "avatar.png") byte[] avatarBytes);
```

For a set of parts not known until runtime, `@PartMap` on a `Map<String, ?>`
parameter adds one part per entry — the `@Part`/`@Multipart` counterpart to
`@QueryMap`/`@HeaderMap`. Each value is handled the same way a `@Part` value
would be, with the entry's key doubling as the filename for a
`byte[]`/`InputStream` value:

```java
@POST("https://api.example.com/uploads")
@Multipart
String upload(@PartMap Map<String, Object> parts);

// upload(mapOf("caption", "vacation photo", "file", photoBytes));
```

`@PartMap` combines with fixed `@Part` parameters on the same method, a
`null` map or a `null` entry value is skipped rather than an error, and at
most one `@PartMap` parameter per method is allowed, same as `@QueryMap`/
`@HeaderMap`.

A `@PartMap` entry has no per-entry `fileName` to set, unlike a fixed
`@Part` - wrap a `File`/`byte[]`/`InputStream` value in `PartValue.of(value,
fileName)` when an entry needs a name other than its key:

```java
Map<String, Object> parts = new LinkedHashMap<>();
parts.put("caption", "vacation photo");                          // plain form field
parts.put("thumb", photoBytes);                                  // filename defaults to "thumb"
parts.put("file", PartValue.of(photoBytes, "photo.jpg"));        // filename "photo.jpg" instead of "file"
api.upload(parts);
```

Add an `UploadProgressListener` parameter to observe upload progress on a
large `File`/`InputStream` part - only valid alongside `@Multipart`:

```java
@POST("https://api.example.com/users/{id}/avatar")
@Multipart
String uploadAvatar(@PathParam("id") String id, @Part("file") File avatar, UploadProgressListener onProgress);
```

```java
avatarApi.uploadAvatar("42", avatar, (field, bytesWritten, totalBytes) ->
        System.out.printf("%s: %d / %d%n", field, bytesWritten, totalBytes));
```

It's only called for `File`/`InputStream` parts - a `String`/`byte[]` part
is written in one shot with nothing meaningful to report. `field` is the
part's name (its `@Part`/`@PartMap` key), needed to tell parts apart since
calls for different parts interleave rather than running one at a time.
Pass `null` for a call that doesn't need progress reporting.

## Return types

A method's declared return type controls what you get back:

```java
@GET("https://api.example.com/users/{id}")
String getUserRaw(@PathParam("id") String id);   // raw response body

@GET("https://api.example.com/users/{id}")
User getUser(@PathParam("id") String id);        // response JSON deserialized into User

@POST("https://api.example.com/events")
void fireEvent(@Body Event event);               // response body discarded
```

`String` gives you the raw response body. `void` fires the request and
discards the response. Anything else is deserialized from the response body
as JSON, the same way `@Body` serializes non-`String` request bodies. These
rules apply to a successful (2xx) response — see below for anything else.

### Binary downloads: `byte[]` and `File`

A `String` return type is fine for text, but decoding a binary response
(an image, a PDF, a zip) as a `String` corrupts it. Declare `byte[]`
instead to get the exact bytes back:

```java
@GET("https://api.example.com/reports/{id}/pdf")
byte[] downloadReport(@PathParam("id") String id);
```

For a large response, buffering the whole thing into a `byte[]` wastes
memory. Declare `File` instead, with a `@Destination File` parameter
saying where to write it - the same `File` instance comes back once the
download finishes:

```java
@GET("https://api.example.com/reports/{id}/pdf")
File downloadReport(@PathParam("id") String id, @Destination File target);
```

```java
File pdf = reportApi.downloadReport("42", new File("/tmp/report.pdf"));
```

Both work with `CompletableFuture<byte[]>`/`CompletableFuture<File>` for
an async download, and `byte[]` also works wrapped in `RipResponse<byte[]>`
when you need the status/headers alongside the bytes. A non-2xx response
still throws `RestInPeaceHttpException` as usual - the error body is
decoded as text (a JSON or plain-text error payload is far more likely
than a binary one), and a `File` destination is left untouched rather
than written with error content.

Add a `DownloadProgressListener` parameter to any of the above to observe
progress as the response streams in:

```java
@GET("https://api.example.com/reports/{id}/pdf")
File downloadReport(@PathParam("id") String id, @Destination File target,
        DownloadProgressListener onProgress);
```

```java
reportApi.downloadReport("42", target, (bytesWritten, totalBytes) ->
        System.out.printf("%d / %d%n", bytesWritten, totalBytes));
```

`totalBytes` is `-1` if the server didn't send a `Content-Length`. Pass
`null` for a call that doesn't need progress reporting.

### Response headers and status: `RipResponse<T>`

The rules above give you the body only. Declare `RipResponse<T>` instead of
`T` (or `CompletableFuture<RipResponse<T>>` for an async method) when a call
also needs the status code or a response header:

```java
@GET("https://api.example.com/users/{id}")
RipResponse<User> getUser(@PathParam("id") String id);
```

```java
RipResponse<User> response = userApi.getUser("42");
System.out.println(response.getStatus());               // e.g. 200
System.out.println(response.getHeader("ETag"));          // first value, case-insensitive lookup
System.out.println(response.getHeaders());                // Map<String, List<String>>, every value
User user = response.getBody();                          // decoded exactly like a plain T return
```

`T` is decoded by the same rules as a plain return type (`String` for the
raw body, `Void` to discard it, anything else deserialized from JSON).
`RipResponse<T>` only ever wraps a successful response — a non-2xx status
still throws `RestInPeaceHttpException` as described below, it's never
wrapped.

## Error handling

A response outside the 200–299 range always throws
`RestInPeaceHttpException`, whatever the method's return type — including
`void`:

```java
try {
    User user = userApi.getUser("42");
} catch (RestInPeaceHttpException e) {
    System.out.println(e.getStatus());     // e.g. 404
    System.out.println(e.getRawBody());    // the raw response body, always available
}
```

By default `getErrorBody()` just returns the same raw body as `getRawBody()`.
Annotate the method with `@ErrorType` to have the error body deserialized
into a class instead, the same way a successful response is deserialized
into the return type:

```java
@GET("/users/{id}")
@ErrorType(ApiError.class)
User getUser(@PathParam("id") String id);
```

```java
catch (RestInPeaceHttpException e) {
    ApiError error = e.getErrorBody();
    System.out.println(error.code);
}
```

`getErrorBody()` is an unchecked generic getter — it trusts the caller to
ask for the same type the method's `@ErrorType` declared (or `String` if it
has none). A transport failure (no response at all - a connection refused,
a timeout) throws the underlying transport exception directly, not
`RestInPeaceHttpException`, which specifically means "the server answered,
and the answer was an error."

## Async

Return `CompletableFuture<T>` instead of `T` to fire the request without
blocking the calling thread — `T` follows the same rules as a synchronous
return type (`String` for the raw body, anything else deserialized from
JSON):

```java
@GET("https://api.example.com/users/{id}")
CompletableFuture<User> getUserAsync(@PathParam("id") String id);
```

```java
CompletableFuture<User> future = userApi.getUserAsync("42");
future.thenAccept(user -> System.out.println(user.name));
```

A raw `CompletableFuture` (no type parameter) fails validation — the
library needs to know what to deserialize the response into.

Making any async call starts Unirest's async HTTP client on non-daemon
threads, so a short-lived program (a script, a CLI tool) won't exit on its
own afterward. Two ways to deal with that:

- Call `RIP.useDaemonThreadsForAsync()` once at startup, before making any
  async call — daemon threads don't keep the JVM alive, so your program
  exits normally once its own work is done. Not the default, since it
  reconfigures Unirest's shared global client; skip this if your app
  already configures Unirest's async client itself.
- Or call `kong.unirest.Unirest.shutDown()` when you're done making
  requests.

## Retries

Annotate a method with `@Retry` to re-issue a request that fails with a
transport error (connection refused, timeout) or one of a configurable set
of status codes:

```java
@GET("https://api.example.com/users/{id}")
@Retry(times = 3, delayMillis = 200, backoffMultiplier = 2.0)
User getUser(@PathParam("id") String id);
```

`times` is the maximum number of attempts in total (default 3); `delayMillis`
is how long to wait before the first retry (default 200); `backoffMultiplier`
is what that delay is multiplied by after each attempt (default 2.0 - use
`1.0` for a fixed delay instead of exponential backoff). `retryOnStatus`
controls which HTTP status codes count as retryable (default `429, 502, 503,
504`) - a transport error is always retried regardless of this list. `times`
must be at least 1, or the method fails validation.

`@Retry` works on both a synchronous return type and a `CompletableFuture`
one - retrying an async call schedules the next attempt on a background
thread instead of blocking the caller. Every attempt, including ones that
get retried, is still reported to any registered interceptor's
`afterResponse`, so a `LoggingInterceptor` or similar sees each individual
attempt, not just the final outcome.

## Timeouts

Annotate a method with `@Timeout` to override the connect/read timeout for
that method's calls only — an endpoint whose expected latency doesn't match
the rest of the client (a slow report-export endpoint, a health check that
should fail fast):

```java
@GET("https://api.example.com/reports/export")
@Timeout(readMillis = 120_000)
String exportReport();
```

`connectMillis` and `readMillis` are independent — set one, both, or
neither — and both default to `-1`, meaning "leave this one at whatever it
would otherwise be." `@Timeout` takes priority over a
[`RipClientConfig`](#per-client-configuration-timeout-and-proxy)'s timeout,
which in turn takes priority over the shared client's own configured
default. A negative value other than `-1` fails validation.

## Per-client configuration: timeout and proxy

Pass a `RipClientConfig` to `getClient(...)` instead of a plain `String`
base URL when a client's environment differs in more than just its base
URL — a connect/read timeout, or a proxy:

```java
UserApi prodApi = RIP.getClient(UserApi.class, RipClientConfig.builder()
        .baseUrl(prodBaseUrl)
        .connectTimeoutMillis(2_000)
        .readTimeoutMillis(10_000)
        .proxy("proxy.example.com", 8080)   // or proxy(host, port, username, password)
        .build());
```

Every setting is optional and independent. Setting a connect/read timeout
or a proxy gives that client its own dedicated Unirest client instance
(its own connection pool) instead of sharing the app-wide static one — a
config with only `baseUrl` set keeps sharing it, same as
`RIP.getClient(Class, String)`. Precedence is the same three-tier shape as
`@BaseUrl`'s: a method's own setting (`@Timeout`, or an absolute URL) beats
this config, which beats whatever's left as the shared client's own
default.

For anything `RipClientConfig` doesn't cover — TLS/mutual-TLS, connection
pooling, cookies, compression, and everything else `kong.unirest.Config`
exposes — configure `kong.unirest.Unirest`'s shared client directly (it's
a hard dependency, always on the classpath) before making any calls, the
same way you'd set a JSON `ObjectMapper`: RIP's JSON (de)serialization
delegates entirely to whatever `ObjectMapper` is configured on the
relevant Unirest client, which defaults to a `Gson`-backed one (bundled
transitively via Unirest) unless you configure your own.

## Interceptors

Register a global hook that runs on every request/response made through
RIP, without touching any `@RestClient` interface:

```java
RIP.addInterceptor(new RequestInterceptor() {
    @Override
    public void beforeRequest(RequestContext context) {
        context.addHeader("Authorization", "Bearer " + currentToken());
    }

    @Override
    public void afterResponse(RequestContext context, int status, Object body) {
        System.out.println(context.getHttpMethod() + " " + context.getUrl() + " -> " + status);
    }
});
```

Both methods are observers: `beforeRequest` can add headers or abort the
call by throwing, and `afterResponse` sees the status and response body (a
`String`, a deserialized object, or `null` for `void` methods) once the
response is back — but neither can cause a request to be re-sent on its
own; see [`@Retry`](#retries) above for that. On an error response,
`afterResponse` still runs and sees the same body a catch block would get
from [`RestInPeaceHttpException.getErrorBody()`](#error-handling) - the raw
body, or the `@ErrorType`-deserialized one if the method declares it - the
call to `afterResponse` happens before the exception is thrown.
`RIP.clearInterceptors()` removes everything that's registered.

When several interceptors are registered, they run "onion"-style: `beforeRequest`
runs in registration order, but `afterResponse` runs in the *reverse* order —
the first interceptor registered wraps every other one and is the last to see
the response. Register an interceptor first if it needs to bracket everything
else's work (e.g. a timer measuring total call overhead); register it last if
it needs to sit closest to the actual network call (e.g. a timer measuring
only network latency).

### Pre-built interceptors

A few ready-to-use interceptors cover the common cases so you don't have to
write a `RequestInterceptor` from scratch:

```java
// Attach a header to every request - useful for auth tokens.
RIP.addInterceptor(new HeaderInterceptor("Authorization", "Bearer " + currentToken()));

// Or pass a Supplier when the value can change between calls (e.g. a
// token that gets refreshed) - it's re-evaluated on every request.
RIP.addInterceptor(new HeaderInterceptor("Authorization", () -> currentToken()));

// Need several headers? Register them all in one interceptor instead
// of one HeaderInterceptor per header.
Map<String, String> staticHeaders = new LinkedHashMap<>();
staticHeaders.put("X-Api-Key", "abc123");
staticHeaders.put("X-Client-Version", "1.2.3");
RIP.addInterceptor(HeaderInterceptor.of(staticHeaders));

// Or a Map<String, Supplier<String>> when some of those values can
// change between calls.
Map<String, Supplier<String>> headerSuppliers = new LinkedHashMap<>();
headerSuppliers.put("Authorization", () -> "Bearer " + currentToken());
headerSuppliers.put("X-Client-Version", () -> "1.2.3");
RIP.addInterceptor(new HeaderInterceptor(headerSuppliers));

// Log a line before each request goes out and another when its
// response comes back, including elapsed time.
RIP.addInterceptor(new LoggingInterceptor());

// Or route log lines wherever you want instead of System.out.
RIP.addInterceptor(new LoggingInterceptor(logger::info));

// Attach a fresh correlation/request ID to every call - useful for
// tracing across service boundaries. Defaults to a random UUID under
// the X-Request-Id header.
RIP.addInterceptor(new CorrelationIdInterceptor());

// Or use a custom header name and/or ID generator.
RIP.addInterceptor(new CorrelationIdInterceptor("X-Trace-Id", () -> traceIdGenerator.next()));
```

`CorrelationIdInterceptor` also stashes the generated ID on the
`RequestContext` under `CorrelationIdInterceptor.ID_ATTRIBUTE`, so another
interceptor registered alongside it (e.g. your own logging or metrics
interceptor) can read it back via `context.getAttribute(...)` to correlate
its own output with the same call.

## Building from source

```bash
mvn clean test
```

## License

[MIT](LICENSE)
