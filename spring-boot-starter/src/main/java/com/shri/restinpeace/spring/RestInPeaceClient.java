package com.shri.restinpeace.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.marker.RestClient;

/**
 * Optional, Spring-only metadata for a {@code @RestClient} interface
 * registered via {@link EnableRestInPeaceClients} - applied *alongside*
 * {@link RestClient}, never in place of it, and never folded into it: the
 * core {@code rest-in-peace} library has no dependency on this module, in
 * either direction, and {@code @RestClient}'s own attributes stay exactly
 * as they are for every non-Spring consumer.
 *
 * <pre>{@code
 * @RestClient
 * @RestInPeaceClient(baseUrlProperty = "user-api.base-url")
 * interface UserApi {
 *     @GET("/users/{id}")
 *     User getUser(@PathParam("id") String id);
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestInPeaceClient {

	/**
	 * A Spring {@code Environment} property key to resolve this client's
	 * base URL from, read once at bean-registration time - a bridge for the
	 * one thing {@link BaseUrl} can never itself express, since a Java
	 * annotation attribute must always be a compile-time constant and can
	 * never hold a runtime-resolved Spring property placeholder. Left
	 * unset (the default), the interface must resolve its base URL
	 * entirely on its own, exactly as it would without this annotation at
	 * all - a real {@link BaseUrl}, or one baked into every method's URL
	 * template.
	 *
	 * @return the property key naming this client's base URL, or empty to
	 *         rely on {@link BaseUrl} instead
	 */
	String baseUrlProperty() default "";

	/**
	 * This client's bean name, and the key under which its own settings are
	 * looked up in {@code rest-in-peace.clients.<name>.*} (from a later
	 * chunk). Left unset (the default), the bean name falls back to the
	 * interface's decapitalized simple name (e.g. {@code UserApi} &rarr;
	 * {@code "userApi"}), the same convention a hand-written {@code @Bean}
	 * method returning it would use.
	 *
	 * @return this client's name, or empty to derive one from the
	 *         interface's simple name
	 */
	String name() default "";

}
