package com.shri.restinpeace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import com.shri.restinpeace.annotation.cache.NoCache;
import com.shri.restinpeace.annotation.error.ErrorType;
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
import com.shri.restinpeace.annotation.request.Field;
import com.shri.restinpeace.annotation.request.FieldMap;
import com.shri.restinpeace.annotation.request.FormUrlEncoded;
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
import com.shri.restinpeace.cache.InMemoryCache;
import com.shri.restinpeace.download.DownloadProgressListener;
import com.shri.restinpeace.upload.UploadProgressListener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import kong.unirest.JsonObjectMapper;

/**
 * Shared fixture for {@code RipIntegrationTest}'s split-out feature-area test
 * classes (see the package's other {@code Rip*IntegrationTest} classes) - the
 * embedded local {@link HttpServer}, the request-capturing dispatcher, and
 * {@code LocalApi}, the {@code @RestClient} interface most of them drive it
 * through. Extracted since every one of those classes needs the identical
 * server/fixture setup; JUnit 5 runs an inherited {@code @BeforeAll}/
 * {@code @AfterAll} once per concrete subclass, so each gets its own
 * server instance on its own port.
 */
abstract class AbstractRipIntegrationTest {

	static final class CapturedRequest {
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
	interface LocalApi {
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
				@Part("caption") String caption, @Part("file") java.io.File file);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithRequiredCaption(@PathParam("port") int port, @PathParam("id") String id,
				@Part(value = "caption", required = true) String caption, @Part("file") java.io.File file);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithBytesAndStream(@PathParam("port") int port, @PathParam("id") String id,
				@Part(value = "data", fileName = "data.bin") byte[] data, @Part("stream") InputStream stream);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithRenamedFile(@PathParam("port") int port, @PathParam("id") String id,
				@Part(value = "file", fileName = "renamed.txt") java.io.File file);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithPartMap(@PathParam("port") int port, @PathParam("id") String id,
				@PartMap Map<String, Object> parts);

		@POST("http://localhost:{port}/items/{id}")
		@Multipart
		String uploadMultipartWithProgress(@PathParam("port") int port, @PathParam("id") String id,
				@Part("file") java.io.File file, UploadProgressListener listener);

		@POST("http://localhost:{port}/items/{id}")
		@FormUrlEncoded
		String postFormUrlEncoded(@PathParam("port") int port, @PathParam("id") String id,
				@Field("grant_type") String grantType, @Field("client_id") String clientId);

		@POST("http://localhost:{port}/items/{id}")
		@FormUrlEncoded
		String postFormUrlEncodedWithRequiredField(@PathParam("port") int port, @PathParam("id") String id,
				@Field(value = "grant_type", required = true) String grantType);

		@POST("http://localhost:{port}/items/{id}")
		@FormUrlEncoded
		String postFormUrlEncodedWithFieldMap(@PathParam("port") int port, @PathParam("id") String id,
				@FieldMap Map<String, Object> fields);

		@POST("http://localhost:{port}/items/{id}")
		@FormUrlEncoded
		String postFormUrlEncodedWithCollectionField(@PathParam("port") int port, @PathParam("id") String id,
				@Field("tag") List<String> tags);

		@GET("http://localhost:{port}/cacheable/{id}")
		String getCacheable(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/cacheable/{id}")
		@NoCache
		String getCacheableNoCache(@PathParam("port") int port, @PathParam("id") String id);

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

		@GET("http://localhost:{port}/flaky/{id}")
		@Retry(times = 3, delayMillis = 5, retryOnStatus = { 503 }, idempotent = true)
		String getFlakyIdempotent(@PathParam("port") int port, @PathParam("id") String id);

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
		java.io.File downloadToFile(@PathParam("port") int port, @PathParam("id") String id,
				@Destination java.io.File target);

		@GET("http://localhost:{port}/binary/{id}")
		CompletableFuture<java.io.File> downloadToFileAsync(@PathParam("port") int port, @PathParam("id") String id,
				@Destination java.io.File target);

		@GET("http://localhost:{port}/error/{id}")
		byte[] downloadBytesFromErrorEndpoint(@PathParam("port") int port, @PathParam("id") String id);

		@GET("http://localhost:{port}/error/{id}")
		java.io.File downloadToFileFromErrorEndpoint(@PathParam("port") int port, @PathParam("id") String id,
				@Destination java.io.File target);

		@GET
		String getWithUrlParam(@Url String url);

		@GET
		String getWithUrlParamAndQueryParam(@Url String url, @QueryParam("q") Integer q);
	}

	static final class Payload {
		public String name;
		public int age;

		public Payload(String name, int age) {
			this.name = name;
			this.age = age;
		}
	}

	static final class ApiError {
		public String code;
		public String message;
	}

	static final byte[] BINARY_CONTENT = { 0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 'h', 'i' };

	static HttpServer server;
	static int port;
	static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();
	static final AtomicInteger FLAKY_ATTEMPTS = new AtomicInteger();
	static final AtomicInteger ALWAYS_FAILING_ATTEMPTS = new AtomicInteger();
	static final AtomicInteger CACHEABLE_HITS = new AtomicInteger();
	static final InMemoryCache CACHE = new InMemoryCache();
	static final List<String> IDEMPOTENCY_KEYS_SEEN = Collections.synchronizedList(new ArrayList<>());

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", AbstractRipIntegrationTest::handle);
		server.setExecutor(null);
		server.start();
		port = server.getAddress().getPort();
		RIP.setCache(CACHE);
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
		RIP.setCache(null);
	}

	@BeforeEach
	void resetCapturedRequest() {
		LAST_REQUEST.set(null);
		FLAKY_ATTEMPTS.set(0);
		ALWAYS_FAILING_ATTEMPTS.set(0);
		CACHEABLE_HITS.set(0);
		CACHE.clear();
		IDEMPOTENCY_KEYS_SEEN.clear();
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
		IDEMPOTENCY_KEYS_SEEN.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));

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
		} else if (exchange.getRequestURI().getPath().startsWith("/cacheable/")) {
			CACHEABLE_HITS.incrementAndGet();
			byte[] response = "cached-value".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Cache-Control", "max-age=60");
			exchange.sendResponseHeaders(200, response.length);
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

}
