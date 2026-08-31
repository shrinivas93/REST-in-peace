# REST-in-peace

A Simple, Declarative and Peaceful REST Client

REST-in-peace lets you declare a REST API as a plain Java interface and get a
working HTTP client for it at runtime — no hand-written request-building
boilerplate. Annotate an interface, call `RIP.getClient(...)`, and invoke its
methods like any other Java call.

## Features

- Declarative REST clients defined as annotated Java interfaces
- `@BaseUrl` declares a base URL once instead of repeating it on every method
- All seven common HTTP verbs: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`,
  `HEAD`, `OPTIONS`
- `@PathParam`, `@QueryParam`, `@HeaderParam`, and `@Body` parameter binding
- Optional params with `required` and `defaultValue`
- Request bodies: raw strings are sent as-is, other objects are
  JSON-serialized automatically
- Responses: a `String` return type gives you the raw body; any other
  return type is deserialized from JSON automatically
- `CompletableFuture<T>` return types fire requests asynchronously
- `@Retry` re-issues a failed request with configurable backoff, for both
  synchronous and async methods
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

### `@QueryParam`

Appends a query string parameter. Supports `required` (throws at call time
if no value and no default is available) and `defaultValue` (used when the
argument is `null`):

```java
@GET("https://api.example.com/items")
String search(@QueryParam(value = "q", required = true) String query,
               @QueryParam(value = "page", defaultValue = "1") Integer page);
```

### `@HeaderParam`

Sets an HTTP header, with the same `required`/`defaultValue` semantics as
`@QueryParam`:

```java
@GET("https://api.example.com/items")
String search(@HeaderParam(value = "Authorization", required = true) String token);
```

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
as JSON, the same way `@Body` serializes non-`String` request bodies.

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
own; see [`@Retry`](#retries) above for that. `RIP.clearInterceptors()`
removes everything that's registered.

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
