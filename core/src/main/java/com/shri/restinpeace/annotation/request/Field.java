package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * One name/value pair of a {@link FormUrlEncoded @FormUrlEncoded} method's
 * form-urlencoded body. Only valid on a method also annotated
 * {@code @FormUrlEncoded}. The argument is converted with
 * {@code String.valueOf(...)} and percent-encoded, except a {@code Collection}
 * argument, which is sent once per element under the same name (e.g.
 * {@code tag=a&tag=b}) - the same convention {@code @QueryParam} uses.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.BODY)
public @interface Field {

	/**
	 * The form field name.
	 *
	 * @return the field name
	 */
	String value();

	/**
	 * If true, a {@code null} argument throws a
	 * {@link com.shri.restinpeace.exception.RestInPeaceException} at call time.
	 *
	 * @return whether the field is required
	 */
	boolean required() default false;
}
