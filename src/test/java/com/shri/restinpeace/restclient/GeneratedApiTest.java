package com.shri.restinpeace.restclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
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
	private static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();

	private static final class CapturedRequest {
		final String method;
		final String path;
		final String query;
		final Headers headers;
		final String body;

		CapturedRequest(String method, String path, String query, Headers headers, String body) {
			this.method = method;
			this.path = path;
			this.query = query;
			this.headers = headers;
			this.body = body;
		}
	}

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> {
			String response = "path=" + exchange.getRequestURI().getPath() + ";query="
					+ exchange.getRequestURI().getRawQuery();
			respond(exchange, 200, response);
		});
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			respond(exchange, 200, "slow");
		});
		server.createContext("/flaky", exchange -> {
			boolean stillFailing = FLAKY_ATTEMPTS.getAndIncrement() < 2;
			respond(exchange, stillFailing ? 503 : 200, stillFailing ? "" : "ok");
		});
		server.createContext("/echo", exchange -> {
			captureRequest(exchange);
			respond(exchange, 200, "ok");
		});
		server.createContext("/error", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			respond(exchange, 422, "{\"code\":\"INVALID\",\"message\":\"nope\"}");
		});
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@BeforeEach
	void resetState() {
		FLAKY_ATTEMPTS.set(0);
		LAST_REQUEST.set(null);
	}

	private static void captureRequest(HttpExchange exchange) throws IOException {
		String body = readBody(exchange.getRequestBody());
		LAST_REQUEST.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(), body));
	}

	private static String readBody(InputStream inputStream) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[1024];
		int read;
		while ((read = inputStream.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toString("UTF-8");
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
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
	void getClient_withHeadersMethod_generatesAndAppliesFixedHeader() {
		GeneratedApiWithHeaders api = RIP.getClient(GeneratedApiWithHeaders.class);

		assertTrue(api.getClass().getName().endsWith("_RipImpl"),
				"Expected the compile-time-generated implementation, got " + api.getClass().getName());
		assertEquals("path=/items/abc;query=null", api.get(port, "abc"));
	}

	@Test
	void getClient_withMultipartMethod_fallsBackToReflectiveProxy() {
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("com.shri.restinpeace.restclient.GeneratedApiWithMultipart_RipImpl"));

		GeneratedApiWithMultipart api = RIP.getClient(GeneratedApiWithMultipart.class);

		assertTrue(api.getClass().getName().contains("Proxy"),
				"Expected the reflective proxy fallback, got " + api.getClass().getName());
	}

	@Test
	void echo_appliesFixedAndDefaultedHeaderParamsAndRequiredQueryParamAndMaps() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);
		Map<String, String> queryMap = new HashMap<>();
		queryMap.put("extra", "1");
		Map<String, String> headerMap = new HashMap<>();
		headerMap.put("X-Dynamic", "dyn");

		api.echo(port, "fixed-value", null, "required-value", queryMap, headerMap);

		CapturedRequest captured = LAST_REQUEST.get();
		assertEquals("fixed-value", header(captured, "X-Fixed"));
		assertEquals("header-default", header(captured, "X-Default"));
		assertEquals("dyn", header(captured, "X-Dynamic"));
		assertTrue(captured.query.contains("q=required-value"));
		assertTrue(captured.query.contains("extra=1"));
	}

	@Test
	void echo_missingRequiredQueryParam_throws() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		assertThrows(RuntimeException.class,
				() -> api.echo(port, "fixed-value", null, null, new HashMap<>(), new HashMap<>()));
	}

	@Test
	void echoBody_sendsBodyVerbatim() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		api.echoBody(port, "raw-body-text");

		assertEquals("raw-body-text", LAST_REQUEST.get().body);
	}

	@Test
	void getByUrl_usesUrlParamVerbatim() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		String result = api.getByUrl("http://localhost:" + port + "/items/xyz");

		assertEquals("path=/items/xyz;query=null", result);
	}

	@Test
	void getError_decodesErrorBodyIntoErrorType() {
		GeneratedApi api = RIP.getClient(GeneratedApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class, () -> api.getError(port));

		assertEquals(422, exception.getStatus());
		ApiError error = (ApiError) exception.getErrorBody();
		assertEquals("INVALID", error.getCode());
		assertEquals("nope", error.getMessage());
	}

	private static String header(CapturedRequest request, String name) {
		List<String> values = request.headers.get(name);
		return values == null ? null : values.get(0);
	}

}
