package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets one or more fixed HTTP headers on every call of the annotated method,
 * each given as a {@code "Name: Value"} string - for a header whose value is
 * always the same (e.g. {@code Accept}, {@code Cache-Control}), not derived
 * from a call argument. Use {@link HeaderParam}/{@link HeaderMap} instead for
 * a header whose value varies per call; the two can be combined on the same
 * method, with {@link HeaderParam}/{@link HeaderMap} overriding a
 * {@code @Headers} entry of the same name, since the per-call value is more
 * specific.
 *
 * <p>
 * Each entry is split on its first {@code ':'}, with whitespace around the
 * name and value trimmed - {@code "Name:Value"}, {@code "Name: Value"},
 * {@code "Name : Value"}, {@code "Name :Value"}, and
 * {@code "Name    :     Value"} are all equivalent. An entry with no
 * {@code ':'}, or an empty name, fails validation.
 *
 * <pre>
 * &#64;Headers({ "Cache-Control: no-cache", "X-Api-Version: 2" })
 * &#64;GET("/users")
 * List&lt;User&gt; listUsers();
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Headers {
	/**
	 * The fixed headers to set, each as a {@code "Name: Value"} string.
	 *
	 * @return the header entries
	 */
	String[] value();
}
