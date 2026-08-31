package com.shri.restinpeace.annotation.error;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares what class to deserialize a method's error response body into.
 * A response with a non-2xx status always throws
 * {@link com.shri.restinpeace.exception.RestInPeaceHttpException}, whether
 * or not this annotation is present - {@code @ErrorType} only controls what
 * {@link com.shri.restinpeace.exception.RestInPeaceHttpException#getErrorBody()}
 * returns; without it, that's the raw body as a {@code String}.
 *
 * <pre>{@code
 * @GET("/users/{id}")
 * @ErrorType(ApiError.class)
 * User getUser(@PathParam("id") String id);
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ErrorType {

	/**
	 * The class to deserialize the error response body into.
	 *
	 * @return the error body class
	 */
	Class<?> value();

}
