package com.shri.restinpeace.annotation.marker;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a REST client whose methods declare HTTP calls via
 * {@code @GET}/{@code @POST}/etc. Required on every interface passed to
 * {@link com.shri.restinpeace.RIP#getClient(Class)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestClient {

}
