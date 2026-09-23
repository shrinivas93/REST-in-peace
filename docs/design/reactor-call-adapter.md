# Design: pluggable `CallAdapter` return types, with Project Reactor as the first consumer

Status: **design only - nothing in this doc has landed.** Parked in
`ROADMAP.md` until now for two open questions (below); this doc resolves
both, adds a full worked design for the general `CallAdapter` SPI plus a
concrete `rest-in-peace-reactor` module built on it, and lays out a chunked
rollout plan (§13). No code has been written yet.

**A naming collision worth flagging immediately**, since this doc otherwise
uses "reactor" constantly: Maven's own multi-module build unit is also
called a "reactor" (the root `pom.xml`'s own header comment already calls
`core`/`spring-boot-starter` "both inherit their `<version>` from here" as
part of "this reactor"). Every use of *Reactor* (capitalized, or written out
as **Project Reactor**) in this doc means the reactive library
(`io.projectreactor:reactor-core`, `Mono`/`Flux`); every use of *reactor*
(lowercase) talking about Maven means the multi-module build. The new
module this doc proposes is named `rest-in-peace-reactor` precisely because
it sits in RIP's Maven reactor *and* adapts to Project Reactor - the name
carries both meanings on purpose, but the doc calls out which one applies
anywhere it could be ambiguous.

## 1. Problem

`RequestExecutor.processRestRequest` (the reflective dispatch path's central
method) decides what a `@RestClient` method call returns via a hardcoded
chain of `if (returnType == X.class)` checks: `CompletableFuture`,
`RipResponse`, `byte[]`, `File`, and a final fallback branch that handles a
plain `String`/POJO/`void` (and, per the E9 rollout, an already-recognized
generic collection like `List<User>`). `RestClientProcessor` (the
compile-time codegen path) mirrors the same fixed vocabulary via its own
`nonAsyncReturnModelOf`. Adding *any* new return-type shape today means
editing both of those files directly - a change to RIP core itself, not
something a consumer can do unilaterally.

That's fine as long as the vocabulary covers what consumers need. It stops
being fine the moment a consumer's own codebase is already built around a
different asynchronous/reactive style than `CompletableFuture<T>` -
overwhelmingly, in the JVM ecosystem, Project Reactor's `Mono<T>`/`Flux<T>`
(the reactive type Spring WebFlux, R2DBC, and Spring Cloud Gateway all
standardize on) or RxJava's `Single<T>`/`Observable<T>`. Without a return
type escape hatch, that consumer either wraps every RIP call by hand at
every call site (`Mono.fromFuture(() -> api.getUser(id))`, repeated
everywhere a client is used) or avoids RIP for exactly the calls where its
declarative, boilerplate-eliminating value would matter most - a Spring
WebFlux controller's own downstream calls.

A second, independent problem surfaced while grounding this design against
the actual code (not a design-doc-only complaint - see §4): **an
unrecognized return type doesn't fail cleanly today.** A method declared to
return `Mono<User>` compiles fine, passes `ReflectiveRestClientValidator`
without a single check firing (`validateReturnType` only special-cases
`CompletableFuture`/`RipResponse`), and then falls into
`processRestRequest`'s final fallback branch, which calls
`responseDecoder.decodeOrThrow(response, errorType, genericReturnType)` -
attempting to Gson-deserialize the response's raw JSON body directly into a
`Mono` object. Gson can often *construct* an arbitrary class via
`Unsafe`-backed reflection even with no matching fields, so this doesn't
reliably throw - it can silently hand back a broken, un-subscribed
`Mono`-shaped object that was never actually built by Reactor's own
factory methods, misbehaving in ways that are hard to trace back to "the
return type was never a supported shape" at all. RIP's own established
philosophy is the opposite of this: an unsupported shape should be rejected
*by name*, at the earliest possible point, the same way `@Paginated`
already rejects `PaginationStrategy<T>` combined with an async first fetch
rather than letting either misbehave silently. §8's validation rules close
this gap as part of the same change that adds `CallAdapter` support, not as
an afterthought.

## 2. Goals

- A small, dependency-free **`CallAdapter<T>`/`CallAdapterFactory`** SPI in
  `core` (§5) that lets a consumer register support for a return type RIP
  itself has never heard of, without editing `RequestExecutor` or
  `RestClientProcessor` - the same "bring your own X, RIP stays
  unopinionated" shape already shipped for `MetricsSink` (metrics backend),
  `CircuitBreakerProvider`/`BulkheadProvider` (resilience backend), and
  `PaginationStrategy<T>` (pagination logic).
- A concrete, real consumer of that SPI shipped as part of this same
  design: **`rest-in-peace-reactor`**, a new optional Maven module
  (sibling to `rest-in-peace-spring-boot-starter`) adding `Mono<T>`/`Flux<T>`
  return-type support, with `reactor-core` as its only new dependency -
  never added to `core`'s own `pom.xml` (§10).
- `Mono<T>` support that's provably correct with essentially no new dispatch
  logic, by reusing RIP's *already-genuinely-async* `CompletableFuture<T>`
  path verbatim (§6) - not a second HTTP-calling implementation.
- `Flux<T>` support covering both the shape every consumer expects on sight
  (a single response whose body is a JSON array, streamed item-by-item) and
  the shape that's actually novel and valuable: a `@Paginated` method
  auto-flattened into a `Flux<T>` that respects Reactor's own backpressure
  protocol, fetching page N+1 only once the subscriber has actually asked
  for more items than the buffered pages contain (§7).
- Both dispatch paths stay correct: the reflective proxy resolves a
  registered `CallAdapterFactory` at runtime (§8.1); compile-time codegen
  disqualifies an adapter-shaped return type to the reflective fallback -
  and, per §4's grounding, **already does this today, unconditionally, with
  no code change needed** (§8.2). That's this doc's biggest concrete
  finding, not an aspiration.
- An unrecognized return type - `Mono<T>`/`Flux<T>` with
  `rest-in-peace-reactor` never added, or any other type nothing claims -
  is rejected at validation time, by name, instead of silently
  misdecoding (§8.3) - closing the real gap found in §1, as a byproduct of
  building this feature properly rather than a separate, unscoped cleanup.
- Full interaction correctness with every existing per-call feature -
  `@Retry` (including the idempotency-key work), cache, circuit breaker,
  bulkhead, interceptors - with the exact same "the adapted call goes
  through the identical pipeline as any other call" guarantee the
  pagination and circuit-breaker/bulkhead design docs already established
  for their own features (§9).

## 3. Non-goals

