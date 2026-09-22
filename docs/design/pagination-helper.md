# Design: Pagination helper

Status: **chunks 2-6 of the rollout plan (§12) have landed.** `@Paginated`,
`@PaginationCursor`, `Page<T>`, `Stream<T>`/`Iterator<T>` auto-flattening,
and the three enums in §6.1 are real code - a `PointerKind.FULL_URL` or
`VALUE` pointer sourced from `PaginationSignalSource.RESPONSE_BODY`/
`RESPONSE_HEADER`/`ITEM_FIELD` (§6.6's keyset pagination, including an N-way
composite key resent via N separate carriers or one `@Body` carrier's
comma-separated `bodyField`, §6.7), resent via `@QueryParam`/`@PathParam`/
`@HeaderParam`/`@Body`, with `hasMoreSource`/`totalSource`/
`totalPagesSource` termination signals, synchronous only. A `FULL_URL`
pointer sourced from `RESPONSE_HEADER` transparently parses an RFC 8288
`Link` header and follows its `rel="next"` target (GitHub/Shopify REST,
row 1) - a header value that doesn't look like that format at all falls
back to being used as the next URL verbatim. A `Stream<T>`/`Iterator<T>`
return type is lazy - the first page (and every page after it) is only
fetched on first use, matching ordinary lazy-iterator/lazy-stream
semantics; `Page<T>` still fetches its first page eagerly, like any other
RIP call. Everything else in this doc - `PaginationAdvance` client-driven
advancement, `PaginationStrategy<T>`, an async first fetch, and
`MockRestServer` multi-page fixtures - is still design only, chunked per
§12; `RIP.getClient(...)` rejects a method using one of those not-yet-
supported shapes by name rather than silently misbehaving. The feature was
previously parked (see `ROADMAP.md`) after a first sketch (a fixed
`Page<T>` interface with `getItems()`/`getNextUrl()`) turned out not to be
generic enough for how differently
real APIs shape pagination. This doc replaces that sketch with a design
built from a deliberately exhaustive survey of real-world pagination
conventions (§5, 46 cataloged variants) plus a programmatic escape hatch
(§6.8) for whatever that survey still doesn't cover.

**Implementation note (§6.4.1):** `Page<T>.rawResponse()` returns
`RipResponse<Void>`, not the `HttpResponse<?>` sketched in §6.4's original
snippet - RIP's own status/headers vocabulary (already used everywhere
else in the public API) instead of leaking the underlying `kong.unirest`
client type. Its body is always `null` since `Page<T>.items()` already
carries the page's decoded content.

## 1. Problem

Every declarative REST client eventually needs an answer for "this endpoint
returns more results than fit in one response." Right now a RIP consumer
has to hand-write the fetch-extract-repeat loop themselves: issue the
first call, pull a `next` field or cursor out of the response by hand,
issue another call with it substituted in, decide when to stop, and repeat
- exactly the boilerplate RIP exists to eliminate everywhere else. The
`@Url` feature (already shipped) was explicitly justified in part by this
gap ("for a pagination `next` link... that isn't a fixed template") but
only removes the URL-templating piece; the loop itself, the item
extraction, and the termination logic are still entirely the consumer's
problem.

The reason this was parked rather than built the first time: real APIs
disagree, extensively and in incompatible ways, on **how** they communicate
pagination. Not just "the items field is named differently" (which a
single dotted-path attribute could fix) but along several genuinely
independent axes at once - *where* the signal lives (response header vs.
body, top-level vs. nested), *what kind* of signal it is (a full URL, a
bare cursor, a boolean flag, a total count, nothing at all), and *how* the
client is expected to send it back (query param, path param, request
header, request body field). A design that only covers "the common case"
well and leaves everything else to chance would ship a feature half the
audience immediately finds doesn't fit their API - worse than not shipping
it, since it invites a false sense that pagination is "handled."

## 2. Goals

- Cover the realistic combinatorial space of how real APIs signal
  pagination - enumerated exhaustively in §5/§9, not sampled - so that
  "does RIP support my API's pagination style" has a confident answer
  before a consumer starts integrating, not after.
- A single new method annotation, `@Paginated` (§6.2), expressive enough
  to declare the overwhelming majority of real-world shapes without any
  hand-written loop.
- Reuse existing RIP vocabulary wherever possible instead of inventing
  parallel concepts - the pagination-cursor parameter marker (§6.3) stacks
  on `@QueryParam`/`@PathParam`/`@HeaderParam`/`@Body`, the same four
  carriers a consumer already knows from every other feature, rather than
  four new annotations that duplicate them.
- A **build-your-own-default, pluggable override** shape, exactly matching
  the pattern the circuit-breaker/bulkhead design already established
  (`CircuitBreakerConfig` vs. `CircuitBreakerProvider`): `@Paginated`
  handles the declarative common cases; `PaginationStrategy<T>` (§6.8) is a
  full programmatic escape hatch for whatever the declarative model
  can't - or hasn't yet - anticipated. The two are mutually exclusive on
  one method, never a hybrid.
- Both `Page<T>` (manual, page-at-a-time control) and `Stream<T>`/
  `Iterator<T>` (auto-flattened across pages) as return-type-driven
  choices on the exact same annotation - no separate annotation attribute
  for something the type system already expresses.
- Every page fetch reuses the client's *entire* existing call pipeline -
  `@Retry`, cache, circuit breaker, bulkhead, interceptors - automatically,
  by construction, not as a feature added on top (§6.9).
- Both dispatch paths stay correct: reflective proxy fully supports
  `@Paginated`/`PaginationStrategy<T>`; compile-time codegen cleanly
  disqualifies these methods to the reflective fallback, per the
  established E9 pattern (§8.5) - not a gap, a deliberate scope boundary.

## 3. Non-goals

- **GraphQL pagination** (Relay-style cursor connections). RIP's entire
  call model is REST-shaped (`@GET`/`@POST`/etc. against a URL); a GraphQL
  query/mutation over a single POST endpoint with a query-language body is
  a different protocol, not a REST pagination variant. Cataloged in §9
  (row 23) for completeness, explicitly out of scope.
- **Bulk export / async job polling** and **streaming (SSE/WebSocket)** as
  alternatives to pagination entirely - a full-dataset export job or a
  push-based stream isn't a pagination *style*, it's a different feature
  a consumer might reach for instead of pagination. Not addressed here.
