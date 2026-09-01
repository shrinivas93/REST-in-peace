package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code Map<String, ?>} parameter of a {@link Multipart @Multipart}
 * method adding one part per entry, for a set of field names not known
 * until runtime - the {@code @Part} counterpart to {@link QueryMap @QueryMap}
 * and {@link HeaderMap @HeaderMap}. Each entry's value is handled the same
 * way a {@code @Part} value would be: {@code String} as a plain form field,
 * {@code java.io.File}/{@code byte[]}/{@code java.io.InputStream} as a file
 * part named after the entry's key - or wrap a {@code File}/{@code byte[]}/
 * {@code InputStream} value in {@link com.shri.restinpeace.multipart.PartValue}
 * to send it under a different file name. A {@code null} map or a
 * {@code null} entry value is skipped, not an error. Combines with fixed
 * {@code @Part} parameters on the same method; only valid alongside
 * {@code @Multipart}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface PartMap {

}
