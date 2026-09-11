package com.shri.restinpeace.spring.baseurl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.spring.EnableRestInPeaceClients;

import com.sun.net.httpserver.HttpServer;

/**
 * Proves {@link RestClient#baseUrlProperty()} resolves a client's base URL
 * from Spring's {@code Environment} instead of requiring a real
 * {@code @BaseUrl} - the bridge {@code @BaseUrl} itself can never express,
 * since a Java annotation attribute must always be a compile-time constant.
 *
 * <p>
 * Declared in its own dedicated {@code .baseurl} sub-package, scanned on its
 * own - see {@code EnableRestInPeaceClientsTest}'s own javadoc for why
 * sharing a scanned package with another test's {@code @RestClient}
 * interface is unsafe.
 */
class RestInPeaceClientBaseUrlPropertyTest {

	@RestClient(baseUrlProperty = "ping-api.base-url")
	interface PropertyConfiguredPingApi {
		@GET("/ping")
		String ping();
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.baseurl")
	static class TestConfig {
	}

	private static HttpServer server;
	private static int port;

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/ping", exchange -> {
			byte[] response = "pong".getBytes(StandardCharsets.UTF_8);
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
	void restInPeaceClient_withBaseUrlProperty_resolvesBaseUrlFromEnvironment() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
					Collections.singletonMap("ping-api.base-url", "http://localhost:" + port)));
			context.register(TestConfig.class);
			context.refresh();

			PropertyConfiguredPingApi pingApi = context.getBean(PropertyConfiguredPingApi.class);

			assertEquals("pong", pingApi.ping());
		}
	}

}
