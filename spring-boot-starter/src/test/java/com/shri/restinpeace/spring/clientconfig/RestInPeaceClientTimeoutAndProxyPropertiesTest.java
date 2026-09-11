package com.shri.restinpeace.spring.clientconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.spring.EnableRestInPeaceClients;

import com.sun.net.httpserver.HttpServer;

/**
 * Proves {@code rest-in-peace.clients.<name>.*} timeout/proxy properties
 * (§4.4) actually reach the registered client's {@code RipClientConfig},
 * the same way core's own
 * {@code RipClientConfigIntegrationTest#get_withClientConfigReadTimeout_throwsOnSlowResponse}
 * and {@code #getClient_withUnreachableProxy_throws} prove it for a
 * hand-built config - same 300ms server delay / 50ms read timeout and
 * unreachable-proxy shapes, just resolved from Spring properties instead of
 * a builder call.
 *
 * <p>
 * Declared in its own dedicated {@code .clientconfig} sub-package, scanned
 * on its own - see {@code EnableRestInPeaceClientsTest}'s own javadoc for
 * why sharing a scanned package with another test's {@code @RestClient}
 * interface is unsafe.
 */
class RestInPeaceClientTimeoutAndProxyPropertiesTest {

	@RestClient
	interface SlowApi {
		@GET("http://localhost:{port}/slow")
		String getSlow(@PathParam("port") int port);
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.clientconfig")
	static class TestConfig {
	}

	private static HttpServer server;
	private static int port;

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/slow", exchange -> {
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
			exchange.close();
		});
		server.setExecutor(null);
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@Test
	void restInPeaceClient_withReadTimeoutMillisProperty_throwsOnSlowResponse() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
					Collections.singletonMap("rest-in-peace.clients.slowApi.read-timeout-millis", "50")));
			context.register(TestConfig.class);
			context.refresh();

			SlowApi slowApi = context.getBean(SlowApi.class);

			assertThrows(RuntimeException.class, () -> slowApi.getSlow(port));
		}
	}

	@Test
	void restInPeaceClient_withoutTimeoutProperty_succeedsOnSlowResponse() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			SlowApi slowApi = context.getBean(SlowApi.class);

			assertEquals("ok", slowApi.getSlow(port));
		}
	}

	@Test
	void restInPeaceClient_withUnreachableProxyProperty_throws() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			Map<String, Object> proxyProperties = new HashMap<>();
			proxyProperties.put("rest-in-peace.clients.slowApi.proxy.host", "localhost");
			proxyProperties.put("rest-in-peace.clients.slowApi.proxy.port", "1");
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", proxyProperties));
			context.register(TestConfig.class);
			context.refresh();

			SlowApi slowApi = context.getBean(SlowApi.class);

			assertThrows(RuntimeException.class, () -> slowApi.getSlow(port));
		}
	}

}
