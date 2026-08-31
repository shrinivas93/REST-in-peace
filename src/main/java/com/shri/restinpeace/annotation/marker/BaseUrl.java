package com.shri.restinpeace.annotation.marker;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the base URL for every relative method URL on a
 * {@code @RestClient} interface, so
 * {@code @GET}/{@code @POST}/etc. can use a path (e.g. {@code "/items/{id}"})
 * instead of repeating the full URL on every method.
 *
 * <p>
 * A method URL that is already absolute (starts with {@code http://} or
 * {@code https://}) is used as-is and ignores {@code @BaseUrl} - a method
 * can always opt out of the interface's base URL by giving its own full
 * one. A relative method URL on an interface with no {@code @BaseUrl} fails
 * validation, since there would be nothing to resolve it against.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BaseUrl {

	/**
	 * The base URL, e.g. {@code "https://api.example.com"}. May itself
	 * contain a {@code {placeholder}} substituted by a matching
	 * {@link com.shri.restinpeace.annotation.request.PathParam @PathParam},
	 * the same as a method URL.
	 *
	 * @return the base URL
	 */
	String value();

}