- **RxJava, or any reactive library besides Project Reactor, in this
  rollout.** The `CallAdapter` SPI (§5) is library-agnostic by
  construction - an RxJava `Single<T>`/`Observable<T>` adapter is a
  plausible future `rest-in-peace-rxjava` module built the same way - but
  this doc scopes its own rollout plan (§13) to Reactor only, per the
  ROADMAP note's own trigger ("revisit once there's a concrete
  RxJava/Reactor consumer"). Building two reactive adapters speculatively,
  with no second concrete consumer yet, repeats the exact mistake this
  item was parked to avoid in the first place.
- **Kotlin `suspend fun` support.** Resolved explicitly, not merely
  deferred - see §12. A Kotlin coroutine's `suspend fun` isn't
  `CallAdapter`-shaped at the bytecode level at all (the Kotlin compiler
  rewrites it to accept a `Continuation<T>` parameter and return `Object`),
  so it was never in scope for this SPI regardless of which reactive
  library is added first.
- **Reactor Netty, or any change to RIP's transport layer.** RIP's HTTP
  transport stays Unirest (backed by Apache HttpClient) for both dispatch
  paths, completely unchanged. `rest-in-peace-reactor` only wraps the
  *result* of a call already dispatched through Unirest's existing
  sync/async client in a `Mono`/`Flux` - it never touches how the bytes
  actually move over the wire, and adds no Netty dependency anywhere.
- **A fully async iteration protocol for `Page<T>` in the declarative
  pagination path** (`Page<T>.next()` itself returning something
  async). That's `pagination-helper.md` §11's own still-open item, owned by
  that doc. What *is* in scope here (§7.2) is a `Flux<T>` return type
  auto-flattening a `@Paginated` method's pages - a different, narrower
  integration that reuses `PaginationCoordinator`'s existing page-fetch
  loop rather than redesigning `Page<T>`'s own contract.
- **A general-purpose reactive extension of every RIP feature** (a
  `Flux`-returning interceptor hook, a reactive `Cache` SPI, etc.). Scoped
  strictly to return-type adaptation, matching how narrowly
  `PaginationStrategy<T>`/`CircuitBreakerProvider` scoped their own escape
  hatches.

## 4. Grounding: what the code actually does today

Both open questions in the original ROADMAP parking note assumed things
about `RequestExecutor`/`RestClientProcessor` that are worth verifying
directly against the current code rather than re-guessing at design time -
this section is the result of that verification, and it changes the shape
of §8 meaningfully.

### 4.1 The reflective dispatch path's hardcoded chain

`RequestExecutor.processRestRequest` (after resolving the URL and applying
timeout/headers/idempotency-key/params/interceptors) reads:

```java
Class<?> returnType = method.getReturnType();
if (returnType == CompletableFuture.class) {
    return processAsync(request, method, args, context);
}
if (returnType == RipResponse.class) { /* ... */ }
if (returnType == byte[].class) { /* ... */ }
if (returnType == File.class) { /* ... */ }
// fallback: decode genericReturnType directly (String/POJO/void, and
// already-supported generic collections like List<User>)
```

A `CallAdapter`-claimed return type (`Mono<User>`, say) needs one new check
inserted into exactly this chain, before the final fallback: "does some
registered `CallAdapterFactory` claim this return type's raw class? If so,
hand off to it instead of falling through to `responseDecoder.decodeOrThrow`
with a type it was never built to handle." §8.1 designs that check.

### 4.2 The compile-time codegen path already disqualifies this, today, unconditionally

`RestClientProcessor.nonAsyncReturnModelOf` (the method deciding whether a
return type is codegen-supported) contains this, already shipped, already
covering `Mono<T>`/`Flux<T>` with zero changes:

```java
if (!declaredType.getTypeArguments().isEmpty()) {
    return null; // some other generic type (e.g. List<User>, a nested CompletableFuture) isn't supported
}
```

`Mono<User>` is a `DeclaredType` with one type argument, its raw type name
isn't `com.shri.restinpeace.RipResponse`, so this check fires and returns
`null` - the same path a raw `List<User>` or a nested
`CompletableFuture<CompletableFuture<T>>` already takes. `null` here means
the method fails `toSupportedMethodModel`, gets added to `fallbackMethods`
(not `methods`), and the generated `_RipImpl` class defers it to the
reflective proxy via `RIP.getClient`'s existing `tryGeneratedImpl` ->
`Proxy.newProxyInstance` fallback (E9's own already-shipped mechanism for
exactly this "some methods generated, some methods reflective, same
interface" split).

**This resolves the ROADMAP note's first open question more cleanly than
the note itself expected.** The note framed this as "an adapter-produced
return type would need to be one more entry in the existing disqualify
list" - implying a code change. It doesn't need one: the existing
"any generic type with type arguments that isn't `RipResponse<T>`" check is
already broad enough to catch `Mono<T>`/`Flux<T>` by construction, since
neither is (or ever will be) special-cased there. §13's rollout plan
still adds a **regression test** asserting this stays true (a compile-time
codegen fixture with a `Mono<User>`-returning method, asserting it lands in
`fallbackMethods` and the reflective proxy answers the call correctly) -
worth locking in explicitly, not just relying on an absence of code to keep
being an absence of code across future refactors.

### 4.3 The async dispatch path is genuinely non-blocking, not a thread-pool wrapper

`processAsync`'s branches all bottom out in `retryExecutor.executeAsyncWithRetry`
wrapping `request::asStringAsync`/`request::asBytesAsync` - Unirest's own
async client methods, backed by Apache HttpClient's async request
execution, not `CompletableFuture.supplyAsync(() -> blockingCall())` on a
thread pool. This matters enormously for §6: it means a `Mono<T>` adapter
doesn't need its own transport-level implementation at all - it can be a
pure, thin wrapper around the `CompletableFuture<T>` this path already
produces, with the same non-blocking guarantee carrying through unchanged.

### 4.4 The reflective validator doesn't check return types broadly today

`ReflectiveRestClientValidator.validateReturnType` only special-cases
`CompletableFuture`/`RipResponse` (checking their type arguments are
themselves decodable). Nothing currently rejects an arbitrary unsupported
generic return type - confirming §1's silent-misdecode problem is real, not
hypothetical, and that §8.3's new validation rule is closing a genuine gap
rather than adding redundant strictness.

## 5. The general `CallAdapter` SPI (lives in `core`, zero new dependencies)

Mirrors Retrofit's own `CallAdapter.Factory` shape (the established,
proven precedent for exactly this problem in the JVM HTTP-client-library
space), adapted to RIP's own vocabulary and dispatch model:

