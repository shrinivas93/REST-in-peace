package com.shri.restinpeace.spring.beanwiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.cache.CachedResponse;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;
import com.shri.restinpeace.spring.EnableRestInPeaceClients;
import com.shri.restinpeace.spring.RestInPeaceAutoConfiguration;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import kong.unirest.ObjectMapper;

/**
 * Proves the §4.4 {@code ObjectMapper}/{@code Cache}/{@code RequestInterceptor}
 * bean-wiring rules actually reach a registered client's {@code RipClientConfig}:
 * a bean qualified with this client's own kebab-case name wins, an interceptor
 * with no qualifier is instead registered globally by
 * {@link RestInPeaceAutoConfiguration}, and a single unqualified bean of a
 * type is the shared default every client without its own falls back to.
 *
 * <p>
 * Declared in its own dedicated {@code .beanwiring} sub-package, scanned on
 * its own - see {@code EnableRestInPeaceClientsTest}'s own javadoc for why
 * sharing a scanned package with another test's {@code @RestClient}
 * interface is unsafe.
 */
class RestInPeaceClientBeanWiringTest {

	@RestClient
	interface WiringApi {
		@GET("http://localhost:{port}/data")
		Payload getData(@PathParam("port") int port);

		@GET("http://localhost:{port}/cacheable")
		String getCacheable(@PathParam("port") int port);
	}

	static final class Payload {
		String value;

		Payload() {
		}

		Payload(String value) {
			this.value = value;
		}
	}

	/** Ignores the actual response body and always decodes to a fixed instance. */
	static final class FixedValueObjectMapper implements ObjectMapper {
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
			return "";
		}
	}

	/** Never actually caches anything - only counts how often it's consulted. */
	static final class CountingCache implements Cache {
		final AtomicInteger getCalls = new AtomicInteger();
		final AtomicInteger putCalls = new AtomicInteger();

		@Override
		public CachedResponse get(String key) {
			getCalls.incrementAndGet();
			return null;
		}

		@Override
		public void put(String key, CachedResponse response) {
			putCalls.incrementAndGet();
		}

		@Override
		public void evict(String key) {
		}

		@Override
		public void clear() {
		}
	}

	private static HttpServer server;
	private static int port;
	private static final AtomicReference<Headers> lastRequestHeaders = new AtomicReference<>();

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/data", exchange -> {
			lastRequestHeaders.set(exchange.getRequestHeaders());
			respond(exchange, null, "{}");
		});
		server.createContext("/cacheable", exchange -> {
			lastRequestHeaders.set(exchange.getRequestHeaders());
			respond(exchange, "max-age=60", "cached-ok");
		});
		server.setExecutor(null);
		server.start();
		port = server.getAddress().getPort();
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, String cacheControl, String body)
			throws IOException {
		if (cacheControl != null) {
			exchange.getResponseHeaders().add("Cache-Control", cacheControl);
		}
		byte[] response = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, response.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(response);
		}
		exchange.close();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.beanwiring")
	static class QualifiedMapperConfig {
		@Bean
		@Qualifier("wiring-api")
		ObjectMapper wiringApiMapper() {
			return new FixedValueObjectMapper(new Payload("from-qualified-mapper"));
		}
	}

	@Test
	void restInPeaceClient_withQualifiedObjectMapperBean_usesItForDecoding() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				QualifiedMapperConfig.class)) {
			WiringApi wiringApi = context.getBean(WiringApi.class);

			assertEquals("from-qualified-mapper", wiringApi.getData(port).value);
		}
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.beanwiring")
	static class SharedUnqualifiedMapperConfig {
		@Bean
		ObjectMapper sharedMapper() {
			return new FixedValueObjectMapper(new Payload("from-shared-mapper"));
		}
	}

	@Test
	void restInPeaceClient_withSingleUnqualifiedObjectMapperBean_usesItAsSharedDefault() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				SharedUnqualifiedMapperConfig.class)) {
			WiringApi wiringApi = context.getBean(WiringApi.class);

			assertEquals("from-shared-mapper", wiringApi.getData(port).value);
		}
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.beanwiring")
	static class QualifiedInterceptorConfig {
		@Bean
		ObjectMapper mapper() {
			return new FixedValueObjectMapper(new Payload("ignored"));
		}

		@Bean
		@Qualifier("wiring-api")
		RequestInterceptor wiringApiInterceptor() {
			return new RequestInterceptor() {
				@Override
				public void beforeRequest(RequestContext context) {
					context.addHeader("X-Client-Specific", "true");
				}
			};
		}
	}

	@Test
	void restInPeaceClient_withQualifiedInterceptorBean_appliesToThatClientsRequests() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				QualifiedInterceptorConfig.class)) {
			WiringApi wiringApi = context.getBean(WiringApi.class);

			wiringApi.getData(port);

			assertEquals("true", lastRequestHeaders.get().getFirst("X-Client-Specific"));
		}
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.beanwiring")
	@Import(RestInPeaceAutoConfiguration.class)
	static class GlobalInterceptorConfig {
		@Bean
		ObjectMapper mapper() {
			return new FixedValueObjectMapper(new Payload("ignored"));
		}

		@Bean
		RequestInterceptor globalInterceptor() {
			return new RequestInterceptor() {
				@Override
				public void beforeRequest(RequestContext context) {
					context.addHeader("X-Global", "true");
				}
			};
		}
	}

	@Test
	void restInPeaceClient_withUnqualifiedInterceptorBeanAndAutoConfiguration_appliesGlobally() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				GlobalInterceptorConfig.class)) {
			WiringApi wiringApi = context.getBean(WiringApi.class);

			wiringApi.getData(port);

			assertEquals("true", lastRequestHeaders.get().getFirst("X-Global"));
		}
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.beanwiring")
	static class QualifiedCacheConfig {
		@Bean
		ObjectMapper mapper() {
			return new FixedValueObjectMapper(new Payload("ignored"));
		}

		@Bean
		@Qualifier("wiring-api")
		CountingCache wiringApiCache() {
			return new CountingCache();
		}
	}

	@Test
	void restInPeaceClient_withQualifiedCacheBean_isConsultedForCacheableResponses() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				QualifiedCacheConfig.class)) {
			WiringApi wiringApi = context.getBean(WiringApi.class);
			CountingCache cache = context.getBean(CountingCache.class);

			assertEquals("cached-ok", wiringApi.getCacheable(port));

			assertTrue(cache.getCalls.get() > 0, "expected the wired Cache to be consulted at least once");
			assertTrue(cache.putCalls.get() > 0, "expected the cacheable response to be stored");
		}
	}

}
