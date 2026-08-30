# REST-in-peace

A Simple, Declarative and Peaceful REST Client

REST-in-peace lets you declare a REST API as a plain Java interface and get a
working HTTP client for it at runtime — no hand-written request-building
boilerplate. Annotate an interface, call `RIP.getClient(...)`, and invoke its
methods like any other Java call.

## Features

- Declarative REST clients defined as annotated Java interfaces
- All seven common HTTP verbs: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`,
  `HEAD`, `OPTIONS`
- `@PathParam`, `@QueryParam`, `@HeaderParam`, and `@Body` parameter binding
- Optional params with `required` and `defaultValue`
- Request bodies: raw strings are sent as-is, other objects are
  JSON-serialized automatically
- Responses: a `String` return type gives you the raw body; any other
  return type is deserialized from JSON automatically
- `CompletableFuture<T>` return types fire requests asynchronously
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

Both methods are observers, not a retry pipeline: `beforeRequest` can add
headers or abort the call by throwing, and `afterResponse` sees the status
and response body (a `String`, a deserialized object, or `null` for `void`
methods) once the response is back — but neither can cause a request to be
re-sent, so this isn't a mechanism for retry policies. `RIP.clearInterceptors()`
removes everything that's registered.

## Building from source

```bash
mvn clean test
```

## License

[MIT](LICENSE)
