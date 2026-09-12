package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * Substitutes a {@code {name}} placeholder in the method's URL template with
 * the annotated parameter's value. Every placeholder in the URL must have a
 * matching {@code @PathParam}, checked at validation time.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.PATH)

public @interface PathParam {
	/**
	 * The placeholder name to substitute, matching {@code {name}} in the URL template.
	 *
	 * @return the placeholder name
	 */
	String value();
}
