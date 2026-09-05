# Compile-time proxy generation - sample consumer

A standalone Maven project - deliberately **not** a module of the parent
`rest-in-peace` build - showing how a real downstream consumer sees the
[compile-time proxy generation](../../docs/design/compile-time-proxy-generation.md)
feature: add the library as an ordinary dependency, write a plain
`@RestClient` interface, and get back a real generated implementation with
zero extra configuration.

## What it demonstrates

- **`ItemApi`** sits within the compile-time generator's currently-supported
  shape (a fixed `GET`, only `@PathParam`/plain `@QueryParam`, a `String`
  return type). Building this project generates a real
  `ItemApi_RipImpl.java` under `target/generated-sources/annotations/` -
  open it after building to see exactly what gets produced.
- **`UnsupportedApi`** uses a generic `List<String>` return type, outside
  that shape, to show the fallback: no generated class exists for it at
  all, and `RIP.getClient(UnsupportedApi.class)` returns the same
  reflective `java.lang.reflect.Proxy` every `@RestClient` interface used
  before this feature existed. (Earlier this used `@HeaderParam`, then
  `@Multipart`/`@Part`, then `CompletableFuture`, but step 2 of the design
  added support for all three - see
  `docs/design/compile-time-proxy-generation.md` - so this moved to a
  feature that is permanently unsupported: a generic collection return
  type isn't decodable via a single `Class<?>` literal the way every
  supported return type is.)
- **`Main`** starts a throwaway local HTTP server, calls both interfaces,
  and asserts (by throwing if anything's wrong) that both paths behave as
  described above.
- **`NativeMain`** is the entry point for this project's `native` Maven
  profile (`mvn -Pnative package`), which builds the whole thing into a
  GraalVM native executable - the native-image smoke test for step 3 of
  the design doc's rollout plan. It exercises only `ItemApi`'s
  fully-covered path (not `UnsupportedApi`'s fallback, which needs its own
  hand-written `proxy-config.json` under native-image and is a separate
  concern from what this smoke test proves), with **zero hand-written
  native-image configuration** anywhere in this project - the only
  `reflect-config.json` involved is the one `RestClientProcessor` itself
  emits alongside each generated `_RipImpl` class.

## Running it

This library isn't published anywhere `mvn` looks by default yet (see the
"Maven Central publishing" item in [`ROADMAP.md`](../../ROADMAP.md)), so
you need a locally-installed build of it first:

```sh
# From the repository root:
mvn install -DskipTests

# Then, from this directory:
cd samples/compile-time-proxy-consumer
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt \
  -Drest-in-peace.version=$(grep -m1 -oP '(?<=<version>)[^<]+(?=</version>)' ../../pom.xml)
java -cp "target/classes:$(cat cp.txt)" com.example.consumer.Main
```

A successful run prints each step and ends with:

```
VERIFICATION PASSED: compile-time proxy generation works for a real downstream consumer.
```

`-Drest-in-peace.version=...` overrides this pom.xml's own
`<rest-in-peace.version>` default with whatever the repository root's
`pom.xml` `<version>` actually is right now - they're two independent
projects, so nothing keeps the two in sync automatically, and the root
version does change over time (each release bumps it). Omitting the flag
falls back to the hardcoded default, which will fail to resolve once it
drifts from whatever you just installed.

### Running the native-image smoke test

Requires a GraalVM JDK (with `native-image`) on `PATH`/`JAVA_HOME` instead
of an ordinary JDK 8:

```sh
# From the repository root, using a GraalVM JDK:
mvn install -DskipTests

# Then, from this directory:
cd samples/compile-time-proxy-consumer
mvn -Pnative package \
  -Drest-in-peace.version=$(grep -m1 -oP '(?<=<version>)[^<]+(?=</version>)' ../../pom.xml)
./target/compile-time-proxy-consumer
```

A successful run ends with:

```
NATIVE-IMAGE VERIFICATION PASSED: compile-time proxy generation works under GraalVM native-image with zero hand-written reflection config.
```

## Try it yourself

Add a method to `ItemApi` using a feature outside the supported shape (e.g.
a generic `List<String>` return type, like `UnsupportedApi`) and rebuild -
watch `ItemApi_RipImpl.java` disappear from `target/generated-sources/` as
the *whole interface* falls back to the reflective proxy, per the
"generating a partially-correct implementation would be worse than not
generating one at all" rule described in the design doc.
