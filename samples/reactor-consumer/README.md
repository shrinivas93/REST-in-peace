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

`core` is published on Maven Central (see the "Maven Central publishing"
item in [`ROADMAP.md`](../../ROADMAP.md)), but this sample pins the
repository's current `-SNAPSHOT` version, and `rest-in-peace-reactor` isn't
published yet at all - so you need locally-installed builds of both first.
core and `rest-in-peace-reactor` share a parent POM
([`../../pom.xml`](../../pom.xml)) that needs installing too (`-N`,
non-recursive: just that one POM), since both of their published POMs
reference it:

```sh
# From the repository root:
mvn install -N
mvn install -DskipTests -pl core,rest-in-peace-reactor

# Then, from this directory:
cd samples/reactor-consumer
VERSION=$(awk '/<artifactId>rest-in-peace-parent<\/artifactId>/{getline; sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print}' ../../pom.xml)
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt \
  -Drest-in-peace.version="$VERSION"
java -cp "target/classes:$(cat cp.txt)" com.example.consumer.Main
```

A successful run prints each call's result and ends with:

```
VERIFICATION PASSED: rest-in-peace-reactor works for a real downstream consumer.
```

The `-Drest-in-peace.version=...` flag (this `pom.xml`'s single version
property, referenced by both the `rest-in-peace` and `rest-in-peace-reactor`
dependencies below) overrides its hardcoded default with whatever core and
`rest-in-peace-reactor`'s shared version actually is right now - they always
match each other (both inherit one version from their common parent), but
this sample is still a separate, non-reactor project, so its own default
still drifts the moment that shared version changes. Omitting the flag
falls back to that hardcoded default, which will fail to resolve once it
drifts from whatever you just installed.

## Try it yourself

Add a `Mono<Void> deleteOrder(String id)` fire-and-forget method (a `DELETE`
that returns no body) alongside the three already here - no other change
needed for `RestInPeaceReactor.register()` to handle it, since
`MonoCallAdapterFactory` claims every `Mono<T>`-returning method, not just
the ones this sample happens to use. (`RipResponse<T>` wraps a *single*
response's status/headers, so it pairs naturally with `Mono<RipResponse<T>>`
- there's no `Flux<RipResponse<T>>` equivalent, since a `Flux<T>` here can
flatten items from more than one underlying HTTP response.)
