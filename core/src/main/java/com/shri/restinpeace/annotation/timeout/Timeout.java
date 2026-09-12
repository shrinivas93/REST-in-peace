package com.shri.restinpeace.annotation.timeout;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the connect/read timeout for this method's calls only, taking
 * priority over both the shared client's configured default and a
 * {@link com.shri.restinpeace.RipClientConfig}'s timeout - for one endpoint
 * whose expected latency doesn't match the rest of the client (a
 * report-export endpoint that's expected to be slow, a health check that
 * should fail fast).
 *
 * <pre>
 * {@literal @}GET("/reports/export")
 * {@literal @}Timeout(readMillis = 120_000)
 * String exportReport();
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
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
