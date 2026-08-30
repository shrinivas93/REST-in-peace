package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.exception.RestInPeaceException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class RipIntegrationTest {

	private static final class CapturedRequest {
		final String method;
		final String path;
		final String query;
		final Map<String, List<String>> headers;
		final String body;

		CapturedRequest(String method, String path, String query, Map<String, List<String>> headers, String body) {
			this.method = method;
			this.path = path;
			this.query = query;
			this.headers = headers;
			this.body = body;
		}

		String header(String name) {
			List<String> values = headers.get(name);
			return values == null || values.isEmpty() ? null : values.get(0);
		}
	}

	@RestClient
	private interface LocalApi {
		@GET("http://localhost:{port}/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id,
				@QueryParam(value = "q", required = true) Integer q, @HeaderParam("X-Custom") String custom);

		@POST("http://localhost:{port}/items/{id}")
		String post(@PathParam("port") int port, @PathParam("id") String id, @Body String body);

		@PUT("http://localhost:{port}/items/{id}")
		String put(@PathParam("port") int port, @PathParam("id") String id, @Body Payload body);

		@PATCH("http://localhost:{port}/items/{id}")
		String patch(@PathParam("port") int port, @PathParam("id") String id, @Body String body);

		@DELETE("http://localhost:{port}/items/{id}")
		String delete(@PathParam("port") int port, @PathParam("id") String id, @Body String body);

		@HEAD("http://localhost:{port}/items/{id}")
		String head(@PathParam("port") int port, @PathParam("id") String id);

		@OPTIONS("http://localhost:{port}/items/{id}")
		String options(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/items/{id}")
		String getWithOptionalQuery(@PathParam("port") int port, @PathParam("id") String id,
				@QueryParam(value = "q", defaultValue = "42") Integer q);

		@GET("http://localhost:{port}/items/{id}")
		String getWithMissingRequiredQuery(@PathParam("port") int port, @PathParam("id") String id,
				@QueryParam(value = "q", required = true) Integer q);

		@GET("http://localhost:{port}/items/{id}")
		String getWithMissingRequiredHeader(@PathParam("port") int port, @PathParam("id") String id,
				@HeaderParam(value = "X-Required", required = true) String header);

		@GET("http://localhost:{port}/items/{id}")
		String getWithNullPathParam(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/payload/{id}")
		Payload getPayload(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/items/{id}")
		void ping(@PathParam("port") int port, @PathParam("id") String id);
	}

	public static final class Payload {
		public String name;
		public int age;

		public Payload(String name, int age) {
			this.name = name;
			this.age = age;
		}
	}

	private static HttpServer server;
	private static int port;
	private static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", RipIntegrationTest::handle);
		server.setExecutor(null);
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@BeforeEach
	void resetCapturedRequest() {
		LAST_REQUEST.set(null);
	}

	private static void handle(HttpExchange exchange) throws IOException {
		String body = readBody(exchange.getRequestBody());
		LAST_REQUEST.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(), body));

		if ("HEAD".equals(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(200, -1);
		} else if (exchange.getRequestURI().getPath().startsWith("/payload/")) {
			byte[] response = "{\"name\":\"Shrinivas\",\"age\":1993}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		} else {
			byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		}
		exchange.close();
	}

	private static String readBody(InputStream inputStream) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[1024];
		int read;
		while ((read = inputStream.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

	@Test
	void get_withPathQueryAndHeader_sendsCorrectRequest() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "abc", 7, "custom-value");

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("GET", request.method);
		assertEquals("/items/abc", request.path);
		assertEquals("q=7", request.query);
		assertEquals("custom-value", request.header("X-Custom"));
	}

	@Test
	void post_withStringBody_sendsBodyRaw() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.post(port, "xyz", "raw-body-content");

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("POST", request.method);
		assertEquals("/items/xyz", request.path);
		assertEquals("raw-body-content", request.body);
		assertFalse(request.header("Content-Type").startsWith("application/json"));
	}

	@Test
	void put_withObjectBody_sendsJsonSerializedBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.put(port, "obj", new Payload("Shrinivas", 1993));

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("PUT", request.method);
		assertTrue(request.body.contains("\"name\":\"Shrinivas\""));
		assertTrue(request.body.contains("\"age\":1993"));
		assertTrue(request.header("Content-Type").startsWith("application/json"));
	}

	@Test
	void patch_withBody_sendsRequest() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.patch(port, "p", "patch-body");

		assertEquals("PATCH", LAST_REQUEST.get().method);
		assertEquals("patch-body", LAST_REQUEST.get().body);
	}

	@Test
	void delete_withBody_sendsRequest() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.delete(port, "d", "delete-body");

		assertEquals("DELETE", LAST_REQUEST.get().method);
		assertEquals("delete-body", LAST_REQUEST.get().body);
	}

	@Test
	void head_sendsRequestAndReturnsEmptyBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.head(port, "h");

		assertEquals("HEAD", LAST_REQUEST.get().method);
		assertTrue(result == null || result.isEmpty());
	}

	@Test
	void options_sendsRequestAndReturnsBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.options(port, "o");

		assertEquals("OPTIONS", LAST_REQUEST.get().method);
		assertEquals("ok", result);
	}

	@Test
	void optionalQueryParam_missingArg_usesDefaultValue() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithOptionalQuery(port, "d", null);

		assertEquals("q=42", LAST_REQUEST.get().query);
	}

	@Test
	void requiredQueryParam_missingArg_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.getWithMissingRequiredQuery(port, "d", null));
		assertTrue(exception.getMessage().contains("Missing required value"));
	}

	@Test
	void requiredHeaderParam_missingArg_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.getWithMissingRequiredHeader(port, "d", null));
		assertTrue(exception.getMessage().contains("Missing required value"));
	}

	@Test
	void pathParam_nullArg_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.getWithNullPathParam(port, null));
		assertTrue(exception.getMessage().contains("Missing value for path param"));
	}

	@Test
	void get_withPojoReturnType_deserializesJsonResponse() {
		LocalApi api = RIP.getClient(LocalApi.class);

		Payload payload = api.getPayload(port, "abc");

		assertEquals("Shrinivas", payload.name);
		assertEquals(1993, payload.age);
	}

	@Test
	void get_withVoidReturnType_doesNotThrow() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.ping(port, "abc");

		assertEquals("GET", LAST_REQUEST.get().method);
	}

}
