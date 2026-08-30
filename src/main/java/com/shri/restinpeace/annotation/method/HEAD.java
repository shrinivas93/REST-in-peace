package com.shri.restinpeace.annotation.method;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.constant.RIPConstant;

/**
 * Marks a method as issuing an HTTP HEAD request to the given URL template.
 * Does not support a {@code @Body} parameter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@HTTPMethodMarker(HTTPMethod.HEAD)

public @interface HEAD {
	/**
	 * The URL template to call, e.g. {@code "https://api.example.com/items/{id}"}.
	 * A {@code {placeholder}} is substituted by a matching
	 * {@link com.shri.restinpeace.annotation.request.PathParam @PathParam}.
	 *
	 * @return the URL template
	 */
	String value() default RIPConstant.DEFAULT;
}
