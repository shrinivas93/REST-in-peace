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
- **`UnsupportedApi`** uses `@HeaderParam`, outside that shape, to show the
  fallback: no generated class exists for it at all, and
  `RIP.getClient(UnsupportedApi.class)` returns the same reflective
  `java.lang.reflect.Proxy` every `@RestClient` interface used before this
  feature existed - a call through it still works correctly.
- **`Main`** starts a throwaway local HTTP server, calls both interfaces,
  and asserts (by throwing if anything's wrong) that both paths behave as
  described above.

## Running it

This library isn't published anywhere `mvn` looks by default yet (see the
"Maven Central publishing" item in [`ROADMAP.md`](../../ROADMAP.md)), so
you need a locally-installed build of it first:

```sh
# From the repository root:
mvn install -DskipTests

# Then, from this directory:
cd samples/compile-time-proxy-consumer
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" com.example.consumer.Main
```

A successful run prints each step and ends with:

```
VERIFICATION PASSED: compile-time proxy generation works for a real downstream consumer.
```

If `mvn compile` fails to resolve `com.shri:rest-in-peace`, check that this
project's `pom.xml` `<rest-in-peace.version>` matches the `<version>` in the
repository root's `pom.xml` - they're two independent projects, so nothing
keeps them in sync automatically.

## Try it yourself

Add a method to `ItemApi` using a feature outside the supported shape (e.g.
a `@HeaderParam`, like `UnsupportedApi`, or a `@Retry`) and rebuild - watch
`ItemApi_RipImpl.java` disappear from `target/generated-sources/` as the
*whole interface* falls back to the reflective proxy, per the "generating a
partially-correct implementation would be worse than not generating one at
all" rule described in the design doc.