- **Automatic retry/resume of a truly stateful, expiring cursor** (e.g. an
  Elasticsearch scroll ID's TTL lapsing mid-iteration) beyond surfacing the
  real error - RIP doesn't restart a paginated fetch from scratch on
  expiry; that's an application-level decision, not something to guess at
  silently.
- **A response cache keyed across an entire paginated sequence** - each
  page fetch is cached independently (or not) per the client's existing
  cache configuration, exactly like any other call. No new "cache the
  whole result set" concept.

## 4. Personas and usage scenarios

- **The common case**: a consumer hitting a `GET /orders` endpoint that
  returns `{"orders": [...], "next": "https://api.example.com/orders?page=2"}`.
  Wants `@Paginated(itemsField = "orders", pointerField = "next")` and a
  `Page<Order>`/`Stream<Order>` return type, nothing more.
- **The Stripe-shaped consumer**: `has_more` boolean plus an object-ID
  cursor. Wants the boolean checked as the real authority, not "cursor
  absent," because Stripe's own docs are explicit that `has_more` is what
  to trust.
- **The GitHub-shaped consumer**: pagination lives entirely in the `Link`
  response header (RFC 5988), body is often just a bare JSON array with no
  wrapper object at all.
- **The homegrown-API consumer**: no cursor of any kind, just a `total`
  field and offset/limit query params the client has to track itself.
- **The "my API doesn't look like any of these" consumer**: needs to
  combine multiple signals with custom logic, decode a non-trivial cursor
  format, or branch pagination behavior on what was passed into the first
  call - reaches for `PaginationStrategy<T>` (§6.8) instead of fighting
  the declarative annotation into a shape it wasn't built for.

## 5. Why a flat annotation with a handful of attributes doesn't work

The parked first sketch (`@Paginated(itemsField, nextUrlField | nextCursorField, cursorQueryParam)`)
assumed one item-list field name and one pointer shape per method. Real
APIs vary along at least three genuinely independent axes at once:

1. **Where the *pointer* signal lives**: response header, response body
   (top-level or nested at an arbitrary dotted path), or derived from the
   last fetched item itself (keyset pagination - no dedicated field at
   all).
2. **What *kind* of signal it is**: a complete next-page URL, a bare
   cursor/token string, a real-data-derived value (an ID or timestamp), a
   page number, or nothing (client tracks its own offset).
3. **How the *client* is expected to resend it**: URL query param, URL
   path param, a request header, or a field inside a JSON request body.

These three axes are independent - a cursor can live in a response header
and be resent as a request body field; a total count can live in a
response header while the items themselves are resent via query param
offset. Treating them as one combined choice (as the original sketch
implicitly did) undercounts the real space badly. A fourth,
easy-to-miss axis compounds this: **termination** isn't always the same
thing as "the pointer is present." Some APIs (Stripe) keep the pointer
populated even on the genuinely last page and expect a *separate* boolean
to be the real authority; others give no pointer at all, only a total
count or total page count, and expect the client to do the arithmetic
itself.

§9 catalogs the resulting space exhaustively: 46 distinct, real-world-
grounded combinations. §6 designs the annotation surface to express that
space with the smallest number of new concepts, and §6.8 adds the
escape hatch for the residue that a fixed, closed vocabulary can never
fully anticipate.

## 6. Proposed architecture

### 6.1 Three enums, each reused rather than duplicated per attribute

```java
// Where does THIS particular signal come from? Reused for pointerSource,
// hasMoreSource, totalSource, and totalPagesSource below - one enum, four
// uses, not four renamed near-duplicates.
public enum PaginationSignalSource { RESPONSE_BODY, RESPONSE_HEADER, ITEM_FIELD, NONE }

// Is the extracted pointer a complete URL, or a bare value to re-inject
// into the next request via a carrier?
public enum PointerKind { FULL_URL, VALUE }

// When there's no server-given pointer at all (pointerSource = NONE), how
// does the client advance on its own?
public enum PaginationAdvance { NONE, INCREMENT_BY_PAGE_SIZE, INCREMENT_BY_ONE }
```

### 6.2 `@Paginated` - the method-level annotation

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Paginated {

    String itemsField() default "";        // dotted path; "" = response body IS the array

    PointerKind pointerKind() default PointerKind.VALUE;
    PaginationSignalSource pointerSource() default PaginationSignalSource.RESPONSE_BODY;
    String pointerField() default "";       // dotted path / header name / comma-separated item field(s)

    PaginationAdvance advance() default PaginationAdvance.NONE;
    int pageSize() default 0;               // only read when advance = INCREMENT_BY_PAGE_SIZE

    PaginationSignalSource hasMoreSource() default PaginationSignalSource.NONE;
    String hasMoreField() default "";

    PaginationSignalSource totalSource() default PaginationSignalSource.NONE;
    String totalField() default "";         // total RECORD count

    PaginationSignalSource totalPagesSource() default PaginationSignalSource.NONE;
    String totalPagesField() default "";    // total PAGE count
}
```

Every "source" attribute is independent, which is what makes the
split-signal rows in §9 (a `total` in a response header while items live
in the body, or `has_more` in a header while the cursor lives in the
body) fall out for free rather than needing a dedicated "hybrid" enum
value: each signal just declares its own source.

### 6.3 `@PaginationCursor` - the one new parameter annotation

The key usability decision in this design: **don't invent four new
parameter annotations for "where does the pagination value go."** RIP
already has that vocabulary - `@QueryParam`, `@PathParam`, `@HeaderParam`,
`@Body` - and a consumer integrating this feature already knows all four
from every other part of the library. `@PaginationCursor` stacks on top of
one of them, marking "and also, RIP should re-populate this parameter with
the freshly extracted next-page value before every subsequent call":

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaginationCursor {
    String bodyField() default "";   // only used when stacked on @Body - see §6.7
}
```

```java
@GET("/orders")
@Paginated(itemsField = "orders", pointerField = "next_cursor")
Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
```

On the first call, the consumer passes whatever they want (often `null`
or empty). On every subsequent page fetch - whether driven by
`Page<T>.next()` or internally by a `Stream<T>`/`Iterator<T>` return type -
RIP re-invokes the exact same method with every other argument held
exactly as the consumer originally supplied it, substituting only the
`@PaginationCursor`-marked argument with the extracted value.

### 6.4 Return type drives manual vs. auto-flattened iteration

No separate annotation attribute - the method's declared return type picks
the behavior, mirroring how `CompletableFuture<T>` vs. plain `T` already
picks sync vs. async elsewhere in RIP:

```java
public interface Page<T> {
    List<T> items();
    boolean hasNext();
    Page<T> next();                 // blocking fetch of the next page, through the full call pipeline
    RipResponse<Void> rawResponse(); // status/headers of the call that produced THIS page - see §6.4.1
}
```

```java
// Manual - caller controls when to advance, sees page-level metadata
@GET("/orders")
@Paginated(itemsField = "orders", pointerField = "next")
Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);

// Auto - lazily flattens every page into one Stream, fetched on demand
@GET("/orders")
@Paginated(itemsField = "orders", pointerField = "next")
Stream<Order> streamOrders(@QueryParam("cursor") @PaginationCursor String cursor);
```

`Iterator<T>` works identically to `Stream<T>` for a consumer who prefers
imperative iteration.

#### 6.4.1 Composing with `CompletableFuture<T>` and `RipResponse<T>`

**`CompletableFuture<Page<T>>` / `CompletableFuture<Stream<T>>` /
`CompletableFuture<Iterator<T>>`** - supported, but only the *first* page
fetch is actually async, matching how `CompletableFuture<T>` already
behaves everywhere else in RIP. `Page<T>.next()` stays a blocking call
(as declared above) once the future resolves and the consumer holds a
`Page<T>`/`Stream<T>`/`Iterator<T>` - walking to page 2 and beyond is
synchronous by construction (§6.9). A fully async iteration protocol (an
async `Page<T>.next()` returning its own `CompletableFuture<Page<T>>`) is
real, plausible future work, but a materially bigger feature than "the
first call can be async like any other RIP call" - left as the
still-open half of the async question in §11.

**`RipResponse<Page<T>>`** - deliberately *not* supported, resolving the
open question this doc originally posed about it. `RipResponse<T>` wraps
one call's metadata; after `Page<T>.next()` advances, that metadata would
describe page 1 while the consumer is looking at page 4 - stale and
misleading. `Page<T>.rawResponse()` above is the actual fix: it returns
the metadata of *whichever* page is currently held, correct across the
whole iteration instead of just the first page, and makes
`RipResponse<Page<T>>` unnecessary rather than merely redundant.

**`RipResponse<Stream<T>>` / `RipResponse<Iterator<T>>`** - rejected at
validation time (§7). Auto-flattening exists specifically to erase page
boundaries; a stream spans an unknown number of underlying calls, so there
is no single coherent response left to wrap. The validator error should
point at `Page<T>` + `rawResponse()` as the way to get per-fetch metadata
without giving up manual page control.

**No `CompletableFuture<RipResponse<Page<T>>>` triple-nesting.** RIP
already supports this depth of nesting elsewhere (`CompletableFuture<RipResponse<List<User>>>`,
per E12), so the precedent exists - but `Page<T>.rawResponse()` makes it
unnecessary here specifically: `CompletableFuture<Page<T>>` alone already
gives a consumer both an async first fetch and per-page response metadata,
with no added expressiveness from wrapping a third layer around it.

### 6.5 Termination precedence

Checked in this order on every fetched page; first match wins:

1. **`hasMoreSource` set** - read that boolean; authoritative, full stop.
   Needed because some APIs (Stripe) keep the pointer populated even on
   the actual last page.
2. **`totalSource` set** - compare a running `itemsFetchedSoFar` counter
   against the extracted total; stop once it's reached.
3. **`totalPagesSource` set** - same idea, compared against
   `pagesFetchedSoFar` instead (for APIs that hand back a page count
   directly, e.g. Algolia's `nbPages`, rather than a record count).
4. **Neither of the above** - fall back to pointer presence: stop when the
   extracted `pointerField`/`Link: rel="next"` is null or absent.
5. **Always, unconditionally, layered on top of whichever of 1-4
   applies**: if a fetched page's `items()` comes back empty, stop -
   guards against a server bug (stale `has_more: true`, wrong `total`)
   turning this into an infinite loop. Not configurable; it protects the
   consumer regardless of which termination signal was declared.

### 6.6 Keyset pagination, including composite (N-way, not fixed at two)

`pointerSource = ITEM_FIELD` extracts a value from the *last fetched item*
rather than a dedicated response field - covers `since_id`/`max_id`-style
and timestamp-cursor APIs. `pointerField` accepts a comma-separated list
for composite keys (an `(id, timestamp)` pair for a stable sort under
concurrent writes, or more - nothing in the mechanism caps it at two):

```java
@GET("/events")
@Paginated(itemsField = "events", pointerSource = ITEM_FIELD,
           pointerField = "id,createdAt,tenantId")
Page<Event> listEvents(
        @QueryParam("lastId") @PaginationCursor String lastId,
        @QueryParam("lastTs") @PaginationCursor String lastTimestamp,
        @QueryParam("tenant") @PaginationCursor String lastTenantId);
```

For query/path/header carriers, an N-way composite needs N separate
`@PaginationCursor`-marked parameters, positionally matched to the N
comma-separated `pointerField` entries - each of those carriers is
inherently one value per named slot. A JSON request body doesn't have
that restriction (§6.7).

### 6.7 The request-body carrier is `@Body Map<String,Object>` + `bodyField`, not `@Field`

**Correction to an earlier draft of this design**: `@Field`/
`@FormUrlEncoded` sends `application/x-www-form-urlencoded` - a
completely different content type from a JSON body. Nearly every
real-world "resend the cursor via the request body" case (Elasticsearch's
`search_after`/`from`, DynamoDB's `ExclusiveStartKey`) is actually JSON,
not form-urlencoded, so `@Field` would send the wrong content type
entirely. `@Body` already accepts any serializable value, including a
`Map<String,Object>` (Gson serializes it as a plain JSON object) - so the
cursor targets a field *inside* that map instead:

```java
@Target(ElementType.PARAMETER)
// (bodyField shown here for reference; declared once on @PaginationCursor in §6.3)
```

```java
@POST("/_search")
@Paginated(itemsField = "hits.hits", advance = INCREMENT_BY_PAGE_SIZE,
           pageSize = 50, totalField = "hits.total.value")
Page<Doc> search(@Body @PaginationCursor(bodyField = "from") Map<String,Object> body);
```

`bodyField` supports the same dotted-path nesting convention as
`itemsField`/`pointerField`, walked as a **set** operation on the same
JSON-tree structure the response side already walks as a **get** - one
symmetric utility, not two unrelated mechanisms. Composite keyset into a
JSON body is simpler than into query/path/header carriers, precisely
because a JSON object can hold multiple keys at once: one
`@PaginationCursor` on the one `Map` parameter, with a comma-separated
`bodyField` list positionally matched to the comma-separated
`pointerField` list:

```java
@POST("/events/search")
@Paginated(itemsField = "events", pointerSource = ITEM_FIELD, pointerField = "id,createdAt")
Page<Event> search(@Body @PaginationCursor(bodyField = "lastId,lastTimestamp") Map<String,Object> body);
```

`@Field` remains valid for the rare case where a paginated endpoint is
genuinely form-urlencoded - there, `@PaginationCursor` works bare (no
`bodyField`), identically to `@QueryParam`, since one `@Field` already
names exactly one form field with no wrapping object.

### 6.8 `PaginationStrategy<T>` - the programmatic escape hatch

Same **build-your-own-default, pluggable-override** shape already shipped
for the circuit breaker (`CircuitBreakerConfig` vs. `CircuitBreakerProvider`).
Since a lambda can never be an annotation attribute value (Java requires
compile-time constants there), it's recognized by **declared parameter
type**, the same idiom RIP already uses for `CompletableFuture<T>`/
`RipResponse<T>` return types - no marker annotation needed, RIP checks
the type:

```java
@FunctionalInterface
public interface PaginationStrategy<T> {
    // Consulted after every page fetch, including the first, to decide
    // whether to continue and what the next request should look like.
    Optional<PaginationRequest> nextRequest(PaginationContext<T> context);
}

public interface PaginationContext<T> {
    List<T> items();              // this page's decoded items
    JsonElement rawBody();        // parsed body tree, for arbitrary field access
    String header(String name);   // response header lookup
    int pagesFetchedSoFar();
    int itemsFetchedSoFar();
}

public interface PaginationRequest {
    static PaginationRequest toUrl(String url) { /* ... */ }
    static PaginationRequest withQueryParam(String name, Object value) { /* ... */ }
    static PaginationRequest withPathParam(String name, Object value) { /* ... */ }
    static PaginationRequest withHeader(String name, Object value) { /* ... */ }
    static PaginationRequest withBodyField(String dottedPath, Object value) { /* ... */ }
    PaginationRequest and(PaginationRequest other);   // combine multiple overrides
}
```

```java
@GET("/orders")
Page<Order> listOrders(@QueryParam("status") String status, PaginationStrategy<Order> strategy);
```

The lambda only answers "what should the next request look like" - the
actual re-invocation, and everything that comes with it (§6.9), stays
owned by the same coordinator the declarative path uses. `@Paginated` and
a `PaginationStrategy<T>` parameter are mutually exclusive on one method
(same "config vs. provider, pick one" precedent as
`CircuitBreakerConfig`/`CircuitBreakerProvider`); combining them is a
validation error (§7).

### 6.9 Execution model: pagination is a thin loop around the existing call pipeline, not a second one

This is the design's central engineering decision. `Page<T>.next()` (or
the internal loop driving `Stream<T>`/`Iterator<T>`) doesn't make raw HTTP
calls of its own - it **re-invokes the exact same annotated method**,
substituting only the `@PaginationCursor`-marked argument (or, for a
`FULL_URL` pointer, following the `@Url` mechanism's own resolution path
internally):

- `FULL_URL` pointers reuse `UrlResolver`'s existing raw-string resolution
  path verbatim - a paginated fetch following a `next` link is
  indistinguishable, at the dispatch level, from a hand-written `@Url`
  call.
- `VALUE` pointers (cursor, token, page number, offset) re-invoke the same
  method with the same declared parameters, with the marked argument
  substituted.

Because every subsequent fetch is a genuine re-invocation of the client's
own method, it automatically passes through `@Retry`, cache lookups, the
circuit breaker, the bulkhead, and every registered interceptor - with no
special-casing anywhere in those features. A flaky page-7 fetch retries
like any other call; a client whose circuit is open refuses page 4 the
same way it would refuse any other call on that client. This is not a
trade-off accepted for pagination's sake - it's the reason pagination is
designed as a loop around the existing pipeline instead of a parallel
HTTP-calling implementation.

## 7. Validation rules

Per the established "deliberately separate implementations" convention
(§9.10.1 of `compile-time-proxy-generation.md`): every rule below is
implemented independently in both `ReflectiveRestClientValidator`
(`java.lang.reflect.Method`/`Parameter`) and
`CompileTimeRestClientValidator` (`javax.lang.model.element.ExecutableElement`/
`VariableElement`), not behind a shared abstraction.

- `itemsField` may be empty only when the response body is itself the
  items array (no wrapper object).
- `pointerKind = FULL_URL` -> **no** `@PaginationCursor` parameter may be
  present (there's nothing to inject; the extracted URL is used as-is).
- `pointerKind = VALUE` and `pointerSource != NONE` -> exactly **N**
  `@PaginationCursor` parameters required, where N is the number of
  comma-separated entries in `pointerField` (1 for the common case; more
  for composite keyset), matched positionally - except when stacked on a
  single `@Body Map<String,Object>` parameter with a comma-separated
  `bodyField`, where one parameter suffices regardless of N (§6.7).
- `pointerSource = NONE` -> `advance` must not be `NONE`, and exactly one
  `@PaginationCursor` parameter (the offset/page counter) is required.
- `advance = INCREMENT_BY_PAGE_SIZE` -> `pageSize` must be `> 0`.
- `@PaginationCursor` must stack on exactly one of `@QueryParam`/
  `@PathParam`/`@HeaderParam`/`@Body` - bare, or stacked on anything else,
  is an error.
- `bodyField` is only meaningful (and only permitted) when
  `@PaginationCursor` is stacked on `@Body`; the parameter's declared type
  must then be `Map<String,Object>`.
- `@PaginationCursor`'s declared parameter type (when not `@Body`) must be
  `String`, `int`, or `long` - anything else is rejected.
- `@Paginated` combined with `@Url` on the same method is rejected as
  redundant - `FULL_URL` pointer handling is internal machinery, invisible
  to the method's own parameter list; the method's *first* call still uses
  its normal templated URL.
- `@Paginated` combined with a `PaginationStrategy<T>` parameter on the
  same method is rejected - pick declarative or programmatic, not both
  (§6.8).
- A return type of `RipResponse<Stream<T>>` or `RipResponse<Iterator<T>>`
  is rejected (§6.4.1) - auto-flattening spans an unknown number of
  underlying calls, so there is no single response left to wrap; the
  error message points at `Page<T>.rawResponse()` as the supported way to
  get per-fetch metadata. `RipResponse<Page<T>>` is not itself rejected as
  a type (nothing stops it compiling), but is documented as unnecessary
  now that `Page<T>` carries its own `rawResponse()`.

## 8. Interaction with existing features

### 8.1 `@Retry`

Applies per page fetch automatically (§6.9) - a transient failure on page
5 retries exactly as it would on any other call. `@Retry`'s idempotency-key
support (already shipped) also applies unchanged, since each page fetch is
just an ordinary invocation of the method it's attached to.

### 8.2 Cache

A GET-based paginated endpoint's individual page responses are cached
exactly as they would be without pagination - no new caching concept.
Worth noting in documentation, not solving here: a cache with a short TTL
combined with a slow multi-page iteration could serve a stale early page
alongside a fresh late page within the same logical sequence; this is an
existing, general cache-consistency property of the library, not something
pagination introduces.

### 8.3 Circuit breaker / bulkhead

Both apply per page fetch, identically to any other call on the same
client (§6.9). A client whose bulkhead is full refuses page N the same way
it refuses any other concurrent call; an open circuit breaker skips page N
without ever reaching the network, exactly as documented in
`circuit-breaker-bulkhead.md`.

### 8.4 Interceptors

`beforeRequest`/`afterResponse` fire for every page fetch, since each is a
real dispatch through the same `InterceptorDispatcher`. A logging
interceptor sees one log line per page; a metrics interceptor records one
sample per page. Documented as expected behavior, not a surprise - the
alternative (suppressing interceptors for "internal" pagination calls)
would make debugging a stuck pagination loop harder, not easier.

### 8.5 Compile-time codegen

`@Paginated` methods and `PaginationStrategy<T>`-parameter methods both
fall back to the reflective proxy, added to the same disqualification list
`RestClientProcessor.toSupportedMethodModel` already uses for a raw
`List<User>` return type or a raw `CompletableFuture` (the established E9
pattern). This is a deliberate scope boundary, not a limitation to be
lifted later:

- **Generic `Type` decoding.** `Page<T>`/`Stream<T>`/`Iterator<T>` all need
  to resolve `T` and decode the items array into it - the exact machinery
  E9/E12 already keep reflective-only, since an annotation processor has
  no `Class<?>` literal to emit for "a list of `User`."
- **Runtime JSON-tree extraction.** `itemsField`/`pointerField`/`bodyField`
  dotted paths are compile-time-known strings, but what they're walked
  against - an arbitrary decoded `JsonElement` - isn't a POJO with named
  fields the compiler could reference; it's inherently a runtime graph
  traversal.
- **A paginated method isn't a single call.** Every codegen'd method makes
  exactly one HTTP call (or one call wrapped in retry attempts, still one
  logical call). A paginated fetch needs to construct a new callable unit
  at runtime and decide whether to invoke it at all - precisely what the
  reflective proxy's `RestClientInvocationHandler`/`RequestExecutor`
  already do dynamically. Generating that as static Java would mean
  duplicating real orchestration logic into every generated class (a
  concrete drift risk - `CompileTimeRestClientValidator` already fell
  behind `ReflectiveRestClientValidator` once this way, tracked as issue
  #187) for no benefit over delegating the whole method, which the
  existing fallback already does with less code.

`PaginationStrategy<T>` disqualifies even more clearly, since it's
arbitrary consumer code the processor has no basis to reason about.

## 9. Exhaustive variant catalogue

46 distinct, real-world-grounded pagination shapes, crossing where the
signal lives (response header / body top-level / body nested / derived
from the last item), what kind of signal it is, and how the client resends
state (query param / path param / request header / JSON body field). Each
row shows the `@Paginated` expression and, where the declarative model
can't reach it cleanly, the equivalent `PaginationStrategy<T>`.

| # | Variant | Real-world example | `@Paginated` | `PaginationStrategy<T>` |
|---|---|---|---|---|
| 1 | Link header, full URL | GitHub REST, Shopify REST | `itemsField=""`, `pointerKind=FULL_URL`, `pointerSource=RESPONSE_HEADER`, `pointerField="Link"` | `ctx -> LinkHeader.parseNext(ctx.header("Link")).map(PaginationRequest::toUrl)` |
| 2 | Header cursor -> query param | Internal/enterprise APIs | `pointerSource=RESPONSE_HEADER`, `pointerField="X-Next-Cursor"`; `@QueryParam("cursor") @PaginationCursor String cursor` | `ctx -> Optional.ofNullable(ctx.header("X-Next-Cursor")).map(c -> PaginationRequest.withQueryParam("cursor", c))` |
| 3 | Header cursor -> JSON request body | POST-based search continuation | same source; `@Body @PaginationCursor(bodyField="cursor") Map<String,Object> body` | `ctx -> Optional.ofNullable(ctx.header("X-Next-Cursor")).map(c -> PaginationRequest.withBodyField("cursor", c))` |
| 4 | Header cursor -> URL path param | Rare path-templated cursor APIs | `@PathParam("cursor") @PaginationCursor String cursor` | `ctx -> Optional.ofNullable(ctx.header("X-Next-Cursor")).map(c -> PaginationRequest.withPathParam("cursor", c))` |
| 5 | Header cursor -> request header | Internal service-to-service APIs | `@HeaderParam("X-Cursor") @PaginationCursor String cursor` | `ctx -> Optional.ofNullable(ctx.header("X-Next-Cursor")).map(c -> PaginationRequest.withHeader("X-Cursor", c))` |
| 6 | `X-Total-Count` header, arithmetic | WordPress REST API, older GitHub API | `advance=INCREMENT_BY_PAGE_SIZE`, `pageSize=50`, `totalSource=RESPONSE_HEADER`, `totalField="X-Total-Count"` | `ctx -> ctx.itemsFetchedSoFar() < Integer.parseInt(ctx.header("X-Total-Count")) ? Optional.of(PaginationRequest.withQueryParam("offset", ctx.itemsFetchedSoFar())) : Optional.empty()` |
| 7 | `X-Has-More` header, boolean | API gateways avoiding body parsing for the flag | `hasMoreSource=RESPONSE_HEADER`, `hasMoreField="X-Has-More"` | `ctx -> !"true".equals(ctx.header("X-Has-More")) ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("cursor", ctx.rawBody().getAsJsonObject().get("cursor").getAsString()))` |
| 8 | `Content-Range` header, arithmetic | Supabase/PostgREST | `totalSource=RESPONSE_HEADER`, `totalField="Content-Range"` (parsed internally) | `ctx -> { int total = parseContentRangeTotal(ctx.header("Content-Range")); return ctx.itemsFetchedSoFar() < total ? Optional.of(PaginationRequest.withHeader("Range", "items=" + ctx.itemsFetchedSoFar() + "-")) : Optional.empty(); }` |
| 9 | Body top-level full URL | Generic REST convention | `pointerKind=FULL_URL`, `pointerField="next"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("next")).map(e -> PaginationRequest.toUrl(e.getAsString()))` |
| 10 | Body top-level cursor -> query param | Slack (top-level variant) | `pointerField="next_cursor"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("next_cursor")).map(e -> PaginationRequest.withQueryParam("cursor", e.getAsString()))` |
| 11 | Body top-level cursor -> JSON request body | Elasticsearch `search_after` | `pointerField="search_after"`; `@Body Map<String,Object>` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("search_after")).map(e -> PaginationRequest.withBodyField("search_after", e))` |
| 12 | Body top-level cursor -> URL path param | Rare cursor-in-path REST designs | `@PathParam("cursor") @PaginationCursor String cursor` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("next")).map(e -> PaginationRequest.withPathParam("cursor", e.getAsString()))` |
| 13 | Body top-level cursor -> request header | Token-continuation APIs keeping query strings clean | `@HeaderParam("X-Cursor") @PaginationCursor String cursor` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("next")).map(e -> PaginationRequest.withHeader("X-Cursor", e.getAsString()))` |
| 14 | `has_more` + last-record-ID cursor | Stripe | `hasMoreField="has_more"`, `pointerSource=ITEM_FIELD`, `pointerField="id"` | `ctx -> !ctx.rawBody().getAsJsonObject().get("has_more").getAsBoolean() ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("starting_after", ctx.items().get(ctx.items().size()-1).getId()))` |
| 15 | `NextToken` -> query param | AWS S3 `ListObjectsV2` | `pointerField="NextContinuationToken"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("NextContinuationToken")).map(e -> PaginationRequest.withQueryParam("continuation-token", e.getAsString()))` |
| 16 | `NextToken` -> JSON request body | AWS DynamoDB `Query`/`Scan` | `pointerField="LastEvaluatedKey"`; `@Body Map<String,Object>` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("LastEvaluatedKey")).map(e -> PaginationRequest.withBodyField("ExclusiveStartKey", e))` |
| 17 | Scroll ID (TTL) -> JSON request body | Elasticsearch Scroll API | `pointerField="_scroll_id"`; `@Body Map<String,Object>` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("_scroll_id")).map(e -> PaginationRequest.withBodyField("scroll_id", e.getAsString()))` |
| 18 | Body top-level next-page-number -> query param | Homegrown list endpoints | `pointerField="next_page"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("next_page")).map(e -> PaginationRequest.withQueryParam("page", e.getAsInt()))` |
| 19 | Body top-level page number -> URL path param | CMS-style REST APIs | `@PathParam("page") @PaginationCursor int page` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("next_page")).map(e -> PaginationRequest.withPathParam("page", e.getAsInt()))` |
| 20 | JSON:API `links.next` | Any JSON:API-conformant API | `pointerKind=FULL_URL`, `pointerField="links.next"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().getAsJsonObject("links")).map(l -> l.get("next")).filter(e -> !e.isJsonNull()).map(e -> PaginationRequest.toUrl(e.getAsString()))` |
| 21 | HAL `_links.next.href` | HAL-conformant APIs | `pointerKind=FULL_URL`, `pointerField="_links.next.href"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().getAsJsonObject("_links")).map(l -> l.getAsJsonObject("next")).map(n -> PaginationRequest.toUrl(n.get("href").getAsString()))` |
| 22 | OData `@odata.nextLink` | Microsoft Graph | `pointerKind=FULL_URL`, `pointerField="@odata.nextLink"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().get("@odata.nextLink")).map(e -> PaginationRequest.toUrl(e.getAsString()))` |
| 23 | GraphQL Relay `pageInfo` | GitHub/Shopify GraphQL | *Out of scope (§3) - GraphQL, not REST* | *N/A* |
| 24 | `meta.next_token` -> query param | Twitter/X API v2 | `pointerField="meta.next_token"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().getAsJsonObject("meta")).map(m -> m.get("next_token")).filter(e -> !e.isJsonNull()).map(e -> PaginationRequest.withQueryParam("pagination_token", e.getAsString()))` |
| 25 | `response_metadata.next_cursor` -> query param | Slack (nested variant) | `pointerField="response_metadata.next_cursor"` | `ctx -> Optional.ofNullable(ctx.rawBody().getAsJsonObject().getAsJsonObject("response_metadata")).map(m -> m.get("next_cursor")).filter(e -> !e.getAsString().isEmpty()).map(e -> PaginationRequest.withQueryParam("cursor", e.getAsString()))` |
| 26 | Nested `meta.has_more` boolean | Wrapped-envelope REST APIs | `hasMoreField="meta.has_more"` | `ctx -> { JsonObject meta = ctx.rawBody().getAsJsonObject().getAsJsonObject("meta"); return !meta.get("has_more").getAsBoolean() ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("cursor", meta.get("next_cursor").getAsString())); }` |
| 27 | Body `total`, offset-arithmetic -> query param | Many homegrown paged list APIs | `advance=INCREMENT_BY_PAGE_SIZE`, `pageSize=50`, `totalField="total"` | `ctx -> ctx.itemsFetchedSoFar() < ctx.rawBody().getAsJsonObject().get("total").getAsInt() ? Optional.of(PaginationRequest.withQueryParam("offset", ctx.itemsFetchedSoFar())) : Optional.empty()` |
| 28 | Nested `meta.total_count`, arithmetic | Wrapped-envelope enterprise APIs | `totalField="meta.total_count"` | `ctx -> ctx.itemsFetchedSoFar() < ctx.rawBody().getAsJsonObject().getAsJsonObject("meta").get("total_count").getAsInt() ? Optional.of(PaginationRequest.withQueryParam("offset", ctx.itemsFetchedSoFar())) : Optional.empty()` |
| 29 | Body `total`, arithmetic -> JSON request body | Elasticsearch `from`/`size` | `totalField="hits.total.value"`; `@Body Map<String,Object>` | `ctx -> ctx.itemsFetchedSoFar() < ctx.rawBody().getAsJsonObject().getAsJsonObject("hits").getAsJsonObject("total").get("value").getAsInt() ? Optional.of(PaginationRequest.withBodyField("from", ctx.itemsFetchedSoFar())) : Optional.empty()` |
| 30 | Body `total`, arithmetic -> URL path param | Rare (`/orders/{offset}/{limit}`) | `@PathParam("offset") @PaginationCursor int offset` | `ctx -> ctx.itemsFetchedSoFar() < ctx.rawBody().getAsJsonObject().get("total").getAsInt() ? Optional.of(PaginationRequest.withPathParam("offset", ctx.itemsFetchedSoFar())) : Optional.empty()` |
| 31 | Body `total`, arithmetic -> request header | Internal APIs keeping query strings clean | `@HeaderParam("X-Offset") @PaginationCursor int offset` | `ctx -> ctx.itemsFetchedSoFar() < ctx.rawBody().getAsJsonObject().get("total").getAsInt() ? Optional.of(PaginationRequest.withHeader("X-Offset", String.valueOf(ctx.itemsFetchedSoFar()))) : Optional.empty()` |
| 32 | `nbPages`/`page`, page-count arithmetic | Algolia | `advance=INCREMENT_BY_ONE`, `totalPagesField="nbPages"` | `ctx -> ctx.pagesFetchedSoFar() < ctx.rawBody().getAsJsonObject().get("nbPages").getAsInt() ? Optional.of(PaginationRequest.withQueryParam("page", ctx.pagesFetchedSoFar()+1)) : Optional.empty()` |
| 33 | Nested `meta.total_pages` -> URL path param | Paginated CMS/search UIs | `totalPagesField="meta.total_pages"` | `ctx -> ctx.pagesFetchedSoFar() < ctx.rawBody().getAsJsonObject().getAsJsonObject("meta").get("total_pages").getAsInt() ? Optional.of(PaginationRequest.withPathParam("page", ctx.pagesFetchedSoFar()+1)) : Optional.empty()` |
| 34 | `X-Total-Pages` header | Some API gateways | `totalPagesSource=RESPONSE_HEADER`, `totalPagesField="X-Total-Pages"` | `ctx -> ctx.pagesFetchedSoFar() < Integer.parseInt(ctx.header("X-Total-Pages")) ? Optional.of(PaginationRequest.withQueryParam("page", ctx.pagesFetchedSoFar()+1)) : Optional.empty()` |
| 35 | No signal, offset -> query param, short-page stop | Minimal/homegrown list endpoints | `advance=INCREMENT_BY_PAGE_SIZE`, `pageSize=50` | `ctx -> ctx.items().size() < 50 ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("offset", ctx.itemsFetchedSoFar()))` |
| 36 | No signal, offset -> JSON request body | POST-based search endpoints with no total | `@Body Map<String,Object>` | `ctx -> ctx.items().size() < 50 ? Optional.empty() : Optional.of(PaginationRequest.withBodyField("offset", ctx.itemsFetchedSoFar()))` |
| 37 | No signal, offset -> URL path param | Rare (`/items/{offset}/{limit}`) | `@PathParam("offset") @PaginationCursor int offset` | `ctx -> ctx.items().size() < 50 ? Optional.empty() : Optional.of(PaginationRequest.withPathParam("offset", ctx.itemsFetchedSoFar()))` |
| 38 | No signal, offset -> request header | Internal service-to-service APIs | `@HeaderParam("X-Page") @PaginationCursor int offset` | `ctx -> ctx.items().size() < 50 ? Optional.empty() : Optional.of(PaginationRequest.withHeader("X-Page", String.valueOf(ctx.itemsFetchedSoFar())))` |
| 39 | No signal, page-number only -> query param | Extremely common minimal convention | `advance=INCREMENT_BY_ONE` | `ctx -> ctx.items().isEmpty() ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("page", ctx.pagesFetchedSoFar()+1))` |
| 40 | No signal, page-number only -> URL path param | CMS/blog-style REST APIs | `@PathParam("page") @PaginationCursor int page` | `ctx -> ctx.items().isEmpty() ? Optional.empty() : Optional.of(PaginationRequest.withPathParam("page", ctx.pagesFetchedSoFar()+1))` |
| 41 | Keyset: last record's ID -> query param | Classic Twitter API v1.1 | `pointerSource=ITEM_FIELD`, `pointerField="id"` | `ctx -> ctx.items().isEmpty() ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("since_id", ctx.items().get(ctx.items().size()-1).getId()))` |
| 42 | Keyset: last record's timestamp -> query param | Event/audit-log/webhook APIs | `pointerField="createdAt"` | `ctx -> ctx.items().isEmpty() ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("after", ctx.items().get(ctx.items().size()-1).getCreatedAt()))` |
| 43 | Composite keyset -> JSON request body | High-throughput DB-backed APIs, stable sort | `pointerField="id,createdAt"`; `@Body @PaginationCursor(bodyField="lastId,lastTimestamp") Map<String,Object>` | `ctx -> { if (ctx.items().isEmpty()) return Optional.empty(); Event last = ctx.items().get(ctx.items().size()-1); return Optional.of(PaginationRequest.withBodyField("lastId", last.getId()).and(PaginationRequest.withBodyField("lastTimestamp", last.getCreatedAt()))); }` |
| 44 | Composite keyset -> request header | Rare, when the pair can't serialize into one query value | two `@HeaderParam @PaginationCursor` params | `ctx -> { if (ctx.items().isEmpty()) return Optional.empty(); Event last = ctx.items().get(ctx.items().size()-1); return Optional.of(PaginationRequest.withHeader("X-Last-Id", last.getId()).and(PaginationRequest.withHeader("X-Last-Ts", last.getCreatedAt()))); }` |
| 45 | Split: `hasMore` in header, cursor in body | Product evolving across API generations | `hasMoreSource=RESPONSE_HEADER`, `hasMoreField="X-Has-More"`, `pointerField="cursor"` | `ctx -> !"true".equals(ctx.header("X-Has-More")) ? Optional.empty() : Optional.of(PaginationRequest.withQueryParam("cursor", ctx.rawBody().getAsJsonObject().get("cursor").getAsString()))` |
| 46 | Split: `total` in header, items only in body | Legacy REST APIs migrated partway | `totalSource=RESPONSE_HEADER`, `totalField="X-Total-Count"`; `@Body Map<String,Object>` | `ctx -> ctx.itemsFetchedSoFar() < Integer.parseInt(ctx.header("X-Total-Count")) ? Optional.of(PaginationRequest.withBodyField("offset", ctx.itemsFetchedSoFar())) : Optional.empty()` |

