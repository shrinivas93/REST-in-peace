package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@code java.io.File} parameter a {@code File}-returning method
 * writes its response body into, instead of buffering the whole body into a
 * {@code byte[]}:
 *
 * <pre>
 * {@literal @}GET("/reports/{id}/pdf")
 * File downloadReport({@literal @}PathParam("id") String id, {@literal @}Destination File target);
 * </pre>
 *
 * Required on exactly one {@code File} parameter of any method that returns
 * {@code File} (or {@code CompletableFuture<File>}), checked at validation
 * time. The returned {@code File} is the same instance passed in.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)

public @interface Destination {
}