```java
package com.shri.restinpeace;

import java.lang.reflect.Type;

/**
 * Adapts a RIP call's outcome into a consumer-chosen return type T, for a
 * return-type shape RIP itself has no built-in support for. Registered via
 * {@link RIP#addCallAdapterFactory(CallAdapterFactory)}. See
 * docs/design/reactor-call-adapter.md.
 *
 * @param <T> the adapted return type this instance produces
 */
public interface CallAdapter<T> {

    /**
     * The type to decode the HTTP response body into - what {@code T}
     * itself wraps (e.g. for a {@code Mono<User>} adapter, this returns
     * {@code User.class}), the same role {@code RipResponse<T>}'s own
     * inner-type resolution already plays for that return type.
     */
    Type responseBodyType();

    /**
     * Adapts one call. {@code delegate} is the exact
     * {@code CompletableFuture<Object>} RIP's own async dispatch path
     * (§4.3, §6) already produces for {@code responseBodyType()} - the
     * decoded body on success, completed exceptionally with the same
     * exception RIP throws for any other return type on failure
     * (transport failure, {@link com.shri.restinpeace.exception.RestInPeaceHttpException},
     * {@link com.shri.restinpeace.exception.CircuitOpenException}, {@link
     * com.shri.restinpeace.exception.BulkheadFullException}). An adapter
     * never dispatches its own HTTP call - it only ever wraps the future
     * RIP itself already produced, guaranteeing the adapted call passes
     * through the identical pipeline (§9) as every other RIP call.
     *
     * @param delegate the in-flight call's future
     * @return the adapted value ({@code T}) to return from the annotated
     *         method
     */
    T adapt(java.util.concurrent.CompletableFuture<Object> delegate);
}
```

```java
package com.shri.restinpeace;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Recognizes a method's declared return type and produces a {@link
 * CallAdapter} for it, or declines. Registered via {@link
 * RIP#addCallAdapterFactory(CallAdapterFactory)}; every registered factory
 * is consulted, in registration order, the first non-empty {@link
 * Optional} wins - mirroring {@link com.shri.restinpeace.interceptor.RequestInterceptor}'s
 * own "registration order matters, first short-circuit wins" convention.
 */
public interface CallAdapterFactory {

    /**
     * @param method the interface method being dispatched
     * @return an adapter for {@code method}'s return type, or {@link
     *         Optional#empty()} to decline (RIP tries the next registered
     *         factory, then its own built-in shapes, in that order - see
     *         §8.1)
     */
    Optional<CallAdapter<?>> get(Method method);
}
```

### 5.1 Why `adapt` takes the delegate future, not a `Supplier`/raw dispatch hook

The original ROADMAP-era sketch (`T adapt(Supplier<Object> rawCall)`) was
revised during this design pass: a `Supplier<Object>` implies the adapter
*invokes* the call itself, which would let an adapter implementation call
it zero times, twice, or lazily in a way that breaks §9's pipeline
guarantees (an adapter that calls `rawCall.get()` twice would make two real
HTTP requests for one logical call, silently). Taking the already-in-flight
`CompletableFuture<Object>` instead means RIP has *already* dispatched
exactly one call - through the exact same `RequestExecutor.processAsync`
path any other `CompletableFuture<T>`-returning method uses - by the time
any adapter code runs at all. An adapter can only ever *transform* that one
call's outcome, never re-issue or skip it. This is the same reasoning
`PaginationStrategy<T>` already applies (the lambda only *answers a
question*, the coordinator owns the actual re-invocation) applied to this
SPI's own escape-hatch boundary.

### 5.2 Registration: global, mirroring interceptors - not per-client

```java
// In RIP.java, alongside addInterceptor/removeInterceptor/clearInterceptors:
public static void addCallAdapterFactory(CallAdapterFactory factory) {
    RequestExecutor.addCallAdapterFactory(factory);
}
public static void removeCallAdapterFactory(CallAdapterFactory factory) {
    RequestExecutor.removeCallAdapterFactory(factory);
}
public static void clearCallAdapterFactories() {
    RequestExecutor.clearCallAdapterFactories();
}
```

Deliberately **not** a `RipClientConfig` per-client setting, unlike
`CircuitBreakerProvider`/`BulkheadProvider`/`Cache`. Those are properties of
*one downstream* (does this API need a circuit breaker, does this API's
responses get cached) - genuinely different per client. Which
programming-model shape a consumer's code is written in (`Mono<T>` vs.
`CompletableFuture<T>`) is an application-wide choice, the same category
`RequestInterceptor`/`ObjectMapper`/`useDaemonThreadsForAsync` already
occupy as global, call-once-at-startup settings. A consumer wanting
`Mono<T>` for one client and `CompletableFuture<T>` for another simply
declares each interface's methods with the return type it wants - the
registry doesn't need to be scoped narrower than "process-wide" for that to
work, since resolution (§8.1) happens per-method, by declared type, not
per-client-instance.

## 6. `Mono<T>`: a thin wrapper over the existing async path, not a new implementation

The central engineering decision, made possible entirely by §4.3's
finding. `rest-in-peace-reactor`'s `Mono` adapter factory:

```java
package com.shri.restinpeace.reactor;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Optional;
import reactor.core.publisher.Mono;
import com.shri.restinpeace.CallAdapter;
import com.shri.restinpeace.CallAdapterFactory;

public final class MonoCallAdapterFactory implements CallAdapterFactory {

    @Override
    public Optional<CallAdapter<?>> get(Method method) {
        if (method.getReturnType() != Mono.class) {
            return Optional.empty();
        }
        java.lang.reflect.Type innerType =
                ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
        return Optional.of(new CallAdapter<Mono<Object>>() {
            @Override
            public java.lang.reflect.Type responseBodyType() {
                return innerType;
            }
            @Override
            public Mono<Object> adapt(java.util.concurrent.CompletableFuture<Object> delegate) {
                return Mono.fromFuture(() -> delegate);
            }
        });
    }
}
```

That's the entire dispatch-level implementation. `Mono.fromFuture` already
does exactly what's needed: subscribing triggers nothing extra (the
`CompletableFuture` RIP handed over is *already in flight* the moment
`adapt` is called, per §5.1 - `Mono.fromFuture`'s normal "don't start work
until subscribed" laziness doesn't apply here, which is worth documenting
loudly, see §6.1), completion propagates as `onNext`+`onComplete`, and an
exceptional completion (any `RestInPeaceHttpException`/
`CircuitOpenException`/`BulkheadFullException`/transport exception RIP
would otherwise throw) propagates as `onError` with that exact same
exception - no translation layer, no new exception type. Cancellation
(§6.2) is the one piece `Mono.fromFuture` doesn't give for free and needs
its own small wrapper.

### 6.1 `Mono<T>` is eager, not lazy - documented loudly, not silently different from Reactor norms

