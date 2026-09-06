package com.shri.restinpeace.annotation.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a single {@code GET} method out of response caching, even when its
 * client has a {@link com.shri.restinpeace.cache.Cache} configured (via
 * {@link com.shri.restinpeace.RipClientConfig.Builder#cache} or
 * {@link com.shri.restinpeace.RIP#setCache}) - every call always hits the
 * network, regardless of the response's own {@code Cache-Control}/
 * {@code ETag} headers. For an endpoint that's cacheable in principle but
 * needs to be observed live in one particular call site (e.g. a live price
 * feed on an otherwise-cacheable catalog client). Meaningless (but harmless)
 * on a non-{@code GET} method, or when no cache is configured at all, since
 * neither is ever cached in the first place.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoCache {

}
