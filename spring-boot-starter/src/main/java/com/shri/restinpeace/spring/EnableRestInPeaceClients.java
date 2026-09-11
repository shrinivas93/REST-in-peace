package com.shri.restinpeace.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * Registers every {@code @RestClient}-annotated interface found under
 * {@link #basePackages()} as a Spring singleton bean - see
 * {@code docs/design/spring-boot-starter.md} in the parent repository for
 * the full design. Each interface is looked up by type
 * ({@code context.getBean(UserApi.class)}) or by a bean name derived from
 * its simple class name, decapitalized (e.g. {@code UserApi} &rarr;
 * {@code "userApi"}), the same convention a hand-written {@code @Bean}
 * method returning it would use.
 *
 * <p>
 * This chunk only constructs each client via
 * {@code RIP.getClient(Class)} - an interface must resolve its own base URL
 * entirely on its own (a real {@code @BaseUrl}, or one baked into every
 * method's URL template) until a later chunk adds Spring property
 * resolution.
 *
 * <pre>{@code
 * @Configuration
 * @EnableRestInPeaceClients(basePackages = "com.example.clients")
 * public class RestInPeaceClientsConfig {
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(RestInPeaceClientsRegistrar.class)
public @interface EnableRestInPeaceClients {

	/**
	 * Packages to scan for {@code @RestClient} interfaces. Defaults to the
	 * package of the class this annotation is declared on.
	 *
	 * @return the base packages to scan
	 */
	String[] basePackages() default {};

}
