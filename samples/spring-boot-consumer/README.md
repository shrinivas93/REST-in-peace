# Spring Boot starter - sample consumer

A standalone Maven project - deliberately **not** a module of the parent
`rest-in-peace` build, nor of `spring-boot-starter/` - showing how a real
downstream consumer sees the
[Spring Boot starter](../../docs/design/spring-boot-starter.md): add both
`rest-in-peace` and `rest-in-peace-spring-boot-starter` as ordinary
dependencies, annotate one `@RestClient` interface, and inject it like any
other Spring bean, with zero hand-written `@Bean` method.

## What it demonstrates

- **`UserApi`** is a plain `@RestClient` interface with `baseUrlProperty`
  set to `user-api.base-url` - the property `application.yml` configures,
  resolved by the starter from Spring's own `Environment`.
- **`Main`** is annotated `@SpringBootApplication @EnableRestInPeaceClients`
  with no `basePackages` (falling back to its own package, which is where
  `UserApi` lives) - the smallest possible real app. It starts a throwaway
  local HTTP server on a fixed port matching `application.yml`'s configured
  base URL, then a `CommandLineRunner` bean asks Spring for `UserApi`
  (constructor-injected, exactly like any other bean) and asserts (by
  throwing if anything's wrong) that a real call through it succeeds.
- No `spring-boot-starter-web` dependency is pulled in, so there's no
  embedded server keeping the JVM alive - the app runs the verification
  once and exits normally, making it a plain, CI-friendly `java -jar`/
  classpath run rather than a long-lived service.

## Running it

Neither `rest-in-peace` nor `rest-in-peace-spring-boot-starter` is
published anywhere `mvn` looks by default yet (see the "Maven Central
publishing" item in [`ROADMAP.md`](../../ROADMAP.md)), so you need locally
-installed builds of both first:

```sh
# From the repository root:
mvn install -DskipTests

# From spring-boot-starter/, using whatever version was just installed above:
cd spring-boot-starter
mvn install -DskipTests \
  -Drest-in-peace.version=$(grep -m1 -oP '(?<=<version>)[^<]+(?=</version>)' ../pom.xml)

# Then, from this directory:
cd ../samples/spring-boot-consumer
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt \
  -Drest-in-peace.version=$(grep -m1 -oP '(?<=<version>)[^<]+(?=</version>)' ../../pom.xml) \
  -Drest-in-peace-spring-boot-starter.version=$(grep -m1 -oP '(?<=<version>)[^<]+(?=</version>)' ../../spring-boot-starter/pom.xml)
java -cp "target/classes:src/main/resources:$(cat cp.txt)" com.example.consumer.Main
```

A successful run prints the decoded response and ends with:

```
VERIFICATION PASSED: the Spring Boot starter registered UserApi as a real Spring bean for a real downstream consumer.
```

The two `-D...version=...` flags override this `pom.xml`'s own hardcoded
defaults with whatever the two upstream projects' `pom.xml`s actually say
right now - three independent projects, so nothing keeps them in sync
automatically, and both versions do change over time. Omitting either flag
falls back to its hardcoded default, which will fail to resolve once it
drifts from whatever you just installed.

## Try it yourself

Add a second `@RestClient` interface alongside `UserApi` (with its own
`baseUrlProperty`, or a plain `@BaseUrl`) and inject it into `verify(...)`
too - no other change needed for the starter to pick it up, since
`@EnableRestInPeaceClients` scans the whole package, not just the
interfaces it already knows about.
