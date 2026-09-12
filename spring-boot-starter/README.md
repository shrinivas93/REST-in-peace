# rest-in-peace-spring-boot-starter

Spring Boot auto-configuration for [REST-in-peace](../README.md): auto-registers
every `@RestClient` interface found on the classpath as a Spring bean,
instead of one hand-written `@Bean` method per interface.

**Status: scaffolding only - no production code yet.** This project exists
so the build/CI wiring is real and verified before any feature code lands.
See [`docs/design/spring-boot-starter.md`](../docs/design/spring-boot-starter.md)
for the full design and the chunked rollout plan; each chunk after this one
adds real functionality here.

## Requirements

- Java 17 or newer
- Spring Boot 4.x

The core `rest-in-peace` library itself stays on Java 8 - this starter is a
sibling reactor module of `core/` (both share a version and release cadence
via their common parent, [`../pom.xml`](../pom.xml)), but it still resolves
`rest-in-peace` as an ordinary Maven dependency, the same way
[`samples/compile-time-proxy-consumer`](../samples/compile-time-proxy-consumer)
does - core has no dependency on this module either direction.

## Building and testing

From the repository root, `-am` also builds core first since this module
depends on it - both are resolved within the same reactor, so no separate
install step is needed:

```bash
mvn test -pl spring-boot-starter -am    # from the repo root
```

See [`.github/workflows/spring-boot-starter-test.yml`](../.github/workflows/spring-boot-starter-test.yml)
for the exact steps CI runs.
