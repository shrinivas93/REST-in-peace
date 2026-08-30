package com.shri.restinpeace.annotation.method.meta;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * Internal meta-annotation applied to each request-parameter annotation
 * (e.g. {@code @PathParam}) so the framework can look up the
 * {@link HTTPRequestParam} kind it represents via reflection. Not intended
 * for direct use on a {@code @RestClient} method parameter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)

public @interface HTTPRequestParamMarker {
	/**
	 * The kind of request parameter binding the annotated parameter
	 * annotation represents.
	 *
	 * @return the request parameter kind
	 */
	HTTPRequestParam value();
}
