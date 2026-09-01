package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * Sets an HTTP header for each entry of the annotated {@code Map<String, ?>}
 * parameter, for a set of headers whose names aren't known until runtime -
 * e.g. caller-supplied headers in a multi-tenant app. Use {@link HeaderParam}
 * instead for a header whose name is fixed and known at compile time; the
 * two can be combined on the same method.
 *
 * <p>
 * A {@code null} map, or a {@code null} entry value, is skipped - neither
 * throws. At most one parameter per method may be annotated
 * {@code @HeaderMap}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.HEADER)

public @interface HeaderMap {

}
