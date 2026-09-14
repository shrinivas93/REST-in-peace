package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;
import com.shri.restinpeace.interceptor.ShortCircuitResponse;

/**
 * {@link RequestInterceptor#shortCircuit} - skipping the network call
 * entirely in favor of a synthetic response, across every dispatch path
 * (sync/async, plain/{@code byte[]}/{@code RipResponse}) via the real
 * {@code LocalApi} fixture (see {@link AbstractRipIntegrationTest}), so
 * {@link AbstractRipIntegrationTest#LAST_REQUEST} being {@code null} after a
 * call is direct proof the network was never touched.
 */
class RipShortCircuitIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void shortCircuit_skipsTheNetworkCall_forPlainStringReturn() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.ok("short-circuited");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "abc", 7, "custom-value");

		assertEquals("short-circuited", result);
		assertNull(LAST_REQUEST.get(), "the network was never touched");
	}

	@Test
	void shortCircuit_returningNull_letsTheCallProceedNormally() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return null;
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "abc", 7, "custom-value");

		assertEquals("ok", result);
		assertTrue(LAST_REQUEST.get() != null, "the network was hit normally");
	}

	@Test
	void shortCircuit_nonSuccessStatus_throwsRestInPeaceHttpException() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.status(500, "synthetic failure");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.get(port, "abc", 7, "custom-value"));

		assertEquals(500, exception.getStatus());
		assertEquals("synthetic failure", exception.getRawBody());
		assertNull(LAST_REQUEST.get());
	}

	@Test
	void shortCircuit_appliesToAsyncCalls() throws InterruptedException, ExecutionException, TimeoutException {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.ok("short-circuited-async");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getAsync(port, "abc").get(5, TimeUnit.SECONDS);

		assertEquals("short-circuited-async", result);
		assertNull(LAST_REQUEST.get());
	}

	@Test
	void shortCircuit_appliesToByteArrayReturn() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.ok("synthetic-bytes");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		byte[] result = api.downloadBytes(port, "abc");

		assertArrayEquals("synthetic-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), result);
		assertNull(LAST_REQUEST.get());
	}

	@Test
	void shortCircuit_appliesToRipResponseReturn() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.status(201, "created").header("X-Synthetic", "true");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<String> response = api.getWithResponseString(port, "abc");

		assertEquals(201, response.getStatus());
		assertEquals("created", response.getBody());
		assertEquals("true", response.getHeader("X-Synthetic"));
		assertNull(LAST_REQUEST.get());
	}

	@Test
	void shortCircuit_everyInterceptorsBeforeRequestStillRuns_evenAfterAnEarlierOneShortCircuits() {
		List<String> order = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				order.add("first-before");
			}

			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.ok("won");
			}
		});
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				order.add("second-before");
			}

			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				order.add("second-shortcircuit-never-wins");
				return ShortCircuitResponse.ok("never used - first wins");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "abc", 7, "custom-value");

		assertEquals("won", result);
		assertTrue(order.contains("first-before"));
		assertTrue(order.contains("second-before"));
	}

	@Test
	void shortCircuit_afterResponseStillNotifiedWithTheSyntheticResponse() {
		List<Integer> statuses = new ArrayList<>();
		List<Object> bodies = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.ok("observed-by-after-response");
			}
		});
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				statuses.add(status);
				bodies.add(body);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(java.util.Collections.singletonList(200), statuses);
		assertEquals(java.util.Collections.singletonList("observed-by-after-response"), bodies);
	}

}
