package com.shri.restinpeace.annotation.timeout;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the connect/read timeout, taking priority over both the shared
 * client's configured default and a {@link com.shri.restinpeace.RipClientConfig}'s
 * timeout - for one endpoint whose expected latency doesn't match the rest
 * of the client (a report-export endpoint that's expected to be slow, a
 * health check that should fail fast).
 *
 * <pre>
 * {@literal @}GET("/reports/export")
 * {@literal @}Timeout(readMillis = 120_000)
 * String exportReport();
 * </pre>
 *
 * <p>
 * Also valid directly on a {@code @RestClient} interface, as a default for
 * every method that doesn't declare its own - mirroring {@code @BaseUrl}'s
 * own interface-level-default/per-method-override shape, for an interface
 * that wants one uniform timeout instead of repeating the same annotation
 * on every method:
 *
 * <pre>
 * {@literal @}RestClient
 * {@literal @}Timeout(readMillis = 5_000)
 * interface FlakyDownstreamApi {
 *     {@literal @}GET("/status")
 *     String getStatus();          // uses the interface's 5s read timeout
 *
 *     {@literal @}GET("/reports/export")
 *     {@literal @}Timeout(readMillis = 120_000)
 *     String exportReport();       // overrides it with its own
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface Timeout {

	/**
	 * The connect timeout in milliseconds, or {@code -1} (the default) to
	 * leave the connect timeout at whatever it would otherwise be.
	 *
	 * @return the connect timeout in milliseconds, or {@code -1} if unset
	 */
	int connectMillis() default -1;

	/**
	 * The read (socket) timeout in milliseconds, or {@code -1} (the
	 * default) to leave the read timeout at whatever it would otherwise be.
	 *
	 * @return the read timeout in milliseconds, or {@code -1} if unset
	 */
	int readMillis() default -1;

}