## 10. Where `PaginationStrategy<T>` is required, not merely an alternative

The declarative model, however exhaustive, is a closed vocabulary. These
cases have no `@Paginated` expression at all - `PaginationStrategy<T>` is
the only option, not a stylistic choice:

| # | Case | Why `@Paginated` can't express it |
|---|---|---|
| 1 | Cursor needs decoding/transformation before reuse (e.g. a base64-encoded composite token) | Dotted-path attributes only *read* a raw field value verbatim - no attribute for "and then transform it" |
| 2 | Combined/defensive termination logic across multiple signals (stop if `has_more` is false **OR** the page came back short **OR** a hard page cap is reached) | The termination check (§6.5) is a fixed precedence chain, not an arbitrary boolean expression the consumer composes |
| 3 | Pagination shape depends on what the *caller* passed on the first call (e.g. a legacy filter flag switches the whole API between page-number and cursor conventions) | `@Paginated`'s attributes are fixed at declaration time - they can't branch on a runtime argument |
| 4 | Cross-page state beyond a simple counter (e.g. deduplicating IDs across pages because a server occasionally repeats a boundary record) | The model exposes only `itemsFetchedSoFar()`/`pagesFetchedSoFar()` - no hook for arbitrary accumulated state |
| 5 | The next page depends on a *separate* API call (e.g. a `/count` endpoint hit once to learn how many pages to expect) | `@Paginated` only ever looks at the current endpoint's own response - it can't invoke another method |
| 6 | Inconsistent/malformed real responses (a `total` field that's sometimes a string, sometimes a number, sometimes absent) | The model assumes one fixed JSON shape per attribute - no coercion or defensive fallback |
| 7 | The extracted URL/cursor needs rewriting before use (an internal hostname needing a public swap, a query param needing stripping) | `pointerKind = FULL_URL` uses the extracted URL verbatim - no attribute for "use it, but modified" |
| 8 | Content-based early termination (stop once an item older than a cutoff date appears - common in incremental sync/backfill) | Depends on inspecting item field *values* with business logic, not a count, flag, or structural field |
| 9 | Custom throttling between fetches (sleep based on a live `X-RateLimit-Remaining` header, beyond `@Retry`'s own backoff) | The annotation has no side-effect hook - it only describes data extraction, not actions between fetches |
| 10 | A genuinely novel convention not yet cataloged in §9 | However exhaustive, the enum set is closed by construction - a shape RIP hasn't anticipated has no attribute combination |

## 11. Open questions

- ~~**`RipResponse<Page<T>>`?**~~ **Resolved (§6.4.1)**: `Page<T>` gets its
  own `rawResponse()` accessor, correct across the whole iteration instead
  of just page 1; `RipResponse<Page<T>>` is deliberately not supported,
  and `RipResponse<Stream<T>>`/`RipResponse<Iterator<T>>` are rejected at
  validation time since auto-flattening leaves no single response to wrap.
- **Async pagination - partially resolved (§6.4.1).** `CompletableFuture<Page<T>>`/
  `CompletableFuture<Stream<T>>`/`CompletableFuture<Iterator<T>>` are
  supported for an async *first* fetch, matching `CompletableFuture<T>`'s
  existing behavior elsewhere in RIP. Still open: a fully async iteration
  protocol (`Page<T>.next()` itself returning a `CompletableFuture<Page<T>>`)
  is real, plausible future work but a materially bigger feature than
  "the first call can be async like any other RIP call" - deliberately
  deferred rather than guessed at now, consistent with parking the whole
  feature until usage was concrete enough to design against.
- **`MockRestServer` multi-page fixtures.** Testing a paginated fetch needs
  a way to script a page sequence in one test (page 1 responds with
  `next=url2`, page 2 responds with `next=null`) - likely a small addition
  to `MockRestServer`/`MockResponse`, sketched in the rollout plan (§12)
  but not designed in detail here.
- **Cursor expiry (Elasticsearch scroll-style).** Per §3, out of scope to
  auto-handle - but should the thrown exception on an expired scroll be a
  distinguishable RIP exception type, so a consumer can catch it
  specifically and restart, rather than a generic
  `RestInPeaceHttpException`?

## 12. Rollout plan (chunked)

Mirrors the chunking convention the other three design docs use - each
chunk its own PR, verified and merged before the next starts.

1. **This design doc.** Landed.
2. **`NEXT_URL` pointer style + `Page<T>`, sync only.** Landed. `@Paginated`,
   `@PaginationCursor` (query/path/header carriers only - `@Body` deferred
   to its own chunk), `PointerKind.FULL_URL` and `PointerKind.VALUE`,
   §6.5's termination precedence (`hasMoreSource`/`totalSource`/
   `totalPagesSource` included - they're cheap scalar reads off the same
   parsed tree the two-phase decode already builds, no reason to defer
   them). Covers §9 rows 9-10, 20-22 fully; row 1 (GitHub's `Link` header)
   only partially at this point - a header whose raw value *is* the next
   URL worked from this chunk, but RFC 8288 `rel="next"` parsing of a real
   `Link` header was chunk 6's job, which has since landed too (§12 item 6).
3. **`Stream<T>`/`Iterator<T>` auto-flatten** on top of chunk 2. Landed.
   A pure wrapper over the existing `Page<T>` chain, no new fetch logic -
   both return types are lazy (no page fetched until the first
   `hasNext()`/terminal stream operation), unlike `Page<T>` itself, which
   still fetches its first page eagerly like any other RIP call.
4. **`@Body Map<String,Object>` + `bodyField` carrier.** Landed. A single
   dotted-path `bodyField` (`get`'s request-side counterpart, walked as a
   copy-on-write `set` so the caller's own map/nested maps are never
   mutated) covers every row whose cursor is one scalar value written into
   the request body - rows 3, 11, 16, 17. A comma-separated `bodyField`
   (composite keyset into one body field) is wired up too, but has nothing
   to consume until chunk 5 lands `pointerSource = ITEM_FIELD`, the only
   source that can ever produce more than one extracted value; rows
   29/36/46 also need `advance` (chunk 7).
5. **`NEXT_CURSOR`-shaped `ITEM_FIELD`/keyset pointer source**, including
   N-way composite via multiple `@PaginationCursor` parameters or one
   `@Body` carrier's comma-separated `bodyField`. Landed. Extracts from the
   *last fetched item* in the current page rather than a dedicated response
   field; a composite `pointerField` needs either exactly N non-`@Body`
   cursor parameters (positionally matched) or one `@Body` cursor parameter
   whose `bodyField` names the same N values. Without a `hasMore`/`total`/
   `totalPages` signal, a `since_id`-style API's own field is (by
   construction) always present on a non-empty page, so termination in
   practice falls to the unconditional empty-items safety net (§6.5 step 5)
   rather than the pointer-presence fallback. Covers rows 14, 41-44.
6. **RFC 8288 (formerly RFC 5988) `Link` header parsing, `rel="next"`.**
   Landed. No new enum value - row 1's own `@Paginated` expression
   (`pointerKind=FULL_URL`, `pointerSource=RESPONSE_HEADER`,
   `pointerField="Link"`) already names this shape; `PaginationCoordinator`
   just parses the extracted header's value as one or more
   comma-separated `<uri>; rel="name"; ...` segments and returns the
   `rel="next"` target, falling back to using the raw header value as the
   URL verbatim when it doesn't look like that format at all (no
   angle-bracketed URI) - the simpler case already covered by chunk 2 for
   a non-standard header. A well-formed `Link` header with no `rel="next"`
   segment (the genuinely last page, which may still carry `rel="prev"`/
   `rel="first"`) resolves to no pointer, same as any other exhausted
   `FULL_URL` pointer. Covers row 1.
