package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;

/**
 * {@code @Retry}/{@code @Timeout} declared directly on a {@code @RestClient}
 * interface, as a default every method without its own annotation falls
 * back to - mirroring {@code @BaseUrl}'s own interface-level-default shape.
 * Hits the same shared {@code /flaky/{id}}/{@code /slow/{id}} endpoints
 * {@code AbstractRipIntegrationTest}'s dispatcher already serves for the
 * method-level {@code @Retry}/{@code @Timeout} tests.
 */
class RipInterfaceLevelDefaultsIntegrationTest extends AbstractRipIntegrationTest {

	@RestClient
	@Retry(times = 3, delayMillis = 5, retryOnStatus = { 503 })
	public interface ApiWithInterfaceLevelRetry {
		@GET("http://localhost:{port}/flaky/{id}")
		String getFlaky(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/always-503/{id}")
		@Retry(times = 1)
		String getAlwaysFailingWithItsOwnRetry(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	@Timeout(readMillis = 50)
	public interface ApiWithInterfaceLevelTimeout {
		@GET("http://localhost:{port}/slow/{id}")
		String getSlow(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/slow/{id}")
		@Timeout(readMillis = 5_000)
		String getSlowWithItsOwnLongerTimeout(@PathParam("port") int port, @PathParam("id") String id);
	}

	@Test
	void interfaceLevelRetry_appliesToAMethodWithNoRetryOfItsOwn() {
		ApiWithInterfaceLevelRetry api = RIP.getClient(ApiWithInterfaceLevelRetry.class);

		String result = api.getFlaky(port, "iface-retry");

		assertEquals("ok", result);
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

	@Test
	void methodLevelRetry_winsOverTheInterfacesDefault() {
		ApiWithInterfaceLevelRetry api = RIP.getClient(ApiWithInterfaceLevelRetry.class);

		// @Retry(times = 1) on the method itself must win over the interface's
		// times = 3 default - if the interface wrongly took over, this would keep
		// retrying instead of giving up (and throwing) after the first attempt.
		assertThrows(RuntimeException.class, () -> api.getAlwaysFailingWithItsOwnRetry(port, "iface-retry"));
		assertEquals(1, ALWAYS_FAILING_ATTEMPTS.get());
	}

	@Test
	void interfaceLevelTimeout_appliesToAMethodWithNoTimeoutOfItsOwn() {
		ApiWithInterfaceLevelTimeout api = RIP.getClient(ApiWithInterfaceLevelTimeout.class);

		assertThrows(RuntimeException.class, () -> api.getSlow(port, "iface-timeout"));
	}

	@Test
	void methodLevelTimeout_winsOverTheInterfacesDefault() {
		ApiWithInterfaceLevelTimeout api = RIP.getClient(ApiWithInterfaceLevelTimeout.class);

		String result = api.getSlowWithItsOwnLongerTimeout(port, "iface-timeout");

		assertEquals("ok", result);
	}

}
