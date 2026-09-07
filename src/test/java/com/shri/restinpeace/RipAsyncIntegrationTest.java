package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * {@code CompletableFuture} return types, including validation of a raw,
 * unparameterized one, and {@code RIP.useDaemonThreadsForAsync()} - split
 * out of {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipAsyncIntegrationTest extends AbstractRipIntegrationTest {

	@RestClient
	private interface BadAsyncApi {
		@GET("http://localhost:1/x")
		CompletableFuture getRawFuture();
	}

	@Test
	void get_withCompletableFutureOfString_completesAsynchronously()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<String> future = api.getAsync(port, "async");

		assertEquals("ok", future.get(5, TimeUnit.SECONDS));
		assertEquals("GET", LAST_REQUEST.get().method);
	}

	@Test
	void get_withCompletableFutureOfPojo_deserializesAsynchronously()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<Payload> future = api.getPayloadAsync(port, "async");

		Payload payload = future.get(5, TimeUnit.SECONDS);
		assertEquals("Shrinivas", payload.name);
		assertEquals(1993, payload.age);
	}

	@Test
	void rawCompletableFuture_throwsRestInPeaceException() {
		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(BadAsyncApi.class));
		assertTrue(exception.getMessage().contains("failed during validation"));
	}

	@Test
	void useDaemonThreadsForAsync_asyncCallsStillWork() throws InterruptedException, ExecutionException, TimeoutException {
		RIP.useDaemonThreadsForAsync();
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<String> future = api.getAsync(port, "daemon");

		assertEquals("ok", future.get(5, TimeUnit.SECONDS));
	}

}