7. **`OFFSET_LIMIT`/`advance` (client-driven, no server pointer) +
   `totalPagesSource`.** Landed. `totalPagesSource`/`totalPagesField` were
   already wired into the termination precedence back in chunk 2 (§12 item
   2) - this chunk's actual work is `PaginationAdvance`: when
   `pointerSource = NONE` there's no pointer to extract at all, so
   `PaginationCoordinator` computes the next offset/page number itself
   (`INCREMENT_BY_PAGE_SIZE` advances by `pageSize`; `INCREMENT_BY_ONE`
   advances the page number by one) and substitutes it into the single
   `@PaginationCursor` parameter, exactly as if it had been extracted. A
   `hasMoreSource`/`totalSource`/`totalPagesSource` signal, if set, still
   takes precedence over the termination check (§6.5); absent any of
   those, `INCREMENT_BY_PAGE_SIZE` falls back to stopping on a short page
   (fewer items than `pageSize`) and `INCREMENT_BY_ONE` falls back to the
   unconditional empty-items safety net. A computed value written into a
   `@Body` carrier is a real JSON number (unlike an extracted pointer
   value, always resent as the raw extracted string) - matching what a
   numeric field like Elasticsearch's `from`/`size` actually expects.
   Covers rows 6, 8, 27-40.
