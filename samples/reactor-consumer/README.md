# Project Reactor - sample consumer

A standalone Maven project - deliberately **not** a module of the parent
`rest-in-peace` build (core and `rest-in-peace-reactor/` are sibling
modules of it, but this sample isn't) - showing how a real downstream
consumer sees
[Project Reactor support](../../docs/design/reactor-call-adapter.md): add
`rest-in-peace-reactor` as an ordinary dependency alongside the core
library, call `RestInPeaceReactor.register()` once, and every
`Mono<T>`/`Flux<T>`-returning method on an ordinary `@RestClient` interface
just works.

## What it demonstrates

- **`OrderApi`** is a plain `@RestClient` interface with three methods,
  each exercising a different shape `rest-in-peace-reactor` supports:
  - `Mono<Order> getOrder(String id)` - a single response.
  - `Flux<Order> listOrders()` - a plain, non-`@Paginated` method decoding
    one JSON array response and flattening it item by item (§7.1).
  - `Flux<Order> streamAllOrders(String cursor)` - a `@Paginated` method
    auto-flattening every page into one stream with real backpressure
    (§7.2), fetching the next page only once demand exceeds what's
    already buffered.
- **`Main`** starts a throwaway local HTTP server serving all three
  routes (the paginated one across two pages), calls
  `RestInPeaceReactor.register()` and `RIP.useDaemonThreadsForAsync()`
  (so this short-lived program can exit on its own instead of hanging on
  Unirest's non-daemon async I/O threads), then calls all three methods
  and asserts (by throwing if anything's wrong) that each one decodes
  correctly.

## Running it

Neither `rest-in-peace` nor `rest-in-peace-reactor` is published anywhere
`mvn` looks by default yet (see the "Maven Central publishing" item in
[`ROADMAP.md`](../../ROADMAP.md)), so you need locally-installed builds of
both first. core and `rest-in-peace-reactor` share a parent POM
([`../../pom.xml`](../../pom.xml)) that needs installing too (`-N`,
non-recursive: just that one POM), since both of their published POMs
reference it:

```sh
# From the repository root:
mvn install -N
mvn install -DskipTests -pl core,rest-in-peace-reactor

# Then, from this directory:
cd samples/reactor-consumer
VERSION=$(grep -A1 -F '<artifactId>rest-in-peace-parent</artifactId>' ../../pom.xml | grep -oP '(?<=<version>)[^<]+(?=</version>)')
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt \
  -Drest-in-peace.version="$VERSION" \
  -Drest-in-peace-reactor.version="$VERSION"
java -cp "target/classes:$(cat cp.txt)" com.example.consumer.Main
```

A successful run prints each call's result and ends with:

```
VERIFICATION PASSED: rest-in-peace-reactor works for a real downstream consumer.
```

The two `-D...version=...` flags override this `pom.xml`'s own hardcoded
defaults with whatever core and `rest-in-peace-reactor`'s shared version
actually is right now - both always match each other (they inherit one
version from their common parent), but this sample is still a separate,
non-reactor project, so its own defaults still drift the moment that shared
version changes. Omitting either flag falls back to its hardcoded default,
which will fail to resolve once it drifts from whatever you just installed.

## Try it yourself

Add a `Mono<Void>` fire-and-forget method, or a `Flux<RipResponse<Order>>`
(wrapping each item's own status/headers), alongside the three already
here - no other change needed for `RestInPeaceReactor.register()` to
handle it, since both `MonoCallAdapterFactory` and
`FluxListCallAdapterFactory`/`FluxPaginatedCallAdapterFactory` claim every
method of their respective shape, not just the ones this sample happens to
use.
