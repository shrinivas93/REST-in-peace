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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.BaseUrl;
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
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.interceptor.CorrelationIdInterceptor;
import com.shri.restinpeace.interceptor.HeaderInterceptor;
import com.shri.restinpeace.interceptor.LoggingInterceptor;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;

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

		@GET("http://localhost:{port}/items/{id}")
		CompletableFuture<String> getAsync(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/payload/{id}")
		CompletableFuture<Payload> getPayloadAsync(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/flaky/{id}")
		@Retry(times = 3, delayMillis = 5, retryOnStatus = { 503 })
		String getFlaky(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/flaky/{id}")
		@Retry(times = 3, delayMillis = 5, retryOnStatus = { 503 })
		CompletableFuture<String> getFlakyAsync(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/always-503/{id}")
		@Retry(times = 3, delayMillis = 5, retryOnStatus = { 503 })
		String getAlwaysFailingWithRetry(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/always-503/{id}")
		String getAlwaysFailingWithoutRetry(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:1/unreachable")
		@Retry(times = 3, delayMillis = 5)
		String getUnreachableWithRetry();
	}

	@RestClient
	private interface BadAsyncApi {
		@GET("http://localhost:1/x")
		CompletableFuture getRawFuture();
	}

	@RestClient
	@BaseUrl("http://localhost:{port}")
	private interface LocalApiWithBaseUrl {
		@GET("/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id);

		@GET("items/{id}")
		String getNoLeadingSlash(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://localhost:{port}/")
	private interface LocalApiWithTrailingSlashBaseUrl {
		@GET("/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://localhost:1")
	private interface AbsoluteUrlOverridesBaseUrlApi {
		@GET("http://localhost:{port}/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	private interface RuntimeBaseUrlApi {
		@GET("/items/{id}")
		String get(@PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://localhost:1")
	private interface RuntimeBaseUrlOverridesAnnotationApi {
		@GET("/items/{id}")
		String get(@PathParam("id") String id);
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
	private static final AtomicInteger FLAKY_ATTEMPTS = new AtomicInteger();
	private static final AtomicInteger ALWAYS_FAILING_ATTEMPTS = new AtomicInteger();

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
		FLAKY_ATTEMPTS.set(0);
		ALWAYS_FAILING_ATTEMPTS.set(0);
		RIP.clearInterceptors();
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
		} else if (exchange.getRequestURI().getPath().startsWith("/flaky/")) {
			boolean stillFailing = FLAKY_ATTEMPTS.getAndIncrement() < 2;
			byte[] response = (stillFailing ? "" : "ok").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(stillFailing ? 503 : 200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		} else if (exchange.getRequestURI().getPath().startsWith("/always-503/")) {
			ALWAYS_FAILING_ATTEMPTS.incrementAndGet();
			exchange.sendResponseHeaders(503, 0);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(new byte[0]);
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

	@Test
	void interceptor_beforeRequest_addsHeaderThatGetsSent() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				context.addHeader("X-From-Interceptor", "injected");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals("injected", LAST_REQUEST.get().header("X-From-Interceptor"));
	}

	@Test
	void interceptor_afterResponse_calledWithStatusAndStringBody() {
		AtomicReference<Integer> capturedStatus = new AtomicReference<>();
		AtomicReference<Object> capturedBody = new AtomicReference<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				capturedStatus.set(status);
				capturedBody.set(body);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(200, capturedStatus.get());
		assertEquals("ok", capturedBody.get());
	}

	@Test
	void interceptor_afterResponse_calledWithDeserializedPojo() {
		AtomicReference<Object> capturedBody = new AtomicReference<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				capturedBody.set(body);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getPayload(port, "abc");

		assertEquals(Payload.class, capturedBody.get().getClass());
		assertEquals("Shrinivas", ((Payload) capturedBody.get()).name);
	}

	@Test
	void interceptor_afterResponse_calledForAsyncCalls()
			throws InterruptedException, ExecutionException, TimeoutException {
		AtomicReference<Integer> capturedStatus = new AtomicReference<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				capturedStatus.set(status);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getAsync(port, "async").get(5, TimeUnit.SECONDS);

		assertEquals(200, capturedStatus.get());
	}

	@Test
	void interceptor_beforeRequestThrows_abortsRequestBeforeSending() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				throw new IllegalStateException("blocked by interceptor");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(IllegalStateException.class, () -> api.get(port, "abc", 7, "custom-value"));
		assertNull(LAST_REQUEST.get());
	}

	@Test
	void interceptors_beforeRequestRunsFifo_afterResponseRunsLifo() {
		List<String> order = new ArrayList<>();
		RIP.addInterceptor(namedInterceptor("first", order));
		RIP.addInterceptor(namedInterceptor("second", order));
		RIP.addInterceptor(namedInterceptor("third", order));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(
				Arrays.asList("first-before", "second-before", "third-before", "third-after", "second-after",
						"first-after"),
				order);
	}

	private static RequestInterceptor namedInterceptor(String name, List<String> order) {
		return new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				order.add(name + "-before");
			}

			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				order.add(name + "-after");
			}
		};
	}

	@Test
	void headerInterceptor_withStaticValue_addsHeaderToEveryRequest() {
		RIP.addInterceptor(new HeaderInterceptor("Authorization", "Bearer static-token"));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals("Bearer static-token", LAST_REQUEST.get().header("Authorization"));
	}

	@Test
	void headerInterceptor_withSupplier_reevaluatesValuePerCall() {
		AtomicReference<String> token = new AtomicReference<>("token-1");
		RIP.addInterceptor(new HeaderInterceptor("Authorization", token::get));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-1", LAST_REQUEST.get().header("Authorization"));

		token.set("token-2");
		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-2", LAST_REQUEST.get().header("Authorization"));
	}

	@Test
	void headerInterceptor_ofStaticMap_addsAllHeadersToEveryRequest() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("X-Api-Key", "key-1");
		headers.put("X-Client-Version", "1.2.3");
		headers.put("X-Tenant-Id", "tenant-42");
		RIP.addInterceptor(HeaderInterceptor.of(headers));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("key-1", request.header("X-Api-Key"));
		assertEquals("1.2.3", request.header("X-Client-Version"));
		assertEquals("tenant-42", request.header("X-Tenant-Id"));
	}

	@Test
	void headerInterceptor_withSupplierMap_reevaluatesEachValuePerCall() {
		AtomicReference<String> token = new AtomicReference<>("token-1");
		Map<String, Supplier<String>> suppliers = new LinkedHashMap<>();
		suppliers.put("Authorization", token::get);
		suppliers.put("X-Static", () -> "fixed");
		RIP.addInterceptor(new HeaderInterceptor(suppliers));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-1", LAST_REQUEST.get().header("Authorization"));
		assertEquals("fixed", LAST_REQUEST.get().header("X-Static"));

		token.set("token-2");
		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-2", LAST_REQUEST.get().header("Authorization"));
	}

	@Test
	void loggingInterceptor_logsRequestAndResponseLinesWithDuration() {
		List<String> lines = new ArrayList<>();
		RIP.addInterceptor(new LoggingInterceptor(lines::add));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(2, lines.size());
		assertTrue(lines.get(0).startsWith("--> GET "));
		assertTrue(lines.get(1).startsWith("<-- GET "));
		assertTrue(lines.get(1).contains(" 200 "));
		assertTrue(lines.get(1).endsWith("ms)"));
	}

	@Test
	void correlationIdInterceptor_default_addsUniqueIdPerRequestUnderDefaultHeader() {
		RIP.addInterceptor(new CorrelationIdInterceptor());
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		String firstId = LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME);

		api.get(port, "abc", 7, "custom-value");
		String secondId = LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME);

		assertTrue(firstId != null && !firstId.isEmpty());
		assertTrue(secondId != null && !secondId.isEmpty());
		assertFalse(firstId.equals(secondId));
	}

	@Test
	void correlationIdInterceptor_customHeaderName_usesConfiguredHeader() {
		RIP.addInterceptor(new CorrelationIdInterceptor("X-Trace-Id"));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertNull(LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME));
		String traceId = LAST_REQUEST.get().header("X-Trace-Id");
		assertTrue(traceId != null && !traceId.isEmpty());
	}

	@Test
	void correlationIdInterceptor_customGenerator_usesProvidedIds() {
		AtomicReference<Integer> counter = new AtomicReference<>(0);
		RIP.addInterceptor(new CorrelationIdInterceptor(CorrelationIdInterceptor.DEFAULT_HEADER_NAME,
				() -> "id-" + counter.updateAndGet(n -> n + 1)));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		assertEquals("id-1", LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME));

		api.get(port, "abc", 7, "custom-value");
		assertEquals("id-2", LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME));
	}

	@Test
	void correlationIdInterceptor_storesIdOnContext_forOtherInterceptorsToRead() {
		AtomicReference<Object> idSeenByOtherInterceptor = new AtomicReference<>();
		RIP.addInterceptor(new CorrelationIdInterceptor());
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				idSeenByOtherInterceptor.set(context.getAttribute(CorrelationIdInterceptor.ID_ATTRIBUTE));
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		String headerId = LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME);
		assertEquals(headerId, idSeenByOtherInterceptor.get());
	}

	@Test
	void retry_withTransientFailure_succeedsAfterRetrying() {
		List<Integer> statuses = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				statuses.add(status);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getFlaky(port, "x");

		assertEquals("ok", result);
		assertEquals(Arrays.asList(503, 503, 200), statuses);
	}

	@Test
	void retry_withPermanentFailure_stopsAfterConfiguredAttemptsAndReturnsLastResponse() {
		List<Integer> statuses = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				statuses.add(status);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getAlwaysFailingWithRetry(port, "x");

		assertEquals(Arrays.asList(503, 503, 503), statuses);
		assertEquals(3, ALWAYS_FAILING_ATTEMPTS.get());
	}

	@Test
	void withoutRetryAnnotation_doesNotRetryOnFailure() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getAlwaysFailingWithoutRetry(port, "x");

		assertEquals(1, ALWAYS_FAILING_ATTEMPTS.get());
	}

	@Test
	void retry_onTransportFailure_retriesAndEventuallyThrows() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RuntimeException.class, api::getUnreachableWithRetry);
	}

	@Test
	void retry_withCompletableFuture_succeedsAfterRetryingWithoutBlocking()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<String> future = api.getFlakyAsync(port, "y");

		assertEquals("ok", future.get(5, TimeUnit.SECONDS));
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

	@Test
	void baseUrl_withRelativeMethodUrl_resolvesAgainstBase() {
		LocalApiWithBaseUrl api = RIP.getClient(LocalApiWithBaseUrl.class);

		String result = api.get(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void baseUrl_withRelativeMethodUrlMissingLeadingSlash_resolvesAgainstBase() {
		LocalApiWithBaseUrl api = RIP.getClient(LocalApiWithBaseUrl.class);

		String result = api.getNoLeadingSlash(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void baseUrl_withTrailingSlashAndLeadingSlashPath_doesNotDoubleSlash() {
		LocalApiWithTrailingSlashBaseUrl api = RIP.getClient(LocalApiWithTrailingSlashBaseUrl.class);

		String result = api.get(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void baseUrl_withAbsoluteMethodUrl_ignoresBaseUrl() {
		AbsoluteUrlOverridesBaseUrlApi api = RIP.getClient(AbsoluteUrlOverridesBaseUrlApi.class);

		String result = api.get(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void getClient_withRuntimeBaseUrl_resolvesRelativeUrlWithNoBaseUrlAnnotation() {
		RuntimeBaseUrlApi api = RIP.getClient(RuntimeBaseUrlApi.class, "http://localhost:" + port);

		String result = api.get("abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void getClient_withRuntimeBaseUrl_takesPriorityOverInterfaceBaseUrlAnnotation() {
		RuntimeBaseUrlOverridesAnnotationApi api = RIP.getClient(RuntimeBaseUrlOverridesAnnotationApi.class,
				"http://localhost:" + port);

		String result = api.get("abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

}
