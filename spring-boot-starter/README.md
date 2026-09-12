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
separate, standalone Maven project (not a reactor module of the core
library's `pom.xml`) that depends on it as an ordinary dependency, the same
way [`samples/compile-time-proxy-consumer`](../samples/compile-time-proxy-consumer)
does.

## Building and testing

From the repository root, install the core library's current commit first,
then build this project against it:

```bash
mvn install -DskipTests --file core/pom.xml    # from the repo root
cd spring-boot-starter
mvn test -Drest-in-peace.version="$(grep -A1 -F '<artifactId>rest-in-peace</artifactId>' ../core/pom.xml | grep -oP '(?<=<version>)[^<]+(?=</version>)')"
```

See [`.github/workflows/spring-boot-starter-test.yml`](../.github/workflows/spring-boot-starter-test.yml)
for the exact steps CI runs.
