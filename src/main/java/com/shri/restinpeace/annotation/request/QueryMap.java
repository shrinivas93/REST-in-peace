package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * Appends a query string parameter for each entry of the annotated
 * {@code Map<String, ?>} parameter, for a set of query params whose names
 * aren't known until runtime - e.g. a search endpoint's open-ended filter
 * set. Use {@link QueryParam} instead for a param whose name is fixed and
 * known at compile time; the two can be combined on the same method.
 *
 * <p>
 * A {@code null} map, or a {@code null} entry value, is skipped - neither
 * throws. At most one parameter per method may be annotated
 * {@code @QueryMap}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.QUERY)

public @interface QueryMap {

}
