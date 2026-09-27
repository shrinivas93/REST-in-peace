package com.shri.restinpeace.restclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.CallAdapter;
import com.shri.restinpeace.RIP;

import com.sun.net.httpserver.HttpServer;

/**
 * Regression coverage for the bug where a {@link com.shri.restinpeace.CallAdapterFactory}
 * claiming a PLAIN-classified method's (non-{@code Mono<T>}/{@code Flux<T>}-shaped)
 * return type was silently bypassed by compile-time-generated dispatch -
 * {@code finishGeneratedSync}/{@code finishGeneratedAsync} decoded the response body
 * directly with no adapter check at all, unlike the reflective proxy path
 * ({@code RequestExecutor.processRestRequest}), which always checks
 * {@code resolveCallAdapter} first. Generated dispatch for a PLAIN method now checks
 * {@code RequestExecutor.resolveCallAdapter} before its own direct decode, delegating to
 * the same reflective proxy the reflective path already uses whenever an adapter claims
 * the method - see {@code RestClientProcessor#appendMethod}.
 */
class AdapterAwareGeneratedApiTest {

	private static HttpServer server;
	private static int port;

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> {
			String response = "path=" + exchange.getRequestURI().getPath();
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@AfterEach
	void clearAdapters() {
		RIP.clearCallAdapterFactories();
	}

	@Test
	void get_stillReturnsTheCompileTimeGeneratedImplementation() {
		AdapterEligiblePlainApi api = RIP.getClient(AdapterEligiblePlainApi.class);

		assertTrue(api.getClass().getName().endsWith("_RipImpl"),
				"Expected the compile-time-generated implementation, got " + api.getClass().getName());
	}

	@Test
	void get_withNoRegisteredAdapter_decodesDirectlyThroughGeneratedDispatch() {
		AdapterEligiblePlainApi api = RIP.getClient(AdapterEligiblePlainApi.class);

		String result = api.get(port, "abc");

		assertEquals("path=/items/abc", result);
	}

	@Test
	void get_withCallAdapterClaimingThisMethod_routesThroughTheAdapterInsteadOfBypassingIt() {
		RIP.addCallAdapterFactory(method -> "get".equals(method.getName())
				&& method.getDeclaringClass() == AdapterEligiblePlainApi.class ? Optional.of(new StringPrefixingAdapter())
						: Optional.empty());

		AdapterEligiblePlainApi api = RIP.getClient(AdapterEligiblePlainApi.class);

		String result = api.get(port, "abc");

		assertEquals("ADAPTED:path=/items/abc", result);
	}

	/** Tags its output distinctly so a test can prove the adapter actually ran. */
	private static final class StringPrefixingAdapter implements CallAdapter<String> {

		@Override
		public Type responseBodyType() {
			return String.class;
		}

		@Override
		public String adapt(CompletableFuture<Object> delegate) {
			try {
				return "ADAPTED:" + delegate.get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

}
