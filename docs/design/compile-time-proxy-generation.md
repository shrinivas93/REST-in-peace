# Design: compile-time proxy generation

Status: **step 1 and step 2 both fully landed** (see §8's rollout plan -
`RestClientProcessor`, `RIP.getClient`'s generated-impl-first lookup,
`GeneratedApiTest`). Step 2's slices landed: `@Timeout`/`@Retry` (§9.4);
`@Headers`/`@HeaderParam`/`@HeaderMap`/`@QueryMap`/required-or-defaulted
`@QueryParam`/`@Body`/`@Url`/`@ErrorType` (§9.5), which also replaced the
original single `processGeneratedRequest` entry point with a
sequence-of-calls design (§9.5.1); `@Multipart`/`@Part`/`@PartMap`/
`UploadProgressListener` (§9.6); `byte[]`/`File`+`@Destination`+
`DownloadProgressListener`/`RipResponse<T>` return types (§9.7); then
`CompletableFuture<T>` (async), for every one of those return-type shapes
(§9.8) - the last item in §5's table. What remains permanently
unsupported - not a future slice, but excluded from §5's table from the
start - is a generic collection return type (e.g. `List<String>`), since
it isn't decodable via a single `Class<?>` literal the way every supported
return type is. The compile-testing validation suite and the native-image
smoke test (steps 3-4) are not started. Roadmap item: "Compile-time proxy
generation instead of a JDK dynamic proxy" in `ROADMAP.md`. The sections
below are mostly the original sketch; §9 records what actually landed and
the real deviations from the sketch, discovered only while implementing
it.

## 1. Problem

`RIP.getClient(UserApi.class)` today builds a JDK dynamic proxy:

```java
// RIP.java
return (T) Proxy.newProxyInstance(restClient.getClassLoader(), new Class[] { restClient },
        new RestClientInvocationHandler(baseUrl));
```

`RestClientInvocationHandler.invoke(...)` re-derives the HTTP method from
`method.getAnnotations()` on *every call*, then hands off to
`RestRequestProcessor.processRestRequest(...)`, which itself re-reads
`method.getAnnotation(Timeout.class)`, `method.getAnnotation(Headers.class)`,
`method.getAnnotation(Retry.class)`, and walks `method.getParameters()`
checking each one for `@PathParam`/`@QueryParam`/`@HeaderParam`/`@HeaderMap`/
`@Body`/`@Part`/`@PartMap`/`@Url`/`@Destination` — all of it reflection,
all of it repeated per call, none of it changing between calls to the same
method.

Three concrete costs follow from this, all already true of the shipped
library:

1. **GraalVM native-image hostility.** `Proxy.newProxyInstance` and
   reflective `getAnnotation`/`getParameters` calls need
   `reflect-config.json` entries for every `@RestClient` interface a
   *consumer* defines. RIP can't ship that config — it doesn't know the
   consumer's interfaces at build time. Every consumer wanting a
   native-image build must hand-write reflection config for their own
   `@RestClient` interfaces, which is exactly the ergonomics problem RIP
   otherwise exists to remove.
2. **Repeated reflection cost per call.** Everything `applyParams`,
   `applyFixedHeaders`, `applyTimeout`, `resolveUrl`, and
   `getHTTPMethod` look up is fixed per *method*, decided the moment the
   interface is compiled — never per *call*. Doing that lookup on every
   invocation is wasted work in a hot path (a service calling the same
   downstream endpoint thousands of times a second).
3. **No IDE navigation, no generated-source visibility.** `Cmd`-clicking
   `userApi.getUser(id)` lands on the interface method and stops there —
   there is no "what actually happens" to jump into, unlike a
   Dagger/MapStruct-style generated implementation a developer can open
   and read line by line.

## 2. Goals

- A `@RestClient` interface gets a generated, named implementation class
  at `javac` time — one call is one direct method invocation, no
  `InvocationHandler`, no per-call `getAnnotation`/`getParameters` calls.
- `RIP.getClient(UserApi.class)` keeps its exact current signature and
  behavior from a consumer's point of view. This is additive, not a
  breaking change.
- Interfaces the processor never saw (a build that doesn't run RIP's
  processor, or an interface compiled in a module without the processor
  on its `annotationProcessorPath`) keep working exactly as today, via
  the existing reflective proxy. Nobody's code breaks by not opting in.
- Every validation `RestClientValidator` performs today (missing
  `@BaseUrl`, unmatched `@PathParam`, malformed `@Headers` entry, wrong
  `@Part` type, ...) becomes a **compile-time** error instead of a
  runtime `RestInPeaceValidationException` thrown the first time
  `RIP.getClient(...)` is called. This is strictly better for a consumer
  — failures move left, from "first request in production" to
  "`mvn compile`".

## 3. Non-goals

- Not attempting Kotlin `suspend fun`, RxJava, or Reactor support here —
  that's the separate "pluggable `CallAdapter`" roadmap item, and this
  design should not block it (see §8).
- Not changing wire behavior. A generated implementation must produce
  byte-identical requests to today's reflective path for every existing
  test in `RipIntegrationTest`. This is a dispatch-mechanism change, not
  a feature change.
- Not removing the reflective path. It stays forever as the fallback for
  un-processed interfaces (see Goals). This design adds a second, faster
  path — it does not delete the first one.
- Not solving Maven Central publishing, `MockInterceptor`, or any other
  open roadmap item.

## 4. Proposed architecture

### 4.1 New module

A new Maven module, e.g. `rest-in-peace-processor` (or a `processor`
source set inside the existing module if a separate artifact is judged
unnecessary — a real trade-off, see §7), containing an
`AutoRestClientProcessor implements javax.annotation.processing.Processor`
registered via
`META-INF/services/javax.annotation.processing.Processor`, so it activates
automatically when present on a consumer's `annotationProcessorPath` —
no extra configuration beyond adding the dependency.

The main `rest-in-peace` artifact stays annotation-processor-free; a
consumer who wants generated implementations adds the processor artifact
as an `annotationProcessorPath` (or `provided`-scope, framework-dependent)
dependency. A consumer who doesn't add it gets today's reflective proxy
with zero change in behavior.

### 4.2 What gets generated

