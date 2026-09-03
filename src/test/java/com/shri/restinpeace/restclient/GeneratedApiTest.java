package com.shri.restinpeace.restclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
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

}