8. **`PaginationStrategy<T>`** - the programmatic escape hatch (§6.8).
   Landed. Recognized by declared parameter type, mutually exclusive with
   `@Paginated` (validated identically in both
   `ReflectiveRestClientValidator`/`CompileTimeRestClientValidator`), with
   an item-type-mismatch check between the strategy's `T` and the method's
   own `Page<T>`/`Stream<T>`/`Iterator<T>` return type argument when both
   are reflectively known. Unlike the declarative path, there's no
   `itemsField` equivalent - the response body must itself be the JSON
   items array; a wrapped envelope's other fields are still reachable via
   `PaginationContext.rawBody()` for the strategy's own termination logic,
   just not as the page's `items()`. `PaginationRequest.withQueryParam`/
   `withHeader` are applied directly onto the built request (no
   corresponding method parameter needed - the escape hatch can name a
   query param/header the method never declared at all).
   `withPathParam`/`withBodyField`, by contrast, route through the
   method's own `@PathParam`/`@Body` parameter (every URL template
   placeholder is already required, for every method, to have a matching
   `@PathParam`, so there's always one to route through) rather than
   patching the resolved URL/body directly - the same mechanism the
   declarative `@PaginationCursor` carriers use, just driven by the
   strategy's return value instead of an extracted pointer or `advance`
   arithmetic. The unconditional empty-items safety net (§6.5 step 5)
   applies here too, regardless of what the strategy itself returns.
9. **`MockRestServer` multi-page test fixtures**, addressing the first
   open question in §11.

Chunk 9 and later can reorder freely based on which real consumer need
surfaces first, per the same "park until real usage narrows which
shape(s) actually matter" instinct that correctly parked this feature the
first time - chunks 2-6 alone already cover the majority of real APIs
surveyed in §9.