For each interface annotated `@RestClient` the processor sees, it emits
one top-level class in the same package, named by convention (e.g.
`UserApi` → `UserApi_RipImpl`), implementing `UserApi` directly:

```java
// Generated by RIP's annotation processor. Do not edit.
package com.example.api;

final class UserApi_RipImpl implements UserApi {

    private final RestRequestProcessor ripProcessor;

    UserApi_RipImpl(RestRequestProcessor ripProcessor) {
        this.ripProcessor = ripProcessor;
    }

    @Override
    public User getUser(String id) {
        String url = ripProcessor.resolveUrl("/users/{id}", new Object[] { id },
                new String[] { "id" });
        // ... same call into RestRequestProcessor's request-building/execution
        // machinery, but with every annotation value passed as a literal
        // constant instead of looked up via reflection.
    }
}
```

The key design decision: **the generated class still calls into
`RestRequestProcessor`/Unirest for the actual HTTP work** — it does not
reimplement request building, retry, multipart, or response decoding from
scratch. What it eliminates is the reflective *lookup* of what to do,
replacing `method.getAnnotation(Timeout.class)` with a compile-time-known
`connectMillis`/`readMillis` pair passed as literal `int` arguments,
`Stream.of(method.getParameters()).filter(...)` with a fixed, generated
sequence of direct calls. This keeps the processor's code-generation
surface small (mostly "which literals to bake in and in what order to
call them") and means every future runtime feature (a new annotation, a
new return-type shape) only has to be taught to `RestRequestProcessor`
once, with the processor's job being "call the same methods, with the
values known up front" rather than "reimplement the feature."

Concretely, `RestRequestProcessor` needs a handful of new, non-reflective
entry points alongside its existing `Method`-based ones — e.g.
`resolveUrl(String template, Object[] pathParamValues, String[]
pathParamNames)` beside today's `resolveUrl(Method, HTTPMethod,
Object[])` — so generated code can call directly without ever
constructing a `Method` object. The existing reflective methods stay,
used by `RestClientInvocationHandler` for the fallback path.

### 4.3 `RIP.getClient` integration

```java
public static <T> T getClient(Class<T> restClient) {
    return getClient(restClient, (String) null);
}

public static <T> T getClient(Class<T> restClient, String baseUrl) {
    validateOrThrow(restClient, baseUrl); // same as today

    T generated = tryGeneratedImpl(restClient, baseUrl);
    if (generated != null) {
        return generated;
    }
    return (T) Proxy.newProxyInstance(/* ... today's path, unchanged ... */);
}

