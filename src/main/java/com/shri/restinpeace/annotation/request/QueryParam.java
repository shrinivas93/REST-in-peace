package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;
import com.shri.restinpeace.constant.RIPConstant;

/**
 * Appends a query string parameter to the request from the annotated
 * parameter's value.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.QUERY)

public @interface QueryParam {
	/**
	 * The query parameter name.
	 *
	 * @return the query parameter name
	 */
	String value();

	/**
	 * If true, a {@code null} argument with no {@link #defaultValue()} throws
	 * a {@link com.shri.restinpeace.exception.RestInPeaceException} at call time.
	 *
	 * @return whether the query parameter is required
	 */
	boolean required() default false;

	/**
	 * Value used when the argument is {@code null}.
	 *
	 * @return the default value
	 */
	String defaultValue() default RIPConstant.DEFAULT;
}
