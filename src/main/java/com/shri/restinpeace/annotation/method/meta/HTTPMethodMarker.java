package com.shri.restinpeace.annotation.method.meta;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * Internal meta-annotation applied to each HTTP-verb annotation (e.g.
 * {@code @GET}) so the framework can look up the {@link HTTPMethod} it
 * represents via reflection. Not intended for direct use on a
 * {@code @RestClient} method.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)

public @interface HTTPMethodMarker {
	/**
	 * The HTTP method the annotated HTTP-verb annotation represents.
	 *
	 * @return the HTTP method
	 */
	HTTPMethod value();
}
