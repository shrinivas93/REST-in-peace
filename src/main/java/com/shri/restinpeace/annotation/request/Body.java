package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * Sends the annotated parameter as the request body. Valid only on
 * {@code @POST}, {@code @PUT}, {@code @PATCH}, and {@code @DELETE} methods -
 * using it on {@code @GET}, {@code @HEAD}, or {@code @OPTIONS} fails
 * validation. A {@code String} value is sent as-is; any other object is
 * JSON-serialized automatically. At most one parameter per method may be
 * annotated {@code @Body}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.BODY)

public @interface Body {
}
