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
 * valid on a method also annotated {@code @Multipart}; {@code String} (a
 * plain form field), {@code java.io.File}, {@code byte[]}, and
 * {@code java.io.InputStream} (all sent as a file part) parameters are
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
	 * The file name sent for a {@code byte[]}/{@code java.io.InputStream}
	 * value, or to override a {@code java.io.File} value's own name.
	 * Ignored for a {@code String} value. Defaults to {@link #value()} when
	 * left unset, since {@code byte[]}/{@code InputStream} have no name of
	 * their own to fall back on.
	 *
	 * @return the file name, or {@code ""} to use {@link #value()}
	 */
	String fileName() default "";

	/**
	 * If true, a {@code null} argument throws a
	 * {@link com.shri.restinpeace.exception.RestInPeaceException} at call time.
	 *
	 * @return whether the part is required
	 */
	boolean required() default false;
}
