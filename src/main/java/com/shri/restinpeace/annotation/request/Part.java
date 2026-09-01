package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * One field of a {@link Multipart @Multipart} method's multipart body. Only
 * valid on a method also annotated {@code @Multipart}; only {@code String}
 * (a plain form field) and {@code java.io.File} (a file part) parameters are
 * supported.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.BODY)

public @interface Part {
	/**
	 * The multipart field name.
	 *
	 * @return the field name
	 */
	String value();

	/**
	 * If true, a {@code null} argument throws a
	 * {@link com.shri.restinpeace.exception.RestInPeaceException} at call time.
	 *
	 * @return whether the part is required
	 */
	boolean required() default false;
}