private static <T> T tryGeneratedImpl(Class<T> restClient, String baseUrl) {
    try {
        Class<?> implClass = Class.forName(restClient.getName() + "_RipImpl");
        Constructor<?> ctor = implClass.getDeclaredConstructor(RestRequestProcessor.class);
        return (T) ctor.newInstance(new RestRequestProcessor(baseUrl));
    } catch (ClassNotFoundException e) {
        return null; // no processor ran on this interface - fall back to the proxy
    } catch (ReflectiveOperationException e) {
        throw new RestInPeaceException("Generated client for " + restClient.getName()
                + " could not be instantiated.", e);
    }
}
```

This one `Class.forName` + one reflective constructor call *per
`getClient` call, not per HTTP call* is a reasonable, bounded cost — it
is the one piece of reflection the fast path still pays, and only once
per client construction, typically once per application (`RIP.getClient`
results are meant to be held onto, same as today).

A cleaner alternative avoiding even that: the processor also emits one
`@RestClientFactory`-annotated static registry class (or a
`ServiceLoader`-discoverable one) mapping interface `Class` to a factory
`Function<RestRequestProcessor, T>`, built once at class-load time. This
removes the per-`getClient`-call `Class.forName`/reflective-constructor
cost entirely, at the price of one more generated artifact per
compilation unit. Worth deciding once a working `Class.forName` version
proves the rest of the design out — premature to commit to the registry
shape before the simpler version is validated end-to-end.

### 4.4 Validation moves to compile time

`RestClientValidator`'s checks (`validateUrl`, `validateUrlParam`,
`validateBody`, `validateHeaders`, `validateMultipart`, `validateRetry`,
`validateTimeout`, `validateMapParam`, `validateReturnType`,
`validateDestination`, `validate*ProgressListener`) are pure functions of
an interface's `Class`/`Method`/`Parameter` shape — nothing about them
requires a live JVM at `RIP.getClient(...)` time. The processor runs the
*same* validation logic during annotation processing (ideally by sharing
`RestClientValidator` directly, if it can be made to work against
`javax.lang.model` types instead of `java.lang.reflect` ones — see the
open question in §7) and reports failures via `Messager.printMessage
(Diagnostic.Kind.ERROR, ...)`, which surfaces as a normal `javac`
compile error with a file/line pointing at the offending method.

`RestClientValidator`'s runtime checks stay exactly as they are today,
unchanged — they still run for the reflective fallback path (an
interface the processor didn't process), and are harmless, cheap
double-checking for a processed interface, too, so there's no reason to
special-case "skip validation because the processor already checked."

## 5. Per-feature codegen sketch

Each existing annotation, and how its handling would be reached from
generated code instead of reflection:

| Annotation | Today (`RestRequestProcessor`) | Generated code |
|---|---|---|
| `@GET`/`@POST`/etc. | `getUrlTemplate` switches on `HTTPMethod` looked up via `getHTTPMethod` (reflection) | HTTP verb baked in as which `RestRequestProcessor.createRequest(...)` overload/argument to call |
| `@PathParam` | `resolvePathParams` finds params via `parameters[i].getAnnotation(PathParam.class)` | Path param names/positions baked in; still calls the shared `encodePathValue`/substitution logic |
| `@QueryParam`/`@QueryMap` | `applyParams` loop checks each parameter's annotations | Direct `applyQueryValue(request, "name", arg)` calls per fixed param, one `applyQueryMap(request, mapArg)` call if present |
| `@HeaderParam`/`@HeaderMap`/`@Headers` | Same loop, plus `applyFixedHeaders` reading `method.getAnnotation(Headers.class)` | Direct `headerReplace`/`applyHeaderMap` calls; `@Headers` entries baked in as a literal `String[]` passed once |
| `@Body` | `applyBody` triggered by `parameter.getAnnotation(Body.class) != null` | Direct `applyBody(request, arg)` call, no annotation check needed — the generated method signature already knows which parameter is the body |
| `@Multipart`/`@Part`/`@PartMap` | `method.getAnnotation(Multipart.class)` gate, then per-parameter checks | `multiPartContent()` call baked in when the method is `@Multipart`; direct `applyPartValue`/`applyPartMap` calls |
| `@Retry` | `executeSyncWithRetry`/`executeAsyncWithRetry` read `method.getAnnotation(Retry.class)` per call | `Retry`'s four values passed as literal constructor/method arguments instead of re-reading the annotation |
| `@Timeout` | `applyTimeout` reads `method.getAnnotation(Timeout.class)` | Literal `connectMillis`/`readMillis` passed directly |
| `@ErrorType` | `decodeBody` reads `method.getAnnotation(ErrorType.class)` | Literal `Class<?>` passed directly |
| Return type (`String`/POJO/`byte[]`/`File`/`CompletableFuture<T>`/`RipResponse<T>`) | `processRestRequest` branches on `method.getReturnType()`/`getGenericReturnType()` at runtime | The *generated method's actual return type* already matches — no runtime branching needed; the generated body picks the right `RestRequestProcessor` entry point (`decodeOrThrow`, `processAsync`, `wrapResponse`, ...) directly, decided once by the processor at compile time from the interface's declared return type |
| `@Url` | `resolveUrlParam` scans parameters for `@Url` at runtime | Known at compile time whether the method has a `@Url` parameter; generated code either takes the template path or the verbatim-URL path, no scan needed |
| `@Destination`/`DownloadProgressListener`/`UploadProgressListener` | Scanned per call | Positions known at compile time, passed directly |

The interceptor chain (`applyInterceptors`, `notifyAfterResponse`) is
unaffected either way — it's driven by the *runtime* interceptor
registry (`RestRequestProcessor.INTERCEPTORS`), which has nothing to do
with reflection on the calling method, so generated code calls it
exactly as today.

## 6. Testing strategy

- `RipIntegrationTest`'s existing ~100 tests all run twice: once against
  `RIP.getClient(...)` with the processor absent (today's reflective
  path — the module the test currently lives in should *not* depend on
  the new processor module, to keep this path genuinely exercised), and
  once from a *new* test module that does depend on the processor and
  re-runs the same `LocalApi` interface's test bodies against the
  generated implementation. Byte-for-byte identical `CapturedRequest`
  assertions in both runs are the acceptance bar — this is a dispatch
  change, and any observable difference is a regression by definition
  (per the non-goal in §3).
- A dedicated `compile-testing`-style test (Google's
  `com.google.testing.compile.CompilationSubject`, or a hand-rolled
  `javac` invocation via `javax.tools.JavaCompiler`) verifying that each
  `RestClientValidatorTest` interface that should fail validation today
  (e.g. `InvalidTimeoutConnectMillis`, `HeadersEntryMissingColon`) also
  fails **compilation** with a matching error message when run through
  the processor, and that every currently-valid interface compiles
  clean and produces a working generated class.
- A native-image smoke test (even a minimal one — build a tiny consumer
  jar with one `@RestClient` interface, `native-image` it, run one call
  against `RipIntegrationTest`'s local `HttpServer`) is the real proof
  this design achieves its stated goal (§1.1) — without it, "GraalVM
  friendly" is an unverified claim.

## 7. Open questions / risks

- **Can `RestClientValidator`'s logic realistically be shared between
  the runtime (`java.lang.reflect.Method`/`Parameter`) and compile-time
  (`javax.lang.model.element.ExecutableElement`/`VariableElement`)
  worlds without a painful abstraction layer?** These are genuinely
  different APIs with different capabilities (e.g. resolving an
  annotation's `Class<?>`-valued attribute works differently against a
  `TypeMirror` than against a live `Class`). It may be more pragmatic to
  accept two independent (if structurally similar) validator
  implementations than to force a shared abstraction — a decision to
  make once the compile-time side is actually attempted, not before.
- **Naming collisions and visibility.** A generated `UserApi_RipImpl` in
  the same package as `UserApi` needs `UserApi` (and every type its
  methods reference) to be at least package-visible to the generated
  class. A `@RestClient` interface declared `private` inside another
  class (as `RipIntegrationTest.LocalApi` is today, per `private
  interface LocalApi`) cannot have a top-level generated implementation
  see it in the ordinary way — this needs either restricting the
  processor to non-private/non-nested interfaces (with a clear compile
  error for a private one, forcing that case onto the reflective
  fallback, which already works for it) or nesting the generated class
  appropriately. This directly affects whether `RipIntegrationTest`
  itself (which uses private nested interfaces throughout) can exercise
  the generated path without restructuring its test interfaces to be
  top-level.
- **Where does the artifact live, and does the extra module hurt
  adoption?** A separate `rest-in-peace-processor` artifact keeps the
  core dependency-free, but doubles what a consumer must add
  (`rest-in-peace` for the API, `rest-in-peace-processor` on
  `annotationProcessorPath` for the fast path). Bundling the processor
  in the main artifact is simpler to adopt but forces every consumer's
  build to carry annotation-processing infra even if they never use it.
  A `provided`-scope inclusion in the *same* artifact (so it's on the
  classpath but only activates as a processor when explicitly
  registered, or auto-activates via the `META-INF/services` SPI with no
  opt-in needed at all) is a middle ground worth prototyping before
  deciding.
- **Interaction with the future pluggable `CallAdapter` roadmap item.**
  If return-type handling becomes pluggable (letting Kotlin
  `suspend fun`/RxJava/Reactor be added as optional modules), the
  codegen's per-return-type dispatch (§5's last row) needs to call
  through whatever that adapter mechanism ends up being, not hardcode
  the current five shapes. Sequencing question: build this first and
  retrofit adapter support, or design the adapter interface first so
  codegen targets it from day one? Leaning toward the latter to avoid
  redoing the codegen's return-type-handling generation twice.
- **Debuggability of generated code.** Generated sources should land
  somewhere a consumer can actually inspect during development (Maven's
  standard `target/generated-sources/annotations`, which most annotation
  processors already use by convention) rather than being hidden inside
  a jar — part of the stated value (§1.3) is a developer being able to
  open and read what a call does.

## 8. Rollout plan (sketch)

1. Prototype the processor against a *minimal* subset first — `@GET`
   with `@PathParam`/`@QueryParam` and a plain `String`/POJO return type
   only, no retry/multipart/async/interceptors — to validate the overall
   mechanism (SPI registration, `RIP.getClient` fallback logic, codegen
   plumbing into `RestRequestProcessor`) before investing in full
   feature parity.
2. Extend feature-by-feature per §5's table, each landing as its own PR
   with its own `RipIntegrationTest`-mirroring test pass, in roughly the
   order features were originally added to the library (simplest,
   most-used first).
3. Add the native-image smoke test (§6) once the minimal subset from
   step 1 is solid — it's the concrete, checkable proof of the whole
   effort's stated payoff, and should not wait until every feature is
   ported to first confirm the mechanism actually works under
   native-image.
4. Full feature parity + the compile-testing validation suite (§6) is
   the exit criterion for calling this roadmap item done — at which
   point `ROADMAP.md`'s entry gets checked off with a summary matching
   the style of every other entry there.

Each step above is independently shippable and reversible (nothing in
steps 1–3 changes behavior for a consumer who doesn't add the processor
dependency), consistent with how every other roadmap item in this
codebase has landed as a small, complete PR rather than a long-lived
branch.

## 9. What step 1 actually landed as

Implementing the minimal prototype (§8 step 1) surfaced one real
correction to §4.1's assumption and one deliberate scope-narrowing beyond
what §4/§5 sketched. Recorded here rather than silently editing the
sections above, since both were genuine discoveries, not just style
choices.

### 9.1 The processor lives in the main module, not a separate one - with a real bootstrapping catch

§4.1 proposed a separate `rest-in-peace-processor` module partly as a
style/dependency-footprint question. It turns out to also be load-bearing
for a reason the original sketch missed entirely: **an annotation
processor cannot process any source compiled in the same `javac`
invocation that also compiles the processor itself.** `RestClientProcessor`
compiled fine sitting in this module's own `src/main/java` - but the moment
`META-INF/services/javax.annotation.processing.Processor` also sat in
`src/main/resources`, `mvn compile` itself failed outright
(`Provider com.shri.restinpeace.processor.RestClientProcessor not found`):
javac discovers processors via SPI from the classpath *before* compiling,
and `RestClientProcessor.class` doesn't exist yet at the start of the very
invocation that's compiling it.

Given the choice this forced - a genuinely separate Maven module, or some
way to keep everything in one module - step 1 took the single-module path,
using a fact the sketch didn't consider: Maven's `compile` and
`test-compile` phases are two *separate* `javac` invocations, sequential
within the same module. By the time `test-compile` runs, `compile`'s
output (including the now fully-built `RestClientProcessor.class`) is
already sitting in `target/classes`, which *is* on the test-compile
classpath - so a test-scope `@RestClient` interface (`GeneratedApi`, added
specifically for this) gets processed correctly, while `src/main/java`
itself never tries to self-process. This needed one small `pom.xml`
change: an explicit `default-compile` execution override setting
`<proc>none</proc>`, applying only to the main-source compile (leaving
`default-testCompile` on its default of "process annotations if a
processor is found," which is what actually exercises the mechanism).

This resolves the bootstrapping problem for developing and testing the
processor *within this repository*, and - importantly - doesn't compromise
the real-world goal either: a downstream consumer adding
`rest-in-peace-1.0.0.x.jar` as an ordinary dependency hits neither
constraint, since by the time their own build starts, our processor is
already-compiled bytecode sitting in an already-published jar on their
classpath - a completely separate `javac` invocation in a separate
project, with no bootstrapping order problem at all. The `<proc>none</proc>`
override is purely a "this module doesn't need to process its own
(nonexistent) `@RestClient` interfaces during its own main build" setting,
invisible to consumers. §7's separate-module open question is downgraded
from "which is nicer" to "known to work either way, single-module chosen
for step 1 to avoid a reactor restructure"; it may still be worth revisiting
if the processor grows large enough that main-artifact bloat becomes a
real concern for a consumer who never uses it.

### 9.2 Deliberately narrower parameter shape than the general design

§5's table describes `@QueryParam`'s general handling (including
`required`/`defaultValue`, via the shared `resolveValue` helper). Step 1's
`RestClientProcessor` only generates for a `@QueryParam` at its defaults
(`required = false`, `defaultValue` unset) - a parameter using either
falls the whole interface back to the reflective proxy, rather than
reproducing `resolveValue`'s semantics in codegen. This was a scope
decision to keep step 1's surface small and unambiguously correct, not a
technical obstacle - `resolveValue` is a small, already-`private` static
method on `RestRequestProcessor` and a natural candidate to become another
literal-parameter call from generated code (alongside
`processGeneratedRequest`) in a follow-up step.

### 9.3 What's now real and testable

- `RestRequestProcessor.processGeneratedRequest(...)`: the new
  non-reflective entry point, reusing (not reimplementing)
  `createRequest`, `applyQueryValue`, `applyInterceptors`,
  `executeSyncWithRetry`, and `decodeOrThrow` - each of those needed only a
  `method == null` guard where they read `method.getAnnotation(...)`
  (`Retry` in `executeSyncWithRetry`, `ErrorType` in the private
  `decodeBody`), since a call routed through the generated path has no
  `Retry`/`ErrorType` to look up by construction (the processor never
  generates for a method using either).
- `RestClientProcessor`: walks each `@RestClient` interface's methods via
  `javax.lang.model`, bails out (leaving the *whole* interface to the
  reflective proxy) the moment any method or parameter falls outside the
  supported shape, and otherwise emits `<Interface>_RipImpl` via
  `Filer.createSourceFile`.
- `RIP.getClient`'s three overloads all now try
  `Class.forName(interfaceName + "_RipImpl")` (with a public constructor
  taking a `RestRequestProcessor`) before falling back to
  `Proxy.newProxyInstance`, exactly as §4.3 sketched, module-adjustment
  aside.
- `GeneratedApi`/`GeneratedApiTest`: a new top-level (not nested, per §7's
  private/nested-interface open question - still unresolved for that case)
  `@RestClient` interface and a test asserting both that
  `RIP.getClient(GeneratedApi.class)` returns something whose class name
  ends in `_RipImpl` (proving the generated path was actually taken, not
  silently falling back) and that a real call through it produces the
  expected request/response over a local `HttpServer`.
- Verified: the full pre-existing `RipIntegrationTest`/
  `RestClientValidatorTest` suite (177 tests, all still exercising nested
  test interfaces the processor correctly skips) stays green and
  unaffected, on both the default and a real JDK 8 toolchain, with a clean
  `javadoc:javadoc`.
- Also verified against a genuinely separate downstream build, not just
  this repo's own test-compile: `samples/compile-time-proxy-consumer` is a
  standalone Maven project (in-tree, `.github/workflows/sample-consumer-test.yml`
  builds and runs it on every push/PR) with an ordinary dependency on the
  library and no processor configuration of its own, confirming the SPI
  auto-activation §9.1 describes actually works end to end for a real
  consumer, not just within this module's compile/test-compile staging.

### 9.4 Step 2, first slice: `@Timeout`/`@Retry` - and a bug this slice closed

Extending the processor to a second feature surfaced a real correctness bug
in what step 1 shipped as v1.0.0.20: `toSupportedMethodModel` never actually
checked a method for `@Retry`, `@Timeout`, `@Headers`, or `@ErrorType` -
despite the class Javadoc and this doc both claiming all four disqualify a
method. Every *parameter*-level disqualifier (`@HeaderParam`, `@Body`,
`@Url`, `@QueryMap`, ...) worked correctly, because `toSupportedParamModel`
rejects any parameter annotation it doesn't recognize - but these four are
*method*-level annotations, which nothing was inspecting at all. A method
otherwise within the supported shape but also carrying `@Retry` or
`@Timeout` would have been silently included in the generated
implementation, which had no code path applying either annotation -
producing a generated class that silently dropped the retry/timeout
behavior instead of either honoring it or falling back to the reflective
proxy. This never manifested in the shipped test suite because every
existing `@Retry`/`@Timeout` test interface is a private nested interface
(correctly skipped for an unrelated reason - §7's nested/private
restriction), and the only top-level generated-path test interface
(`GeneratedApi`) didn't combine the supported shape with either annotation.

Fixed as part of landing real `@Timeout`/`@Retry` support, not as a
separate patch: `toSupportedMethodModel` now explicitly disqualifies a
method carrying `@Headers` or `@ErrorType` (still unsupported, closing the
bug for those two the same way), and reads `@Timeout`/`@Retry` into the
method model instead of ignoring them, when present. `GeneratedApiWithHeaders`
is new regression coverage: a `GeneratedApi`-shaped method with `@Headers`
added, asserting no `_RipImpl` is generated for it.

Mechanically, this is exactly what §4.2/§5 sketched - `RestRequestProcessor`
gained non-reflective, literal-argument counterparts of its existing
`Method`-based logic rather than any new machinery:

- `applyTimeout(HttpRequest, int connectMillis, int readMillis)` - the
  `Method`-based overload now just extracts `@Timeout`'s two fields and
  delegates to this one. `-1` (matching `@Timeout`'s own "unset" default)
  means "don't touch this timeout", so the processor always passes both
  literals regardless of whether `@Timeout` is present, and the case where
  it isn't naturally becomes two no-ops instead of needing a separate
  "hasTimeout" flag.
- `executeSyncWithRetry(..., boolean hasRetry, int times, long delayMillis, double backoffMultiplier, int[] retryOnStatus)` -
  the retry loop itself (attempt counting, `isRetryableStatus`, `nextDelay`
  backoff) was already independent of reading a live `Retry` annotation
  instance; only the *values* needed to come from somewhere else. Unlike
  `@Timeout`, `@Retry` does need an explicit `hasRetry` flag rather than a
  sentinel value, since every one of its fields (`times`, `delayMillis`,
  `backoffMultiplier`, `retryOnStatus`) has an ordinary-looking default that
  doesn't double as "absent" the way `@Timeout`'s `-1` does. `nextDelay`
  changed from taking a `Retry` to taking a plain `double backoffMultiplier`,
  which is what it only ever used from the annotation anyway.
- `processGeneratedRequest`'s signature grew by seven parameters
  (`connectMillis`, `readMillis`, `hasRetry`, `retryTimes`,
  `retryDelayMillis`, `retryBackoffMultiplier`, `retryOnStatus`) to carry
  these through. §5 anticipated this pattern of growth ("a handful of new,
  non-reflective entry points... called directly without ever constructing
  a `Method` object") without settling in advance how many parameters one
  entry point should carry versus splitting into several - this slice's
  answer was to keep extending the one method, since the two features are
  always applied together in the same generated call and splitting them
  into separate calls the generated code would chain wouldn't remove any
  complexity, just relocate it.

The async retry path (`executeAsyncWithRetry`/`attemptAsync`) was
deliberately left untouched - `processGeneratedRequest` only ever calls the
synchronous path, since `CompletableFuture` return types are a separate,
not-yet-supported entry in §5's table. Only `nextDelay`'s signature change
touched `attemptAsync`, mechanically (it still reads `retry.backoffMultiplier()`
from the `Method`-based `Retry` it already has).

### 9.5 Step 2, second slice: full header/query/body/URL/error-type support

This slice covered `@Headers`, `@HeaderParam`, `@HeaderMap`, `@QueryMap`,
required-or-defaulted `@QueryParam`/`@HeaderParam` (the `resolveValue`
semantics step 1 explicitly scoped out, per §9.2), `@Body`, `@Url`, and
`@ErrorType` - everything in §5's table except `@Multipart`/`@Part`/
`@PartMap` and the return-type expansion.

#### 9.5.1 Replaced the single `processGeneratedRequest` entry point with a sequence of calls

Adding just `@Timeout`/`@Retry` in the first slice already grew
`processGeneratedRequest` to 15 parameters. Extending the *same* method to
also cover headers, query/header maps, a body, and a `@Url` override would
have pushed it well past 25 - unreadable, and error-prone to keep each
generated call's argument *position* correct against the method's growing
signature. Instead, `RestRequestProcessor` now exposes a small set of
non-reflective, `public` primitives - `resolveGeneratedUrl`,
`requireUrlParam`, `createGeneratedRequest`, `applyGeneratedHeaders`,
`resolveValue`, `applyQueryValue`, `applyQueryMap`, `applyHeaderMap`,
`applyGeneratedBodyIfPresent`, and a terminal `finishGeneratedSync` - and
generated code calls a *sequence* of them, one per feature the method
actually uses, mirroring §4.2's original sketch ("a handful of new,
non-reflective entry points... called directly") more closely than step 1's
single mega-call did. Each of these is `public` only because generated code
lives in an arbitrary consumer package - not part of RIP's
application-facing API, documented as such at the top of the new methods'
section in `RestRequestProcessor`.

A header param value (`@HeaderParam`) doesn't get its own RIP method at
all - generated code calls `resolveValue` then, if non-`null`, Unirest's own
public `request.headerReplace(name, String.valueOf(value))` directly, since
that's all the reflective path itself does past `resolveValue`. Reusing a
public method on a public third-party type the generated code already holds
a reference to, instead of wrapping it in another RIP method, was a
deliberate choice to keep the new public surface as small as it can be.

#### 9.5.2 Decoupling `@ErrorType` from `Method` (and reading a `Class`-valued annotation attribute at compile time)

`decodeBody`/`decodeOrThrow`/`notifyAfterResponse`/`executeSyncWithRetry`/
`executeAsyncWithRetry`/`attemptAsync` all used to take (or derive from) a
`Method`, reading `method.getAnnotation(ErrorType.class)` internally to
decide how to decode an error body. Since generated code has no `Method`,
every one of these was refactored to take an explicit `Class<?> errorType`
parameter instead - the reflective path now derives it once
(`errorTypeOf(method)`) and passes it down like any other literal, and
generated code passes its `@ErrorType`'s value directly. This is a pure
simplification for the reflective path too, not just an accommodation for
the generated one: `Method` was only ever a vehicle for that one annotation
lookup in these methods.

Reading `@ErrorType`'s `Class<?> value()` during annotation processing
can't just call `errorType.value()` - the class it names may not even be
compiled yet, so the JDK deliberately throws `MirroredTypeException` instead
of trying to load it; catching that and reading `getTypeMirror()` off the
exception is the standard, if unintuitive, annotation-processor idiom for
this, used in `errorTypeClassNameOf`.

#### 9.5.3 A same-named parameter shadowing a generated local variable - and the fix

`GeneratedApi.getByUrl(@Url String url)` - an entirely reasonable parameter
name for a `@Url` parameter to have - broke compilation of its own generated
class: `RestClientProcessor` always declared a local named `url` to hold the
resolved URL, and Java doesn't allow redeclaring a variable of the same name
in the same scope, so `String url = RestRequestProcessor.requireUrlParam(url, ...)`
failed with "variable url is already defined". The same risk existed for
every other synthetic local the generator introduces (`request`, `context`,
`result`, a per-parameter scratch variable for `resolveValue`'s result) and
even for the generated class's own `ripProcessor` field, if a parameter
happened to share that name. Fixed by renaming every synthetic identifier to
a `__rip`-prefixed name (`__ripUrl`, `__ripRequest`, `__ripContext`,
`__ripResult`, `__ripValue`) that a real Java parameter is never going to
collide with, and by qualifying every field access as `this.ripProcessor`
rather than bare `ripProcessor` so a same-named parameter shadowing the
field can't silently break a call site either. `GeneratedApi.getByUrl` is
now permanent regression coverage for this - see `GeneratedApiTest`.

#### 9.5.4 What's now real and testable

`GeneratedApi` gained `echo` (fixed `@HeaderParam` + defaulted
`@HeaderParam` + required `@QueryParam` + `@QueryMap` + `@HeaderMap`),
`echoBody` (`@Body` on a `@POST`), `getByUrl` (`@Url`), and `getError`
(`@ErrorType`, asserting the decoded error body's fields after catching
`RestInPeaceHttpException`) - each verified against a real embedded
`HttpServer`, mirroring the assertion style `RipIntegrationTest` already
uses for the same features on the reflective path.

`GeneratedApiWithHeaders` - originally step 1's regression test proving
`@Headers` correctly disqualified a method - is repurposed as a *positive*
test now that `@Headers` is supported, asserting a real `_RipImpl` is
generated and the header-carrying call succeeds. `GeneratedApiWithMultipart`
is new regression coverage taking over the "still correctly disqualifies"
role for a feature this slice didn't cover.

### 9.6 Step 2, third slice: `@Multipart`/`@Part`/`@PartMap`/`UploadProgressListener`

Mechanically the smallest slice so far, since the reflective path's own
`applyPartValue`/`applyPartMap`/`applyUploadMonitor` were already
non-reflective (literal `MultipartBody`/name/value arguments, no `Method`
or `Parameter` involved) - the same pattern §9.5.1 already established for
`applyQueryValue`/`applyQueryMap`/`applyHeaderMap` applied directly, just
widening their visibility to `public`. The one genuinely new piece is
`beginGeneratedMultipart(HttpRequest<?> request)`, the generated-code
counterpart of the reflective path's own
`((HttpRequestWithBody) request).multiPartContent()` cast-and-call for a
`@Multipart` method, emitted once per method right after
`applyGeneratedHeaders` (mirroring `applyParams`'s own ordering - multipart
conversion happens before any per-parameter `@Part`/`@PartMap` application)
and reassigning the generated method's `__ripRequest` local to the
resulting `MultipartBody`.

`UploadProgressListener` needed a small addition to `toSupportedParamModel`
itself: unlike every other supported parameter kind, it carries no
annotation at all - the reflective path detects it by parameter *type*
(`parameter.getType() == UploadProgressListener.class`), so the processor
does the same by comparing the parameter's `TypeMirror` string form against
the class's fully-qualified name, checked before the usual
exactly-one-annotation dispatch rather than folded into it.

#### 9.6.1 A new codegen-safety class of bug, generalized from the `@Url` one

§9.5.3 fixed one instance of "generated code references a variable this
processor doesn't always declare" (a `@Url` parameter shadowing the `url`
local). `@Part`/`@PartMap`/`UploadProgressListener` created a *second*
instance of the same underlying hazard: all three only make sense on an
`@Multipart` method, and their generated code references `__ripMultipart` -
a local only declared when `method.isMultipart` is true. A method
combining, say, `@Part` with no `@Multipart` (itself a validation error the
runtime validator already catches) would previously have been silently
accepted by `toSupportedMethodModel` and generated as source referencing an
undeclared `__ripMultipart` - not a runtime misbehavior like most other
validation errors produce when the processor doesn't specially guard
against them, but a **compile failure of the generated class itself**,
breaking the entire downstream build.

Recognizing `@Url`-on-non-`String` (§9.5.3) and this as the same class of
problem - a validation error that would corrupt the *generated source*
itself, rather than merely misbehaving at runtime for an interface that
was never going to pass validation anyway - is the more important lesson
than either individual fix: any future slice adding a parameter/return kind
whose generated code depends on another feature also being present needs
the same explicit cross-check in `toSupportedMethodModel`, disqualifying
the whole method (never partially) if that precondition doesn't hold.
Fixed here by checking, after building a method's full parameter list,
that every `PART`/`PART_MAP`/`UPLOAD_PROGRESS`-kind parameter only appears
when `isMultipart` is true (and, symmetrically, that `@Multipart` never
coincides with a `BODY`-kind parameter - also a validation error, also
otherwise harmless at the codegen level since `applyGeneratedBodyIfPresent`
only fails at *runtime*, but disqualified anyway since the combination can
never be valid).

#### 9.6.2 What's now real and testable

`GeneratedApiWithMultipart` - originally this slice's own "still correctly
disqualifies" regression coverage - is repurposed as a *positive* test
(the same pattern §9.5's `GeneratedApiWithHeaders` followed), asserting a
real `_RipImpl` is generated and that a `@Part` value actually lands in the
multipart-encoded request body. `GeneratedApiWithAsyncReturn` takes over
the "still correctly disqualifies" role using a `CompletableFuture<String>`
return type - the next slice's feature, and a good one to prove the
current disqualification logic is set up correctly. The in-repo sample
consumer's `UnsupportedApi` needed updating *again* for the same reason as
§9.5 - it had been moved to `@Multipart`/`@Part` last slice, which this
slice made real - so it now demonstrates the fallback via a
`CompletableFuture<String>` return type as well, which surfaced a genuine
bug in the sample itself: nothing had ever exercised its `UnsupportedApi`'s
now-async fallback call before, and Unirest's async client's non-daemon
threads kept the sample's JVM alive indefinitely after printing its
success message. Fixed by calling `RIP.useDaemonThreadsForAsync()` once at
the top of the sample's `Main.main`, exactly the scenario that method's own
documentation describes.

### 9.7 Step 2, fourth slice: `byte[]`/`File`+`@Destination`+`DownloadProgressListener`/`RipResponse<T>` return types

The first slice to change *what a generated method returns*, not just what
it can accept as input. Every prior slice kept `finishGeneratedSync` as the
single terminal call, differing only in what gets built up before it; this
one adds four sibling terminal methods to `RestRequestProcessor` -
`finishGeneratedSyncBytes`, `finishGeneratedSyncFile`,
`finishGeneratedSyncRipResponse`, `finishGeneratedSyncRipResponseBytes` -
mirroring `processRestRequest`'s own return-type branches, each reusing
the same `executeSyncWithRetry`/`decodeOrThrow`/`wrapResponse` machinery
the reflective path and every earlier generated-code slice already share.
`RestClientProcessor` picks the right one per method, decided once at
compile time from the interface's declared return type - exactly what §5's
table originally described as the payoff of moving return-type dispatch
out of the runtime.

#### 9.7.1 `returnTypeNameOf` became `returnModelOf`

Every earlier slice treated a method's return type as a single opaque
string (`returnTypeName`) - fine when every supported return type decoded
the same way. Supporting `byte[]`/`File`/`RipResponse<T>` alongside
`void`/`String`/POJO needs the processor to know *which* of those shapes a
method has, not just its literal type name, so `MethodModel` now carries a
`ReturnModel` (a `ReturnKind` - `VOID`/`PLAIN`/`BYTES`/`FILE`/
`RIP_RESPONSE` - plus the return type's own literal name and, for
`RIP_RESPONSE`, the wrapped inner type's name) instead of a bare string.
Detecting `byte[]` needs a new branch entirely - `TypeKind.ARRAY`, which
every earlier slice's `returnTypeNameOf` unconditionally rejected via its
"not `TypeKind.DECLARED`" check, since nothing before this slice needed to
tell an array return type apart from disqualifying it. Detecting
`RipResponse<T>` needs comparing the return type's *erasure* against
`com.shri.restinpeace.RipResponse` (via `Types.erasure`, the standard way
to check "is this raw type X" while ignoring its type arguments) and then
recursively classifying `T` with the same array/declared/generic checks -
mirroring the reflective path's own `resolveWrappedType`.

#### 9.7.2 Extending the codegen-safety cross-check rule from §9.6.1 to return types

§9.6.1 generalized "a parameter kind whose generated code depends on
another feature also being present" into an explicit rule, applied there
to `@Part`/`@PartMap`/`UploadProgressListener` needing `@Multipart`. This
slice is the same rule applied to a *return* type instead of a parameter:
`@Destination`/`DownloadProgressListener` only make sense - and only
compile as generated code - on a `File`-returning method, since their
codegen references the destination `File` local `finishGeneratedSyncFile`
needs. `toSupportedMethodModel` now cross-checks this the same way it
already does for `@Multipart`: any `DESTINATION`/`DOWNLOAD_PROGRESS`-kind
parameter on a method whose `ReturnKind` isn't `FILE` disqualifies the
whole method, and (new, since a `File` return needs to know unambiguously
which parameter to write into, unlike anything checked before) exactly one
`@Destination` parameter is required whenever the return kind *is* `FILE`
- zero or more than one both disqualify, rather than generating source
that references the wrong local or none at all.

#### 9.7.3 What's now real and testable

`GeneratedApi` gained `getBinary` (`byte[]`), `downloadBinary`
(`File`+`@Destination`+`DownloadProgressListener`, asserting both the
written file's bytes and that the progress listener actually fired),
`getWithResponse` (`RipResponse<String>`), and `getBinaryWithResponse`
(`RipResponse<byte[]>`) - each verified against a real embedded
`HttpServer`'s `/binary` endpoint, mirroring `RipIntegrationTest`'s own
binary-response test pattern. `GeneratedApiWithAsyncReturn` (added in
§9.6) continues to serve as the "still correctly disqualifies" regression
coverage, now for the one feature genuinely still missing:
`CompletableFuture<T>`.

### 9.8 Step 2, fifth and final slice: `CompletableFuture<T>` (async), for every return-type shape

The last item in §5's table, and the only slice to widen the *set of
return-type shapes* to a genuinely orthogonal dimension rather than adding
a new one to the list: `CompletableFuture<T>` isn't a sibling of
`PLAIN`/`BYTES`/`FILE`/`RIP_RESPONSE`, it's each of them wrapped - a
method can return a plain `String` synchronously or asynchronously, and
the same is true of `byte[]`, `File`, and `RipResponse<T>`. That meant
doubling `RestRequestProcessor`'s terminal-call surface (one async sibling
per existing sync terminal method) rather than adding a fifth `ReturnKind`
case to `RestClientProcessor`'s dispatch switch.

#### 9.8.1 Five new async terminal methods, mirroring the five sync ones

`RestRequestProcessor` gained `finishGeneratedAsync`,
`finishGeneratedAsyncBytes`, `finishGeneratedAsyncFile`,
`finishGeneratedAsyncRipResponse`, and
`finishGeneratedAsyncRipResponseBytes` - each the direct async counterpart
of an existing `finishGeneratedSync*` method, built the same way the
reflective path's own `processAsync` already works: apply interceptors,
call `executeAsyncWithRetry` (which already existed, used by the
reflective path, and needed no changes) with the request's
`::asStringAsync`/`::asBytesAsync` method reference instead of a
synchronous call, then `.thenApply(response -> decodeOrThrow(...))` to
chain the same decoding logic every sync path already shares, onto the
returned `CompletableFuture` instead of running it inline. No new decoding
or retry logic was needed anywhere - the entire slice is "run the existing
machinery on a future instead of a value."

#### 9.8.2 `ReturnModel` gained `isAsync`; `decodeTypeName` replaces `innerTypeName`; `VOID` folded into `PLAIN`

Detecting `CompletableFuture<T>` needs the same "compare the type's
erasure against a known raw type" idiom §9.7.1 introduced for
`RipResponse<T>` - here against `java.util.concurrent.CompletableFuture` -
but unlike `RipResponse<T>`, a `CompletableFuture<T>`'s `T` isn't itself a
new terminal shape; it's any of the *existing* four kinds, classified the
same way whether or not it's wrapped. `returnModelOf` was split into an
outer method that checks for the `CompletableFuture` wrapper first
(recording `isAsync = true` and recursing into a new
`nonAsyncReturnModelOf(TypeMirror)` helper for the wrapped type argument)
and falls through to the same helper directly, unwrapped, when there's no
`CompletableFuture` - so a method's `ReturnKind` is always classified by
the same code regardless of async-ness.

This also exposed that `ReturnModel`'s `innerTypeName` field, added in
§9.7.1 specifically for `RipResponse<T>`'s wrapped inner type, was really
serving double duty: it was also being used, for every other kind, as
"the type to decode the response body into" - which is `javaTypeName`
itself for `PLAIN`/`BYTES`/`FILE`, but diverges from it once a
`CompletableFuture` is involved (a method returning
`CompletableFuture<String>` has `javaTypeName =
"java.util.concurrent.CompletableFuture<java.lang.String>"` but must still
decode into plain `String.class`). Renamed to `decodeTypeName` to name
what it actually is - "the literal `Class<?>` to decode the response body
into" - independent of `isAsync`, since wrapping a return type in a
`CompletableFuture` never changes what class the body decodes into, only
what the generated method's own signature returns and which
`finishGenerated*` method it calls. `ReturnKind.VOID` was folded into
`PLAIN` at the same time (distinguished, now that a dedicated dispatch
case is no longer worth keeping for it, purely by `decodeTypeName` being
the literal string `"void"`), since async and sync `void`-returning
methods otherwise needed no different handling from `String`/POJO ones
beyond that string check already present in `finishGeneratedSync`/
`finishGeneratedAsync`.

#### 9.8.3 What's now real and testable, and what's permanently not

`GeneratedApiWithAsyncReturn` - the "still correctly disqualifies"
regression interface since §9.6 - is repurposed as a *positive* test
(the same pattern every terminal "still unsupported" interface in this
package has followed as its feature graduated: `GeneratedApiWithHeaders`
in §9.5, `GeneratedApiWithMultipart` in §9.6), asserting a real
`_RipImpl` is generated for a `CompletableFuture<String>`-returning
method and that calling it and blocking on `.get(5, TimeUnit.SECONDS)`
produces the correct decoded result.

Since every item in §5's table is now supported, a new regression
interface was needed for a feature that is not merely "not yet
implemented" but genuinely can never be: `GeneratedApiWithListReturn`,
using a `List<String>` return type. `nonAsyncReturnModelOf` returns `null`
for it (it's a `TypeKind.DECLARED` type with a non-empty type-argument
list that matches none of the recognized raw types), so no
`_RipImpl` is generated and `RIP.getClient(...)` falls back to the
reflective proxy - the reflective path can still handle it, since
`method.getReturnType()` erases to plain `List` and Jackson can
deserialize a JSON array into a raw `List`, but the compile-time
generator has no way to hand `decodeBody` anything more specific than a
`Class<?>` literal, and `List.class` alone would lose the element type
entirely for any consumer that cares.

The in-repo sample consumer's `UnsupportedApi` needed updating for the
third time, for the same reason as §9.5 and §9.6 - it had been moved to
`CompletableFuture<String>` in §9.6, which this slice made real - so it
now demonstrates the fallback via the same `List<String>` return type as
`GeneratedApiWithListReturn`. This also let the sample drop the
`RIP.useDaemonThreadsForAsync()` workaround §9.6 added: with nothing left
in the sample exercising Unirest's async client, the non-daemon-thread
hang that workaround existed for no longer applies. The sample's fallback
demonstration for `UnsupportedApi` now stops at confirming no generated
class exists and that `RIP.getClient(...)` returns the reflective proxy -
it doesn't call through it, since the sample's plain-text local server
response isn't valid JSON and decoding it into a `List` would fail,
which was never the point of that demonstration (only the fallback
itself is).