This is the one place where `rest-in-peace-reactor`'s `Mono<T>` behaves
differently from idiomatic Reactor code, and it needs to be impossible to
miss in the javadoc/README, not just correct: an ordinary `Mono<T>` in
Reactor does nothing until subscribed (`Mono.defer`/cold-publisher
semantics are the whole point of the library). `api.getUser(id)` here,
however, is a **method call that returns a `Mono`**, following RIP's
existing convention (§9's own consistency requirement) that calling any
`@RestClient` method *immediately* dispatches the HTTP request - exactly
like `CompletableFuture<T>` already does elsewhere in RIP. The returned
`Mono<T>` is *hot* from the moment the method returns: the request already
went out; subscribing only observes the outcome, it doesn't trigger the
call. Calling the method twice makes two real HTTP requests, calling it
once and subscribing to the result twice does not make a second request
(ordinary `Mono` replay-to-multiple-subscribers semantics still apply once
it's been created - only the *timing* of dispatch relative to subscription
is unusual, not the multicast behavior after that point). A consumer who
wants Reactor's usual defer-until-subscribed semantics wraps it themselves:
`Mono.defer(() -> api.getUser(id))` builds a fresh, undispatched-until-
subscribed `Mono` around the eager call, the same wrapping trick any
Reactor codebase already reaches for when adapting an eager API.

### 6.2 Cancellation: disposing the `Mono` cancels the underlying HTTP call

`CompletableFuture` itself supports `cancel(boolean)`, and Unirest's async
client (backing §4.3's genuinely non-blocking dispatch) honors cancelling
the in-flight `HttpAsyncClient` request when the `CompletableFuture`
wrapping it is cancelled. `Mono.fromFuture`, however, does **not**
propagate a downstream `Mono` cancellation (a `.timeout()` firing, a
subscriber calling `Disposable.dispose()`) back into cancelling the
wrapped future by default - a real gap worth closing explicitly rather than
inheriting silently:

```java
@Override
public Mono<Object> adapt(CompletableFuture<Object> delegate) {
    return Mono.create(sink -> {
        delegate.whenComplete((value, error) -> {
            if (error != null) {
                sink.error(unwrap(error)); // CompletableFuture wraps in CompletionException; unwrap it
            } else {
                sink.success(value);
            }
        });
        sink.onCancel(() -> delegate.cancel(true));
    });
}
```

`Mono.create` (not `Mono.fromFuture`) is the actual shipped implementation
for this reason - `sink.onCancel(...)` is exactly the hook needed to wire a
downstream cancellation into `CompletableFuture#cancel(true)`, which in
turn genuinely aborts the underlying Apache HttpClient async request rather
than just abandoning interest in a result that keeps computing anyway. This
means a `.timeout(Duration.ofSeconds(2))` on a `Mono<T>`-returning RIP call
genuinely stops the outbound HTTP call at 2 seconds, not merely stops
*waiting* for it - a real, user-visible correctness property worth a
dedicated integration test in §13.

## 7. `Flux<T>`: two genuinely different shapes, both real, neither optional

### 7.1 Flavor 1 - a single response whose body is a JSON array

The shape every consumer expects first: `Flux<Order> listOrders()` for a
`GET /orders` endpoint whose body is (or contains, via a dotted path) a
JSON array. Built on the exact same one-call dispatch as `Mono<T>` -
decode the response body as `List<T>` (RIP's existing generic-collection
decoding, E9/E12), then `Flux.fromIterable`:

```java
public final class FluxListCallAdapterFactory implements CallAdapterFactory {
    @Override
    public Optional<CallAdapter<?>> get(Method method) {
        if (method.getReturnType() != Flux.class) {
            return Optional.empty();
        }
        java.lang.reflect.Type itemType =
                ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
        java.lang.reflect.Type listType = /* List<itemType>, built via ParameterizedType wrapping */;
        return Optional.of(new CallAdapter<Flux<Object>>() {
            public java.lang.reflect.Type responseBodyType() { return listType; }
            @SuppressWarnings("unchecked")
            public Flux<Object> adapt(CompletableFuture<Object> delegate) {
                return Mono.<List<Object>>create(sink -> {
                    delegate.whenComplete((value, error) -> {
                        if (error != null) sink.error(unwrap(error));
                        else sink.success((List<Object>) value);
                    });
                    sink.onCancel(() -> delegate.cancel(true));
                }).flatMapMany(Flux::fromIterable);
            }
        });
    }
}
```

This flavor has **no real backpressure** to speak of - the entire list is
already in memory (RIP decoded the whole response body before this adapter
ever runs), `Flux.fromIterable` just emits the already-materialized items
on request. That's an honest limitation to document, not hide: this flavor
of `Flux<T>` is a *convenience* (emit items one at a time instead of
handling a `List<T>` yourself) for a consumer already writing
Reactor-style pipelines, not a memory-efficiency feature. §7.2 is where
`Flux<T>` earns its keep for that.

### 7.2 Flavor 2 - a `@Paginated` method auto-flattened into a genuinely backpressure-aware `Flux<T>`

The actually novel, valuable integration, and the reason this doc treats
`Flux<T>` as a first-class return type rather than sugar over Flavor 1.
`@Paginated` already has two return-type-driven flattening modes -
`Page<T>` (manual) and `Stream<T>`/`Iterator<T>` (eager-per-item-pull,
lazy-per-page) - see `pagination-helper.md` §6.4. `Flux<T>` is a **third**
mode on the exact same annotation, recognized the same
return-type-driven way:

```java
@GET("/orders")
@Paginated(itemsField = "orders", pointerField = "next_cursor")
Flux<Order> fluxOrders(@QueryParam("cursor") @PaginationCursor String cursor);
```

Implementation sketch, built on `PaginationCoordinator`'s existing
`fetchFirstPage`/page-fetch-callback machinery (`RequestExecutor.processPaginatedRequest`
already has `Stream<T>`/`Iterator<T>` branches calling
`paginationCoordinator.flattenToStream`/`flatten` against the same
`Supplier<Page<Object>> firstPageSupplier` - a `Flux<T>` branch is a third
sibling call, not new coordinator logic):

```java
public Flux<Object> flattenToFlux(Supplier<Page<Object>> firstPageSupplier) {
    return Flux.create(sink -> {
        AtomicReference<Page<Object>> currentPage = new AtomicReference<>();
        AtomicLong pendingRequests = new AtomicLong();
        sink.onRequest(n -> {
            pendingRequests.addAndGet(n);
            drain(sink, currentPage, pendingRequests, firstPageSupplier);
        });
    }, FluxSink.OverflowStrategy.ERROR);
}
```

The real design property this buys: **page N+1 is only fetched once the
subscriber has requested more items than every already-fetched page's
buffered-but-unemitted items can satisfy** - `onRequest(n)` is Reactor's
own backpressure signal, and `PaginationCoordinator.fetchFirstPage`/
`Page<T>.next()` (both already-blocking calls, per pagination's own design)
run on `Schedulers.boundedElastic()` (never the subscriber's own thread,
consistent with Reactor's documented rule that a blocking call inside
`Flux.create`'s callback must never run on a compute-bound scheduler) only
when a genuine gap between "items requested" and "items already buffered"
demands one. A subscriber requesting `Long.MAX_VALUE` (RxJava/Reactor's own
common "just give me everything" convention, e.g. `.subscribe(consumer)`
with no explicit `request(n)`) degrades gracefully to "fetch every page as
fast as the downstream allows," matching `Stream<T>`'s own current
eagerness under that same usage pattern - no worse than what already
ships, strictly better (real backpressure) when a consumer's downstream
genuinely can't keep up and calls `request(n)` deliberately.

`hasNext()`/exhaustion reuses `Page<T>`'s existing termination logic
(`pagination-helper.md` §6.5) completely unchanged - `Flux<T>` is a
different *consumption* shape over the identical page sequence, not a
different pagination algorithm. Cancellation (a `.take(50)` or an explicit
`Disposable.dispose()`) stops fetching further pages - never mid-page (a
partially-decoded page's items still make it to the buffer/subscriber if
already in hand, per ordinary Reactor `Flux.create` cancellation
semantics), and, per §6.2's same reasoning, cancels the in-flight page
fetch if one is genuinely in progress when cancellation arrives.

### 7.3 Disambiguating the two flavors: return type alone isn't enough

Both flavors declare `Flux<Order>`. `FluxListCallAdapterFactory` (7.1) and
the pagination-specific handling (7.2) can't both claim every `Flux<T>`
method by declared type - the distinguishing signal already exists
elsewhere on the method: **`@Paginated`'s presence.** `RequestExecutor`
already branches on `method.getAnnotation(Paginated.class) != null` as the
very first check in `processRestRequest` (§4.1), before any return-type
dispatch runs at all - a `Flux<T>`-returning method annotated `@Paginated`
routes to §7.2's pagination-aware flattening (inside
`processPaginatedRequest`, alongside the existing `Stream`/`Iterator`
branches, never reaching `CallAdapterFactory` resolution at all); a plain
`Flux<T>`-returning method with no `@Paginated` routes to §7.1's
`CallAdapterFactory`-based single-response flattening. No ambiguity, no new
disambiguation mechanism needed - this is exactly the same
"annotation-presence-first, then return-type" precedence
`processRestRequest` already has for `Page<T>`/`Stream<T>`/`Iterator<T>`
vs. every other return type, extended by one more return type without
touching the precedence rule itself.

## 8. Where this plugs into dispatch, and what changes in each path

### 8.1 Reflective path: one new check, inserted before the final fallback

```java
// In RequestExecutor.processRestRequest, after the existing
// CompletableFuture/RipResponse/byte[]/File checks, before the fallback:
Optional<CallAdapter<?>> adapter = resolveCallAdapter(method); // consults the global registry, in registration order
if (adapter.isPresent()) {
    CompletableFuture<?> delegate = processAsync(request, method, args, context, adapter.get().responseBodyType());
    @SuppressWarnings("unchecked")
    CompletableFuture<Object> untyped = (CompletableFuture<Object>) delegate;
    return adapter.get().adapt(untyped);
}
```

`processAsync` already exists (§4.3) and already takes everything it needs
except the response body type to decode into, which every other call site
gets from `resolveFutureInnerType(method)` (reading a real
`CompletableFuture<T>`'s own type argument) - this new call site is the
first to need that type supplied externally (from
`CallAdapter.responseBodyType()`) rather than read off the method's own
generic signature, since the method's declared return type here is
`Mono<T>`/`Flux<T>`, not `CompletableFuture<T>`. A small overload of
`processAsync` accepting the decode type explicitly (rather than deriving
it from the method) is the concrete, minimal-diff way to share the
existing implementation - not a parallel copy of `processAsync`'s dispatch
logic.

### 8.2 Compile-time codegen path: already correct (§4.2), gains only a regression test

No production code change to `RestClientProcessor`. §13's rollout plan adds
a fixture test (a `@RestClient` interface with one `Mono<User>`-returning
method alongside ordinary codegen-supported methods) asserting: (a) the
`Mono`-returning method lands in `fallbackMethods`, not `methods`; (b) the
generated `_RipImpl` class still compiles and correctly generates every
other method; (c) `RIP.getClient(...)` against that interface answers the
`Mono`-returning method correctly via the reflective proxy while every
other method still uses the generated implementation - proving E9's
"partial fallback, not whole-interface fallback" guarantee holds for this
new shape too, not just for a raw `List<User>`.

### 8.3 Validation: reject an unclaimed, unrecognized return type by name

New rule in `ReflectiveRestClientValidator.validateReturnType` (mirrored,
per the established convention, in `CompileTimeRestClientValidator` -
though the compile-time side can only check its own *static* whitelist,
since a `CallAdapterFactory` is registered at runtime, long after
annotation processing finished; see below):

```java
// Reflective side - runs at RIP.getClient() time, after any startup-time
// RIP.addCallAdapterFactory(...) calls have already registered factories,
// mirroring the existing "configure globals before building any client"
// convention (setObjectMapper/setCache/useDaemonThreadsForAsync):
Class<?> returnType = method.getReturnType();
if (isKnownBuiltInShape(returnType) /* CompletableFuture, RipResponse, byte[], File,
                                        void/String/POJO, List<T>, Page/Stream/Iterator */) {
    return; // existing behavior, unchanged
}
if (!declaredType.getTypeArguments().isEmpty()
        && RequestExecutor.resolveCallAdapter(method).isEmpty()) {
    validationResult.addError(String.format(
        "The method %s.%s returns %s, which RIP has no built-in support for and no "
        + "registered CallAdapterFactory claims. If this is a Mono<T>/Flux<T>, add the "
        + "rest-in-peace-reactor dependency and call RIP.addCallAdapterFactory(...) "
        + "before building this client.",
        method.getDeclaringClass().getName(), method.getName(), returnType));
}
```

**Compile-time codegen can't run this exact check** - it has no visibility
into a runtime-registered `CallAdapterFactory` during annotation
processing, which is precisely why §4.2's disqualify-to-reflective-fallback
behavior exists in the first place (this is the ROADMAP note's *first* open
question, restated: the two dispatch paths pull in different directions
here, and the resolution is that compile-time codegen simply never tries to
answer "is this return type valid" for an adapter-shaped method at all -
it defers the entire question, validation included, to the reflective
proxy it falls back to). `CompileTimeRestClientValidator` needs no new rule
for this case - it already lets an unrecognized generic return type through
undiagnosed at the annotation-processing stage (there is no equivalent of
`ReflectiveRestClientValidator.validate` invoked automatically at codegen
time the way `RIP.getClient()` invokes it for the reflective path); the
method reaching the reflective proxy via fallback is what causes the
*reflective* validator above to run and catch it correctly, the first time
that interface is actually loaded via `RIP.getClient(...)`. Worth stating
explicitly as the real answer, not a gap: **the reflective validator is the
single source of truth for "is this return type actually supported,"
regardless of which dispatch path a given method ultimately executes
through** - exactly mirroring how `@Paginated`/`PaginationStrategy<T>`
validation already works today.

One precise scoping question this raises, listed rather than silently
assumed: exactly which "known built-in shapes" the reflective check above
needs to whitelist explicitly (so genuinely-supported generic collections
like `List<User>` don't start false-positive-rejecting) is an
implementation-detail enumeration, not a design-doc-level decision - flagged
in §12 as the one open question needing that enumeration written out
against the actual current `responseDecoder`/`RestClientProcessor` support
matrix before this rule ships.

## 9. Interaction with existing features

- **`@Retry`**: applies to the underlying `CompletableFuture<T>` dispatch
  exactly as it already does for any other async call (§4.3) - an
  adapter's `Mono<T>`/`Flux<T>` never bypasses it, since the adapter only
  ever wraps the future `processAsync` already produced *after* retry has
  run its course. `@Retry(idempotent = true)`'s idempotency-key behavior is
  unaffected for the same reason - it's applied before the future the
  adapter receives is even constructed.
  **A real double-retry hazard worth documenting loudly**: a consumer who
  also chains Reactor's own `.retryWhen(...)` on top of an
  already-`@Retry`-annotated method's `Mono<T>` gets retries compounding
  (RIP's own `@Retry` retries the HTTP call N times *inside* one logical
  `Mono` emission; Reactor's `.retryWhen` then re-subscribes to that whole
  already-retried `Mono` M more times) - documented explicitly as "pick
  one: either `@Retry` for the HTTP-level retry, or your own
  `.retryWhen(...)` for a higher-level policy (a fallback to a different
  client, business-logic-aware retry) - don't stack both for the same
  failure."
- **Cache**: identical - a cached `GET` response completes the same
  `CompletableFuture<T>` faster (served from cache, no network call);
  `Mono<T>`/`Flux<T>` never know or care whether the underlying call was
  actually served from cache.
- **Circuit breaker / bulkhead**: an open circuit or a full bulkhead
  completes the delegate future exceptionally
  (`CircuitOpenException`/`BulkheadFullException`, per the existing async
  wraps documented in `circuit-breaker-bulkhead.md` §7) exactly as for any
  other `CompletableFuture`-returning call - propagates as `Mono.error`/
  `Flux.error` with that exact exception, catchable via
  `.onErrorResume(CircuitOpenException.class, ex -> fallback)` the same way
  a consumer already catches it from a plain `CompletableFuture`.
- **Interceptors**: fire exactly once per underlying call, same as any
  other `CompletableFuture`-returning method - a `Mono<T>` subscribed to
  multiple times doesn't re-fire interceptors (per §6.1, the call already
  happened once, before any subscription).
- **Pagination**: §7.2 covers `Flux<T>` as a first-class `@Paginated`
  return type. `Mono<Page<T>>`/`Mono<Stream<T>>`/`Mono<Iterator<T>>` are
  **not** part of this rollout - `pagination-helper.md` §6.4.1 already
  covers `CompletableFuture<Page<T>>` for an async first fetch; a
  `Mono`-wrapped equivalent would be pure sugar over that
  (`Mono.fromFuture(() -> api.listOrdersAsync(cursor))`) a consumer can
  already write today with zero new RIP code, so it isn't a `CallAdapter`
  in its own right worth shipping.
- **`MockRestServer`**: no changes needed. A test exercising a
  `Mono<T>`/`Flux<T>`-returning method registers routes/responses exactly
  as any other test does; `StepVerifier` (Reactor's own standard test
  utility) subscribes to the result the same way a consumer's production
  code would. §13 adds worked examples, not new test infrastructure.

## 10. Module layout: `rest-in-peace-reactor`

Sibling to `rest-in-peace-spring-boot-starter` in every structural respect
(§4's grounding already confirmed the parent POM/versioning precedent this
follows exactly):

```
rest-in-peace-reactor/
  pom.xml                 # <parent> = rest-in-peace-parent, same version, same release cadence
  src/main/java/com/shri/restinpeace/reactor/
    MonoCallAdapterFactory.java
    FluxListCallAdapterFactory.java
    ReactorPagination.java      # the Flux<T>-over-@Paginated wiring (§7.2), package-private glue
  src/test/java/...
```

- **Only new dependency: `io.projectreactor:reactor-core`.** Never added
  to `core`'s own `pom.xml` - the same "zero-dependency default, opt-in
  module for the integration" shape already established for
  `rest-in-peace-spring-boot-starter` (Spring Boot 4.x) and the
  `CircuitBreakerProvider`/`BulkheadProvider` javadoc's own
  resilience4j-adapter example (never a real dependency anywhere in
  `core`).
- **Registration is one call, documented as a startup-time requirement**
  (matching `useDaemonThreadsForAsync`'s own "call once, before building
  any client" convention):
  ```java
  RestInPeaceReactor.register(); // calls RIP.addCallAdapterFactory(...) for both factories
  ```
  A single `RestInPeaceReactor.register()` entry point (rather than asking
  a consumer to call `RIP.addCallAdapterFactory` twice, once per factory)
  matches how a Spring Boot starter's own auto-configuration hides its
  wiring behind one annotation - here, one static method call, since this
  module deliberately has no Spring dependency of its own and must work in
  a plain-Java consumer exactly as well as a Spring one.
- **Spring Boot starter interop**: `rest-in-peace-spring-boot-starter`
  itself gets no changes in this rollout - a Spring WebFlux consumer using
  both starters simply calls `RestInPeaceReactor.register()` once
  (e.g. in a `@PostConstruct` or the application's `main`, before any
  `@RestClient` bean is constructed) alongside the starter's own
  auto-configuration. A dedicated
  `rest-in-peace-reactor-spring-boot-starter` auto-configuration module
  (calling `register()` automatically, the way the plain Spring starter
  already auto-configures `useDaemonThreadsForAsync()`) is plausible follow-on
  work, deliberately not bundled into this rollout's own chunks (§13) to
  keep this design's first landing minimal and reviewable.
- **A `samples/reactor-consumer` sample module** (mirroring
  `samples/spring-boot-consumer`'s own precedent - a standalone Maven
  project depending on the published artifacts like a real downstream
  consumer, not a reactor-internal module) is planned as part of §13's
  rollout, not before it - `samples/spring-boot-consumer` itself only
  landed once the starter's own chunks were feature-complete (§8 of
  `spring-boot-starter.md`), and this follows the same order.

## 11. Exhaustive usage examples

Every shape this design supports, worked end-to-end. `orderApi`/`userApi`
below are ordinary `RIP.getClient(...)`-built clients; nothing about
*building* a client changes - only the declared return type on individual
methods.

### 11.1 Basic `Mono<T>`

```java
@RestClient
@BaseUrl("https://api.example.com")
interface UserApi {
    @GET("/users/{id}")
    Mono<User> getUser(@PathParam("id") String id);
}

UserApi api = RIP.getClient(UserApi.class);
api.getUser("42")
   .doOnNext(user -> log.info("got {}", user.getName()))
   .subscribe();
```

### 11.2 `Mono<Void>` - fire-and-forget

```java
@POST("/events")
Mono<Void> fireEvent(@Body Event event);

api.fireEvent(new Event("order.shipped")).subscribe();
```

### 11.3 `Mono<RipResponse<T>>` - status/headers, async

```java
@GET("/users/{id}")
Mono<RipResponse<User>> getUserWithResponse(@PathParam("id") String id);

api.getUserWithResponse("42").subscribe(response -> {
    response.getStatus();               // 200
    response.getHeader("ETag");
    User user = response.getBody();
});
```

`RipResponse<T>`'s own inner-type resolution (already shipped, used by the
existing `CompletableFuture<RipResponse<T>>` shape per E12) is reused
verbatim - `MonoCallAdapterFactory` just needs to recognize
`Mono<RipResponse<T>>` and resolve `T` one level deeper, mirroring exactly
how `processAsync`'s own `isRipResponseType`/`resolveWrappedType` already
do this for `CompletableFuture<RipResponse<T>>`.

### 11.4 `Mono<byte[]>` / a download

```java
@GET("/reports/{id}/pdf")
Mono<byte[]> downloadReport(@PathParam("id") String id);

api.downloadReport("42").subscribe(bytes -> Files.write(path, bytes));
```

### 11.5 Explicit deferred semantics (§6.1)

```java
// Eager - dispatches immediately, subscribing only observes the outcome:
Mono<User> eager = api.getUser("42");

// Deferred - dispatches only once subscribed, standard Reactor idiom:
Mono<User> deferred = Mono.defer(() -> api.getUser("42"));
deferred.subscribe();   // the HTTP call happens HERE, not at Mono.defer(...) construction
```

### 11.6 Error handling: distinguishing failure kinds

```java
api.getUser("missing")
   .onErrorResume(RestInPeaceHttpException.class, ex -> {
       if (ex.getStatus() == 404) {
           return Mono.empty();   // treat "not found" as an empty result, not a pipeline failure
       }
       return Mono.error(ex);     // any other status - propagate
   })
   .onErrorResume(CircuitOpenException.class, ex -> Mono.just(User.placeholder()))
   .subscribe(user -> render(user), error -> log.error("unrecoverable", error));
```

### 11.7 Cancellation genuinely aborts the HTTP call (§6.2)

```java
Disposable call = api.getUser("42").subscribe(user -> render(user));
// ... user navigates away before the response arrives ...
call.dispose();   // the underlying Apache HttpClient async request is cancelled, not just abandoned

api.getUser("42")
   .timeout(Duration.ofSeconds(2))
   .subscribe(user -> render(user), error -> showTimeoutError());
// a slow downstream past 2s: the outbound call itself is aborted at 2s, not merely ignored after
```

### 11.8 `@Retry` + `Mono<T>` - the correct combination (§9)

```java
@GET("/orders/{id}")
@Retry(times = 3, retryOnStatus = {502, 503}, idempotent = true)
Mono<Order> getOrder(@PathParam("id") String id);

// @Retry already handles the HTTP-level retry (3 attempts, with a
// consistent Idempotency-Key across all 3) entirely inside this one Mono
// emission - do NOT also chain .retryWhen(...) here for the same failures:
api.getOrder("42").subscribe(order -> process(order));

// A higher-level policy on top (e.g. fall back to a cache after RIP's own
// retries are exhausted) is a legitimate reason to still use Reactor's own
// operators - just not for the same failure @Retry already owns:
api.getOrder("42")
   .onErrorResume(ex -> Mono.fromCallable(() -> localCache.get("42")))
   .subscribe(order -> process(order));
```

### 11.9 `Flux<T>` - single response, list-flattening (§7.1)

```java
@GET("/users")
Flux<User> listUsers();

userApi.listUsers()
       .filter(u -> u.isActive())
       .subscribe(u -> render(u));
```

### 11.10 `Flux<T>` - `@Paginated` auto-flatten with real backpressure (§7.2)

```java
@GET("/orders")
@Paginated(itemsField = "orders", pointerField = "next_cursor")
Flux<Order> fluxOrders(@QueryParam("cursor") @PaginationCursor String cursor);

orderApi.fluxOrders(null)
        .limitRate(20)              // request 20 at a time - page N+1 fetched only once needed
        .subscribe(order -> slowDownstreamProcessor.accept(order));

// Take just the first 50 matching orders across however many pages that
// spans, without ever fetching a page beyond what's actually needed:
orderApi.fluxOrders(null)
        .filter(o -> "shipped".equals(o.getStatus()))
        .take(50)
        .collectList()
        .subscribe(orders -> render(orders));
```

### 11.11 Combining multiple RIP calls reactively

```java
Mono<UserProfile> profile = Mono.zip(
        userApi.getUser(id),
        orderApi.getOrderHistory(id).collectList(),
        (user, orders) -> new UserProfile(user, orders));

profile.subscribe(p -> render(p));
```

### 11.12 Testing with `MockRestServer` + `StepVerifier`

```java
@ExtendWith(MockRestServerExtension.class)
class UserApiReactorTest {
    @Test
    void getUser_emitsDecodedUser(MockRestServer server) {
        server.on(HTTPMethod.GET, "/users/{id}", MockResponse.json(new User("42", "Shrinivas")));
        UserApi api = RIP.getClient(UserApi.class, server.baseUrl());

        StepVerifier.create(api.getUser("42"))
                .expectNextMatches(user -> "Shrinivas".equals(user.getName()))
                .verifyComplete();
    }

    @Test
    void getUser_propagatesHttpExceptionAsMonoError(MockRestServer server) {
        server.on(HTTPMethod.GET, "/users/{id}", MockResponse.status(404, "{}"));
        UserApi api = RIP.getClient(UserApi.class, server.baseUrl());

        StepVerifier.create(api.getUser("missing"))
                .expectErrorMatches(ex -> ex instanceof RestInPeaceHttpException
                        && ((RestInPeaceHttpException) ex).getStatus() == 404)
                .verify();
    }

    @Test
    void fluxOrders_fetchesOnlyAsManyPagesAsRequested(MockRestServer server) {
        server.onPages(HTTPMethod.GET, "/orders",
                MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"c2\"}"),
                MockResponse.ok("{\"orders\":[{\"id\":\"2\"}],\"next_cursor\":\"c3\"}"),
                MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
        OrderApi api = RIP.getClient(OrderApi.class, server.baseUrl());

        StepVerifier.create(api.fluxOrders(null), 1)   // request exactly 1 item up front
                .expectNextCount(1)
                .thenRequest(1)
                .expectNextCount(1)
                .thenCancel()
                .verify();

        assertEquals(2, server.countOf(HTTPMethod.GET, "/orders")); // page 3 never fetched - proves backpressure
    }
}
```

## 12. Kotlin `suspend fun` - explicitly out of scope, not merely deferred

Restated from the ROADMAP note, resolved here rather than left implicit:
Kotlin's compiler lowers a `suspend fun` to a JVM method taking an extra
`Continuation<T>` parameter and returning `Object` (or the coroutine's own
suspension marker) - it is not `CallAdapter`-shaped, at the bytecode level,
under any circumstances. A `CallAdapterFactory` resolves by *declared
return type*; a suspend function's declared JVM return type is `Object`,
carrying zero information about what a Kotlin caller actually receives.
Supporting Kotlin coroutines properly needs a `kotlinx-coroutines-core`
`Continuation`-aware integration - closer in shape to a **Kotlin compiler
plugin or a hand-written `suspendCancellableCoroutine` wrapper generated
per method** than anything this SPI can express. This doc's scope stops at
the JVM-visible `CallAdapter` mechanism; a Kotlin-coroutines module, if one
is ever built, is tracked as its own separate, unstarted roadmap item, not
a follow-on chunk of this one.

## 13. Open questions

- **The exact "known built-in shapes" whitelist for §8.3's validation
  rule.** Needs to be enumerated against the current, real support matrix
  across `responseDecoder`, `RestClientProcessor`'s E9 collection support,
  and pagination's `Page`/`Stream`/`Iterator` types before the rule ships -
  an implementation-detail enumeration task, not a design-doc-level
  decision (the same category §8 of `circuit-breaker-bulkhead.md` already
  put its own async-bulkhead-permit shape question in).
- **Whether `rest-in-peace-reactor-spring-boot-starter`** (auto-calling
  `RestInPeaceReactor.register()` the way the plain Spring starter
  auto-calls `useDaemonThreadsForAsync()`) ships as part of this rollout or
  as genuinely separate follow-on work. §10 leans toward separate, to keep
  this design's first landing reviewable - revisit once §13's chunks 2-4
  are real and a concrete Spring WebFlux consumer is asking for it.
- **RxJava as a second `CallAdapterFactory` consumer.** Explicitly
  non-goal (§3) for *this* rollout, but the general SPI (§5) was
  deliberately built library-agnostic specifically so a future
  `rest-in-peace-rxjava` module needs no changes to `core` either -
  worth confirming, once Reactor support has shipped and stabilized, that
  a from-scratch RxJava `Single`/`Observable` adapter genuinely needs zero
  `CallAdapter`/`CallAdapterFactory` changes, only a new implementing
  module - the same kind of "was the SPI actually general enough" check
  `CircuitBreakerProvider`/`BulkheadProvider` implicitly passed by being
  usable for "any other implementation," not just resilience4j.

## 14. Rollout plan (chunked)

Mirrors the chunking convention every other design doc in this repository
uses - each chunk its own PR, verified and merged before the next starts.

1. **This design doc.**
2. **The general `CallAdapter`/`CallAdapterFactory` SPI in `core`** (§5),
   plus `RIP.addCallAdapterFactory`/`removeCallAdapterFactory`/
   `clearCallAdapterFactories` (§5.2), the new dispatch hook in
   `RequestExecutor.processRestRequest` (§8.1), and §8.3's validation rule
   (with §13's whitelist enumeration done as part of this chunk, not
   deferred). Verified with a hand-written test `CallAdapterFactory`
   (no Reactor dependency needed for this chunk at all) proving
   registration, resolution order (first non-empty answer wins), the
   validation rejection message for an unclaimed type, and that removing a
   factory correctly reverts to that rejection.
3. **`rest-in-peace-reactor`: `Mono<T>`**, including `Mono<Void>`,
   `Mono<RipResponse<T>>`, `Mono<byte[]>` (§6, §6.1, §6.2). Verified via
   `StepVerifier`-based tests against a real `MockRestServer`, including a
   dedicated cancellation test proving a disposed `Mono`/a fired
   `.timeout(...)` genuinely aborts the in-flight Apache HttpClient
   request (asserting via `MockRestServer`'s own recorded-request/timing
   facilities that the mock server observes a genuinely severed
   connection, not merely an ignored response).
4. **`rest-in-peace-reactor`: `Flux<T>` flavor 1** (§7.1, single-response
   list flattening) and **flavor 2** (§7.2, `@Paginated` auto-flatten with
   real backpressure), plus §7.3's disambiguation (already-existing
   `@Paginated`-presence-first precedence, no new code needed for the
   disambiguation itself). Verified via the backpressure-proving test
   style in §11.12's `fluxOrders_fetchesOnlyAsManyPagesAsRequested` example
   - the core deliverable of this chunk is that test genuinely passing
   against a real `PaginationCoordinator`-driven `Flux`, not just compiling.
5. **Compile-time codegen regression test** (§8.2) - a fixture proving
   `Mono<T>`/`Flux<T>` methods land in `fallbackMethods` today, unchanged,
   locking in §4.2's finding against future `RestClientProcessor`
   refactors accidentally narrowing or widening that disqualification
   boundary.
6. **`samples/reactor-consumer`** (§10) plus documentation - a
   `docs/design/reactor-call-adapter.md` Status-line update, a new
   "Reactive (Project Reactor)" section in the core README mirroring the
   Pagination section's own depth, and a new field-guide category in
   `docs/getting-started.html` (matching the pattern the pagination
   feature's own documentation gap - since fixed - illustrated the cost of
   skipping).
