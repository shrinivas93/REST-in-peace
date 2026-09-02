package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.error.ErrorType;
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
import com.shri.restinpeace.annotation.request.Destination;
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.Headers;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PartMap;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.request.Url;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.download.DownloadProgressListener;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.interceptor.CorrelationIdInterceptor;
import com.shri.restinpeace.interceptor.HeaderInterceptor;
import com.shri.restinpeace.interceptor.LoggingInterceptor;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;
import com.shri.restinpeace.multipart.PartValue;
import com.shri.restinpeace.multipart.UploadProgressListener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import kong.unirest.JsonObjectMapper;
import kong.unirest.ObjectMapper;
import kong.unirest.Unirest;

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
		String getWithQueryMap(@PathParam("port") int port, @PathParam("id") String id,
				@QueryMap Map<String, String> filters);

		@GET("http://localhost:{port}/items/{id}")
		String getWithFixedQueryParamAndQueryMap(@PathParam("port") int port, @PathParam("id") String id,
				@QueryParam("fixed") String fixed, @QueryMap Map<String, String> extra);

		@GET("http://localhost:{port}/items/{id}")
		String getWithHeaderMap(@PathParam("port") int port, @PathParam("id") String id,
				@HeaderMap Map<String, String> headers);

		@GET("http://localhost:{port}/items/{id}")
		@Headers({ "Cache-Control: no-cache", "X-Api-Version : 2" })
		String getWithFixedHeaders(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/items/{id}")
		@Headers({ "X-Custom: from-headers" })
		String getWithFixedHeaderAndOverridingHeaderParam(@PathParam("port") int port, @PathParam("id") String id,
				@HeaderParam("X-Custom") String custom);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipart(@PathParam("port") int port, @PathParam("id") String id,
				@Part("caption") String caption, @Part("file") File file);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithRequiredCaption(@PathParam("port") int port, @PathParam("id") String id,
				@Part(value = "caption", required = true) String caption, @Part("file") File file);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithBytesAndStream(@PathParam("port") int port, @PathParam("id") String id,
				@Part(value = "data", fileName = "data.bin") byte[] data, @Part("stream") InputStream stream);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithRenamedFile(@PathParam("port") int port, @PathParam("id") String id,
				@Part(value = "file", fileName = "renamed.txt") File file);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithPartMap(@PathParam("port") int port, @PathParam("id") String id,
				@PartMap Map<String, Object> parts);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithProgress(@PathParam("port") int port, @PathParam("id") String id,
				@Part("file") File file, UploadProgressListener listener);

		@GET("http://localhost:{port}/items/{id}")
		String getWithMultiValueQuery(@PathParam("port") int port, @PathParam("id") String id,
				@QueryParam("tag") List<String> tags);

		@GET("http://localhost:{port}/items/{id}")
		String getWithMultiValueQueryMap(@PathParam("port") int port, @PathParam("id") String id,
				@QueryMap Map<String, Object> filters);

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

		@GET("http://localhost:{port}/error/{id}")
		String getWithUntypedError(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/error/{id}")
		@ErrorType(ApiError.class)
		String getWithTypedError(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/error/{id}")
		@ErrorType(ApiError.class)
		CompletableFuture<String> getWithTypedErrorAsync(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/always-503/{id}")
		void pingAlwaysFailing(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/payload/{id}")
		RipResponse<Payload> getPayloadWithResponse(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/payload/{id}")
		CompletableFuture<RipResponse<Payload>> getPayloadWithResponseAsync(@PathParam("port") int port,
				@PathParam("id") String id);

		@GET("http://localhost:{port}/items/{id}")
		RipResponse<String> getWithResponseString(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/items/{id}")
		RipResponse<Void> getWithResponseVoid(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/error/{id}")
		RipResponse<String> getErrorWithResponse(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/items/{id}")
		@Retry(times = 3, delayMillis = 5, retryOnStatus = { 503 })
		RipResponse<String> getWithResponseAndRetry(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/slow/{id}")
		String getSlow(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/slow/{id}")
		@Timeout(readMillis = 50)
		String getSlowWithShortTimeout(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/slow/{id}")
		@Timeout(readMillis = 5_000)
		String getSlowWithLongTimeoutOverride(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/binary/{id}")
		byte[] downloadBytes(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/binary/{id}")
		CompletableFuture<byte[]> downloadBytesAsync(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/binary/{id}")
		RipResponse<byte[]> downloadBytesWithResponse(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/binary/{id}")
		byte[] downloadBytesWithProgress(@PathParam("port") int port, @PathParam("id") String id,
				DownloadProgressListener listener);

		@GET("http://localhost:{port}/binary/{id}")
		File downloadToFile(@PathParam("port") int port, @PathParam("id") String id, @Destination File target);

		@GET("http://localhost:{port}/binary/{id}")
		CompletableFuture<File> downloadToFileAsync(@PathParam("port") int port, @PathParam("id") String id,
				@Destination File target);

		@GET("http://localhost:{port}/error/{id}")
		byte[] downloadBytesFromErrorEndpoint(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/error/{id}")
		File downloadToFileFromErrorEndpoint(@PathParam("port") int port, @PathParam("id") String id,
				@Destination File target);

		@GET
		String getWithUrlParam(@Url String url);

		@GET
		String getWithUrlParamAndQueryParam(@Url String url, @QueryParam("q") Integer q);
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

	public static final class ApiError {
		public String code;
		public String message;
	}

	private static final class FixedValueObjectMapper implements ObjectMapper {
		private final Object fixedValue;

		FixedValueObjectMapper(Object fixedValue) {
			this.fixedValue = fixedValue;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T readValue(String value, Class<T> valueType) {
			return (T) fixedValue;
		}

		@Override
		public String writeValue(Object value) {
			return "{}";
		}
	}

	private static final byte[] BINARY_CONTENT = { 0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 'h', 'i' };

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

	@AfterEach
	void restoreDefaultObjectMapper() {
		RIP.setObjectMapper(new JsonObjectMapper());
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
		} else if (exchange.getRequestURI().getPath().startsWith("/slow/")) {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		} else if (exchange.getRequestURI().getPath().startsWith("/binary/")) {
			byte[] response = BINARY_CONTENT;
			exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		} else if (exchange.getRequestURI().getPath().startsWith("/error/")) {
			byte[] response = "{\"code\":\"INVALID\",\"message\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(422, response.length);
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
	void get_withPathParamContainingSpace_encodesAndDeliversLiteralSpace() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "a b", 7, "custom-value");

		assertEquals("ok", result);
		assertEquals("/items/a b", LAST_REQUEST.get().path);
	}

	@Test
	void get_withPathParamContainingQuestionMark_encodesInsteadOfStartingQueryString() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "a?b=c", 7, "custom-value");

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("/items/a?b=c", request.path);
		assertEquals("q=7", request.query);
	}

	@Test
	void get_withPathParamContainingSlash_encodesAsSingleSegment() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "a/b", 7, "custom-value");

		assertEquals("ok", result);
		assertEquals("/items/a/b", LAST_REQUEST.get().path);
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
	void queryMap_withEntries_sendsEachAsQueryParam() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> filters = new LinkedHashMap<>();
		filters.put("status", "active");
		filters.put("sort", "name");

		api.getWithQueryMap(port, "abc", filters);

		Set<String> queryParams = new HashSet<>(Arrays.asList(LAST_REQUEST.get().query.split("&")));
		assertEquals(new HashSet<>(Arrays.asList("status=active", "sort=name")), queryParams);
	}

	@Test
	void queryParam_withListValue_repeatsParamOncePerElement() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithMultiValueQuery(port, "abc", Arrays.asList("a", "b", "c"));

		assertEquals("tag=a&tag=b&tag=c", LAST_REQUEST.get().query);
	}

	@Test
	void queryMap_withListValue_repeatsThatEntryOncePerElement() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> filters = new LinkedHashMap<>();
		filters.put("status", "active");
		filters.put("tag", Arrays.asList("a", "b"));

		api.getWithMultiValueQueryMap(port, "abc", filters);

		Set<String> queryParams = new HashSet<>(Arrays.asList(LAST_REQUEST.get().query.split("&")));
		assertEquals(new HashSet<>(Arrays.asList("status=active", "tag=a", "tag=b")), queryParams);
	}

	@Test
	void queryMap_withNullMap_sendsNoQueryParams() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithQueryMap(port, "abc", null);

		assertNull(LAST_REQUEST.get().query);
	}

	@Test
	void queryMap_withNullValue_skipsThatEntry() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> filters = new LinkedHashMap<>();
		filters.put("status", "active");
		filters.put("skip", null);

		api.getWithQueryMap(port, "abc", filters);

		assertEquals("status=active", LAST_REQUEST.get().query);
	}

	@Test
	void queryMap_combinedWithFixedQueryParam_sendsBoth() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> extra = new LinkedHashMap<>();
		extra.put("status", "active");

		api.getWithFixedQueryParamAndQueryMap(port, "abc", "fixedValue", extra);

		Set<String> queryParams = new HashSet<>(Arrays.asList(LAST_REQUEST.get().query.split("&")));
		assertEquals(new HashSet<>(Arrays.asList("fixed=fixedValue", "status=active")), queryParams);
	}

	@Test
	void headerMap_withEntries_sendsEachAsHeader() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("X-Tenant", "acme");
		headers.put("X-Trace-Id", "trace-123");

		api.getWithHeaderMap(port, "abc", headers);

		assertEquals("acme", LAST_REQUEST.get().header("X-Tenant"));
		assertEquals("trace-123", LAST_REQUEST.get().header("X-Trace-Id"));
	}

	@Test
	void headerMap_withNullMap_sendsNoExtraHeaders() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getWithHeaderMap(port, "abc", null);

		assertEquals("ok", result);
	}

	@Test
	void headerMap_withNullValue_skipsThatEntry() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("X-Tenant", "acme");
		headers.put("X-Skip", null);

		api.getWithHeaderMap(port, "abc", headers);

		assertEquals("acme", LAST_REQUEST.get().header("X-Tenant"));
		assertNull(LAST_REQUEST.get().header("X-Skip"));
	}

	@Test
	void headers_fixedEntries_sendsEachAsHeaderWithTrimmedNameAndValue() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithFixedHeaders(port, "abc");

		assertEquals("no-cache", LAST_REQUEST.get().header("Cache-Control"));
		assertEquals("2", LAST_REQUEST.get().header("X-Api-Version"));
	}

	@Test
	void headers_withOverridingHeaderParam_headerParamValueWins() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithFixedHeaderAndOverridingHeaderParam(port, "abc", "from-param");

		assertEquals("from-param", LAST_REQUEST.get().header("X-Custom"));
	}

	@Test
	void multipart_withStringAndFileParts_sendsMultipartFormData() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipart(port, "abc", "a caption", file);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.header("Content-Type").startsWith("multipart/form-data"));
		assertTrue(request.body.contains("name=\"caption\""));
		assertTrue(request.body.contains("a caption"));
		assertTrue(request.body.contains("name=\"file\""));
		assertTrue(request.body.contains(file.getName()));
		assertTrue(request.body.contains("file contents"));
	}

	@Test
	void multipart_withNullOptionalPart_skipsThatField() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		api.uploadMultipart(port, "abc", null, file);

		assertFalse(LAST_REQUEST.get().body.contains("name=\"caption\""));
	}

	@Test
	void multipart_withMissingRequiredPart_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = new File("unused.txt");

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.uploadMultipartWithRequiredCaption(port, "abc", null, file));
		assertTrue(exception.getMessage().contains("Missing required value"));
	}

	@Test
	void multipart_withBytePartAndFileName_sendsGivenFileNameAndBytes() {
		LocalApi api = RIP.getClient(LocalApi.class);
		byte[] data = "byte contents".getBytes(StandardCharsets.UTF_8);
		InputStream stream = new ByteArrayInputStream("stream contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipartWithBytesAndStream(port, "abc", data, stream);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("name=\"data\""));
		assertTrue(request.body.contains("filename=\"data.bin\""));
		assertTrue(request.body.contains("byte contents"));
		assertTrue(request.body.contains("name=\"stream\""));
		assertTrue(request.body.contains("filename=\"stream\""));
		assertTrue(request.body.contains("stream contents"));
	}

	@Test
	void multipart_withFilePartAndFileName_overridesFileNameNotContentType() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		api.uploadMultipartWithRenamedFile(port, "abc", file);

		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("filename=\"renamed.txt\""));
		assertFalse(request.body.contains(file.getName()));
		assertTrue(request.body.contains("file contents"));
	}

	@Test
	void multipart_withUploadProgressListener_reportsFinalByteCountsForFileField() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));
		List<String> reportedFields = new ArrayList<>();
		List<Long> reportedBytesWritten = new ArrayList<>();

		String result = api.uploadMultipartWithProgress(port, "abc", file, (field, bytesWritten, totalBytes) -> {
			reportedFields.add(field);
			reportedBytesWritten.add(bytesWritten);
		});

		assertEquals("ok", result);
		assertFalse(reportedFields.isEmpty());
		assertTrue(reportedFields.stream().allMatch("file"::equals));
		assertEquals(file.length(), (long) reportedBytesWritten.get(reportedBytesWritten.size() - 1));
	}

	@Test
	void multipart_withNullUploadProgressListener_skipsProgressReporting() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipartWithProgress(port, "abc", file, null);

		assertEquals("ok", result);
	}

	@Test
	void partMap_withMixedValueTypes_sendsEachAsAppropriatePart() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("caption", "a caption");
		parts.put("data", "byte contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipartWithPartMap(port, "abc", parts);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("name=\"caption\""));
		assertTrue(request.body.contains("a caption"));
		assertTrue(request.body.contains("name=\"data\""));
		assertTrue(request.body.contains("filename=\"data\""));
		assertTrue(request.body.contains("byte contents"));
	}

	@Test
	void partMap_withPartValue_sendsGivenFileNameInsteadOfKey() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("file", PartValue.of("byte contents".getBytes(StandardCharsets.UTF_8), "photo.jpg"));

		String result = api.uploadMultipartWithPartMap(port, "abc", parts);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("name=\"file\""));
		assertTrue(request.body.contains("filename=\"photo.jpg\""));
		assertFalse(request.body.contains("filename=\"file\""));
		assertTrue(request.body.contains("byte contents"));
	}

	@Test
	void partMap_withPartValueWrappingFile_overridesFileName() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("file", PartValue.of(file, "renamed.txt"));

		api.uploadMultipartWithPartMap(port, "abc", parts);

		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("filename=\"renamed.txt\""));
		assertFalse(request.body.contains(file.getName()));
	}

	@Test
	void partMap_withNullMap_sendsNoParts() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.uploadMultipartWithPartMap(port, "abc", null);

		assertEquals("ok", result);
	}

	@Test
	void partMap_withNullValue_skipsThatEntry() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("caption", "a caption");
		parts.put("skip", null);

		api.uploadMultipartWithPartMap(port, "abc", parts);

		assertTrue(LAST_REQUEST.get().body.contains("name=\"caption\""));
		assertFalse(LAST_REQUEST.get().body.contains("name=\"skip\""));
	}

	@Test
	void partMap_withUnsupportedValueType_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("bad", 42);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.uploadMultipartWithPartMap(port, "abc", parts));
		assertTrue(exception.getMessage().contains("Unsupported"));
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
	void get_withRipResponseOfPojo_exposesStatusHeadersAndDeserializedBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<Payload> response = api.getPayloadWithResponse(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("application/json", response.getHeader("Content-Type"));
		assertEquals("application/json", response.getHeader("content-type"));
		assertEquals("Shrinivas", response.getBody().name);
		assertEquals(1993, response.getBody().age);
	}

	@Test
	void get_withRipResponseOfString_exposesRawBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<String> response = api.getWithResponseString(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("ok", response.getBody());
	}

	@Test
	void get_withRipResponseOfVoid_hasNullBodyButRealStatus() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<Void> response = api.getWithResponseVoid(port, "abc");

		assertEquals(200, response.getStatus());
		assertNull(response.getBody());
	}

	@Test
	void get_withRipResponseAndRetry_succeedsAfterRetryingAndStillExposesHeaders() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<String> response = api.getWithResponseAndRetry(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("ok", response.getBody());
	}

	@Test
	void get_withRipResponseOnNonSuccessStatus_stillThrowsInsteadOfWrapping() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getErrorWithResponse(port, "abc"));
		assertEquals(422, exception.getStatus());
	}

	@Test
	void getAsync_withRipResponseOfPojo_completesWithStatusHeadersAndBody()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<Payload> response = api.getPayloadWithResponseAsync(port, "abc").get(5, TimeUnit.SECONDS);

		assertEquals(200, response.getStatus());
		assertEquals("application/json", response.getHeader("Content-Type"));
		assertEquals("Shrinivas", response.getBody().name);
	}

	@Test
	void get_withNoTimeoutAnnotation_toleratesSlowResponse() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getSlow(port, "abc");

		assertEquals("ok", result);
	}

	@Test
	void get_withShortTimeoutAnnotation_throwsOnSlowResponse() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RuntimeException.class, () -> api.getSlowWithShortTimeout(port, "abc"));
	}

	@Test
	void get_withClientConfigReadTimeout_throwsOnSlowResponse() {
		LocalApi api = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().readTimeoutMillis(50).build());

		assertThrows(RuntimeException.class, () -> api.getSlow(port, "abc"));
	}

	@Test
	void get_withMethodTimeoutOverridingShortClientConfig_succeeds() {
		LocalApi api = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().readTimeoutMillis(50).build());

		String result = api.getSlowWithLongTimeoutOverride(port, "abc");

		assertEquals("ok", result);
	}

	@Test
	void getClient_withUnreachableProxy_throws() {
		LocalApi api = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().proxy("localhost", 1).build());

		assertThrows(RuntimeException.class, () -> api.getSlow(port, "abc"));
	}

	@Test
	void get_withRipSetObjectMapper_usesGivenMapperForSharedClient() {
		Payload fixedPayload = new Payload("custom-mapper", 1);
		RIP.setObjectMapper(new FixedValueObjectMapper(fixedPayload));
		LocalApi api = RIP.getClient(LocalApi.class);

		Payload payload = api.getPayload(port, "abc");

		assertEquals("custom-mapper", payload.name);
	}

	@Test
	void getClient_withRipClientConfigObjectMapper_usesGivenMapperForThatClientOnly() {
		Payload fixedPayload = new Payload("client-mapper", 2);
		LocalApi customApi = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().objectMapper(new FixedValueObjectMapper(fixedPayload)).build());
		LocalApi defaultApi = RIP.getClient(LocalApi.class);

		Payload customResult = customApi.getPayload(port, "abc");
		Payload defaultResult = defaultApi.getPayload(port, "abc");

		assertEquals("client-mapper", customResult.name);
		assertEquals("Shrinivas", defaultResult.name);
	}

	@Test
	void get_withNoObjectMapperConfigured_throwsRestInPeaceExceptionNotUnirestOne() {
		Unirest.config().setObjectMapper(null);
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class, () -> api.getPayload(port, "abc"));
		assertTrue(exception.getMessage().contains("No JSON ObjectMapper is configured"));
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
	void retry_withPermanentFailure_stopsAfterConfiguredAttemptsAndThrows() {
		List<Integer> statuses = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				statuses.add(status);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getAlwaysFailingWithRetry(port, "x"));

		assertEquals(503, exception.getStatus());
		assertEquals(Arrays.asList(503, 503, 503), statuses);
		assertEquals(3, ALWAYS_FAILING_ATTEMPTS.get());
	}

	@Test
	void withoutRetryAnnotation_doesNotRetryOnFailure() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RestInPeaceHttpException.class, () -> api.getAlwaysFailingWithoutRetry(port, "x"));

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

	@Test
	void get_withUrlParam_callsGivenUrlVerbatimIgnoringBaseUrl() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getWithUrlParam("http://localhost:" + port + "/items/abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void get_withUrlParamAndQueryParam_appendsQueryToGivenUrl() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getWithUrlParamAndQueryParam("http://localhost:" + port + "/items/abc", 7);

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
		assertEquals("q=7", LAST_REQUEST.get().query);
	}

	@Test
	void get_withNullUrlParam_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class, () -> api.getWithUrlParam(null));
		assertTrue(exception.getMessage().contains("Missing value for @Url parameter"));
	}

	@Test
	void get_withNonSuccessStatusAndNoErrorType_throwsWithRawBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getWithUntypedError(port, "x"));

		assertEquals(422, exception.getStatus());
		assertEquals("{\"code\":\"INVALID\",\"message\":\"nope\"}", exception.getRawBody());
		assertEquals(exception.getRawBody(), exception.getErrorBody());
	}

	@Test
	void get_withNonSuccessStatusAndErrorType_throwsWithDeserializedErrorBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getWithTypedError(port, "x"));

		assertEquals(422, exception.getStatus());
		ApiError error = exception.getErrorBody();
		assertEquals("INVALID", error.code);
		assertEquals("nope", error.message);
	}

	@Test
	void getAsync_withNonSuccessStatusAndErrorType_completesExceptionally() {
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<String> future = api.getWithTypedErrorAsync(port, "x");

		ExecutionException executionException = assertThrows(ExecutionException.class,
				() -> future.get(5, TimeUnit.SECONDS));
		assertTrue(executionException.getCause() instanceof RestInPeaceHttpException);
		RestInPeaceHttpException httpException = (RestInPeaceHttpException) executionException.getCause();
		assertEquals(422, httpException.getStatus());
		ApiError error = httpException.getErrorBody();
		assertEquals("INVALID", error.code);
	}

	@Test
	void get_withVoidReturnTypeAndNonSuccessStatus_stillThrows() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RestInPeaceHttpException.class, () -> api.pingAlwaysFailing(port, "x"));
	}

	@Test
	void get_withByteArrayReturnType_returnsExactBytesUncorrupted() {
		LocalApi api = RIP.getClient(LocalApi.class);

		byte[] result = api.downloadBytes(port, "abc");

		assertArrayEquals(BINARY_CONTENT, result);
	}

	@Test
	void getAsync_withByteArrayReturnType_completesWithExactBytes()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		byte[] result = api.downloadBytesAsync(port, "abc").get(5, TimeUnit.SECONDS);

		assertArrayEquals(BINARY_CONTENT, result);
	}

	@Test
	void get_withRipResponseOfByteArray_exposesStatusHeadersAndExactBytes() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<byte[]> response = api.downloadBytesWithResponse(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("application/octet-stream", response.getHeader("Content-Type"));
		assertArrayEquals(BINARY_CONTENT, response.getBody());
	}

	@Test
	void get_withByteArrayReturnTypeOnNonSuccessStatus_throwsWithStringRawBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.downloadBytesFromErrorEndpoint(port, "x"));

		assertEquals(422, exception.getStatus());
		assertEquals("{\"code\":\"INVALID\",\"message\":\"nope\"}", exception.getRawBody());
	}

	@Test
	void get_withDownloadProgressListener_reportsFinalByteCounts() {
		LocalApi api = RIP.getClient(LocalApi.class);
		List<Long> reportedBytesWritten = new ArrayList<>();
		List<Long> reportedTotalBytes = new ArrayList<>();

		byte[] result = api.downloadBytesWithProgress(port, "abc", (bytesWritten, totalBytes) -> {
			reportedBytesWritten.add(bytesWritten);
			reportedTotalBytes.add(totalBytes);
		});

		assertArrayEquals(BINARY_CONTENT, result);
		assertFalse(reportedBytesWritten.isEmpty());
		assertEquals(BINARY_CONTENT.length, (long) reportedBytesWritten.get(reportedBytesWritten.size() - 1));
	}

	@Test
	void get_withFileReturnTypeAndDestination_writesExactBytesToDestination() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File destination = Files.createTempFile("rip-download", ".bin").toFile();

		File result = api.downloadToFile(port, "abc", destination);

		assertEquals(destination, result);
		assertArrayEquals(BINARY_CONTENT, Files.readAllBytes(destination.toPath()));
	}

	@Test
	void getAsync_withFileReturnTypeAndDestination_writesExactBytesToDestination()
			throws IOException, InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File destination = Files.createTempFile("rip-download-async", ".bin").toFile();

		File result = api.downloadToFileAsync(port, "abc", destination).get(5, TimeUnit.SECONDS);

		assertEquals(destination, result);
		assertArrayEquals(BINARY_CONTENT, Files.readAllBytes(destination.toPath()));
	}

	@Test
	void get_withFileReturnTypeOnNonSuccessStatus_throwsAndDoesNotWriteDestination() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File destination = Files.createTempFile("rip-download-error", ".bin").toFile();

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.downloadToFileFromErrorEndpoint(port, "x", destination));

		assertEquals(422, exception.getStatus());
		assertEquals(0L, Files.size(destination.toPath()));
	}

	@Test
	void interceptor_afterResponse_onErrorStatus_seesDeserializedErrorBody() {
		List<Object> bodies = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				bodies.add(body);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RestInPeaceHttpException.class, () -> api.getWithTypedError(port, "x"));

		assertEquals(1, bodies.size());
		assertTrue(bodies.get(0) instanceof ApiError);
	}

}
