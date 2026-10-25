> Published on dev.to: https://dev.to/shrinivas93/stop-writing-rest-client-boilerplate-in-java-28jn
>
> Images referenced below are the same files checked into this repo under
> [`docs/assets/`](../assets/).

# Stop Writing REST Client Boilerplate in Java

Every Java project ends up with the same file eventually. You know the one — `ApiClient.java`, `HttpUtil.java`, `SomeServiceClient.java`. It opens an `HttpClient`, builds a request, sets a few headers, fires it off, checks the status code by hand, and manually deserializes the response body. Then you copy-paste that same shape for the next endpoint. And the next.

```java
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/users/" + id))
    .header("Accept", "application/json")
    .GET()
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
if (response.statusCode() != 200) {
    throw new RuntimeException("Request failed: " + response.statusCode());
}
User user = objectMapper.readValue(response.body(), User.class);
```

Multiply that by every endpoint you call, add retry logic by hand because the network is unreliable, and you've got a few hundred lines of code that do nothing interesting.

I got tired of writing this, so I built **[REST-in-peace](https://github.com/shrinivas93/REST-in-peace)** (RIP) — a declarative REST client for Java. You describe the API as an interface, and RIP gives you back a working implementation.

```java
@RestClient
@BaseUrl("https://api.example.com")
public interface UserApi {

    @GET("/users/{id}")
    User getUser(@PathParam("id") String id);
}
```

```java
UserApi userApi = RIP.getClient(UserApi.class);
User user = userApi.getUser("42");
```

That's the whole thing. No `HttpClient` setup, no manual JSON parsing, no status-code checking. Here's what it actually looks like running:

![Declaring a UserApi interface and calling it with RIP.getClient](https://raw.githubusercontent.com/shrinivas93/REST-in-peace/master/docs/assets/demo.gif)

## It's not just another Retrofit clone

If you've used Retrofit or Feign, the interface-based idea will feel familiar — annotate a method, get an HTTP call. The part I actually built RIP around is what happens *underneath* that interface.

**Compile-time code generation, with a reflective fallback.** By default, RIP implements your interface at compile time via an annotation processor — you get a real generated class, not a runtime-built dynamic proxy. That means no reflection on your hot path, and no proxy-generation cost at startup. If you'd rather skip the build-time step, the reflective proxy is still there and works identically.

**GraalVM native-image just works.** Because the generated implementation is real compiled bytecode instead of a runtime proxy, there's no reflect-config JSON to hand-write for RIP's own client-generation path. Build a native image the normal way and it works.

**Resilience is built in, not bolted on.** Retry with backoff, a circuit breaker, a bulkhead, idempotency keys for safe retries on non-idempotent-looking calls — all as annotations on the interface, not a separate library you have to wire in yourself.

```java
@RestClient
@BaseUrl("https://api.example.com")
public interface OrderApi {

    @GET("/orders/{id}")
    @Retry(times = 3)
    Order getOrder(@PathParam("id") String id);
}
```

**Response caching with stale-while-revalidate and negative caching**, out of the box, configurable per client or per call.

**A pluggable interceptor chain** for logging, auth headers, request signing, or short-circuiting a call entirely (handy for feature flags or local overrides).

**A Spring Boot starter**, if that's your world — annotate the interface, inject it like any other bean, zero hand-written `@Bean` methods.

**Reactive support** via `rest-in-peace-reactor` — any method returning `Mono<T>` or `Flux<T>` just works once you register the call adapter.

**An OpenAPI-to-`@RestClient` generator**, so if you already have a spec, you don't have to hand-write the interface at all.

**A real local test server** (`MockRestServer`) for integration-style tests, plus a JUnit 5 extension, so you're not mocking `HttpClient` directly.

## How it compares

![REST-in-peace vs. Retrofit, Feign/OpenFeign, and RestTemplate/WebClient](https://raw.githubusercontent.com/shrinivas93/REST-in-peace/master/docs/assets/comparison-card.png)

## Try it

```xml
<dependency>
    <groupId>io.github.shrinivas93</groupId>
    <artifactId>rest-in-peace</artifactId>
    <version>1.0.0.63</version>
</dependency>
```

```groovy
implementation("io.github.shrinivas93:rest-in-peace:1.0.0.63")
```

It's on Maven Central, MIT licensed, and works from Java, Kotlin, or any other JVM language that can implement a Java interface.

- **GitHub:** https://github.com/shrinivas93/REST-in-peace
- **Field guide** (every feature, plain Java and Spring side by side): https://shrinivas93.github.io/REST-in-peace/
- **Javadoc:** https://shrinivas93.github.io/REST-in-peace/apidocs/

If you try it and something's confusing, missing, or just annoying, I'd genuinely like to hear about it — open an issue or start a [discussion](https://github.com/shrinivas93/REST-in-peace/discussions).
