package com.shri.restinpeace.restclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.RIP;

import com.sun.net.httpserver.HttpServer;

/**
 * Verifies {@code RIP.getClient(GeneratedApi.class)} actually returns the
 * compile-time-generated {@code GeneratedApi_RipImpl} - not the reflective
 * {@code java.lang.reflect.Proxy} every other test in this module exercises
 * - and that a call through it produces the same request a reflective call
 * would. See {@code docs/design/compile-time-proxy-generation.md}.
 */
class GeneratedApiTest {

	private static HttpServer server;
	private static int port;
	private static final AtomicInteger FLAKY_ATTEMPTS = new AtomicInteger();

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> {
			String response = "path=" + exchange.getRequestURI().getPath() + ";query="
					+ exchange.getRequestURI().getRawQuery();
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] bytes = "slow".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.createContext("/flaky", exchange -> {
			boolean stillFailing = FLAKY_ATTEMPTS.getAndIncrement() < 2;
			byte[] bytes = (stillFailing ? "" : "ok").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(stillFailing ? 503 : 200, bytes.length);
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

	@BeforeEach
	void resetFlakyAttempts() {
		FLAKY_ATTEMPTS.set(0);
	}

	@Test
	void getClient_returnsGeneratedImplementation() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		assertTrue(api.getClass().getName().endsWith("_RipImpl"),
				"Expected the compile-time-generated implementation, got " + api.getClass().getName());
	}

	@Test
	void get_withPathAndQueryParams_sendsCorrectRequest() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		String result = api.get(port, "abc", 7);

		assertEquals("path=/items/abc;query=q=7", result);
	}

	@Test
	void getSlowWithShortTimeout_throughGeneratedImpl_throwsOnSlowResponse() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		assertTrue(api.getClass().getName().endsWith("_RipImpl"));
		assertThrows(RuntimeException.class, () -> api.getSlowWithShortTimeout(port));
	}

	@Test
	void getFlaky_throughGeneratedImpl_succeedsAfterRetrying() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		String result = api.getFlaky(port);

		assertEquals("ok", result);
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

	@Test
	void getClient_withHeadersMethod_fallsBackToReflectiveProxy() throws ClassNotFoundException {
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("com.shri.restinpeace.restclient.GeneratedApiWithHeaders_RipImpl"));

		GeneratedApiWithHeaders api = RIP.getClient(GeneratedApiWithHeaders.class);

		assertTrue(api.getClass().getName().contains("Proxy"),
				"Expected the reflective proxy fallback, got " + api.getClass().getName());
		assertEquals("path=/items/abc;query=null", api.get(port, "abc"));
	}

}
