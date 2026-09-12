package com.shri.restinpeace.annotation.marker;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a REST client whose methods declare HTTP calls via
 * {@code @GET}/{@code @POST}/etc. Required on every interface passed to
 * {@link com.shri.restinpeace.RIP#getClient(Class)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestClient {

	/**
	 * A property key naming this client's base URL, read only by the
	 * optional {@code rest-in-peace-spring-boot-starter} module - inert
	 * metadata as far as {@link com.shri.restinpeace.RIP#getClient(Class)}
	 * and every other core-library consumer is concerned. Bridges the one
	 * thing {@link BaseUrl} can never itself express: a Java annotation
	 * attribute must always be a compile-time constant, so it can never
	 * hold a runtime-resolved Spring property placeholder. Left unset (the
	 * default), the interface must resolve its base URL entirely on its
	 * own - a real {@link BaseUrl}, or one baked into every method's URL
	 * template - exactly as if this attribute didn't exist.
	 *
	 * @return the property key naming this client's base URL, or empty to
	 *         rely on {@link BaseUrl} instead
	 */
	String baseUrlProperty() default "";

	/**
	 * This client's name, read only by the optional
	 * {@code rest-in-peace-spring-boot-starter} module - as with
	 * {@link #baseUrlProperty()}, inert metadata for every other consumer.
	 * Used there as the registered Spring bean name and the key under which
	 * this client's own settings are looked up. Left unset (the default),
	 * the starter falls back to the interface's decapitalized simple name
	 * (e.g. {@code UserApi} &rarr; {@code "userApi"}).
	 *
	 * @return this client's name, or empty to derive one from the
	 *         interface's simple name
	 */
	String name() default "";

}
