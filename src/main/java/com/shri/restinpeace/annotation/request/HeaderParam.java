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
 * Sets an HTTP header on the request from the annotated parameter's value.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.HEADER)

public @interface HeaderParam {
	/**
	 * The header name to set.
	 *
	 * @return the header name
	 */
	String value();

	/**
	 * If true, a {@code null} argument with no {@link #defaultValue()} throws
	 * a {@link com.shri.restinpeace.exception.RestInPeaceException} at call time.
	 *
	 * @return whether the header is required
	 */
	boolean required() default false;

	/**
	 * Value used when the argument is {@code null}.
	 *
	 * @return the default value
	 */
	String defaultValue() default RIPConstant.DEFAULT;
}
