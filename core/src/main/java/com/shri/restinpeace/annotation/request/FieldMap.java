package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code Map<String, ?>} parameter of a {@link FormUrlEncoded @FormUrlEncoded}
 * method adding one form field per entry, for a set of field names not known
 * until runtime - the {@code @FieldMap} counterpart to {@link QueryMap @QueryMap}
 * and {@link PartMap @PartMap}. Each entry's value is handled the same way a
 * {@code @Field} value would be: converted with {@code String.valueOf(...)}
 * and percent-encoded, or sent once per element for a {@code Collection}
 * value. A {@code null} map or a {@code null} entry value is skipped, not an
 * error. Combines with fixed {@code @Field} parameters on the same method;
 * only valid alongside {@code @FormUrlEncoded}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface FieldMap {

}
