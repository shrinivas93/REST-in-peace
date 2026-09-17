package com.shri.restinpeace.spring.defaultbasepackage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.spring.EnableRestInPeaceClients;

import com.sun.net.httpserver.HttpServer;

/**
 * Two {@link RestInPeaceClientsRegistrar} paths every other spring-boot-
 * starter test leaves untouched (each one always passes an explicit
 * {@code basePackages}, and never uses {@code @RestClient(name = ...)}):
 * omitting {@code basePackages} entirely, which falls back to scanning the
 * {@code @EnableRestInPeaceClients}-annotated class's own package; and
 * {@code @RestClient(name = ...)}, which becomes the bean name verbatim
 * instead of the interface's decapitalized simple name.
 *
 * <p>
 * Declared in its own dedicated {@code .defaultbasepackage} sub-package,
 * scanned on its own - see {@code EnableRestInPeaceClientsTest}'s own
 * javadoc for why sharing a scanned package with another test's
 * {@code @RestClient} interface is unsafe.
 */
class DefaultBasePackageAndCustomNameTest {

	@RestClient
	interface PingApi {
		@GET("http://localhost:{port}/ping")
		String ping(@PathParam("port") int port);
	}

	@RestClient(name = "custom-ping-api")
	interface NamedPingApi {
		@GET("http://localhost:{port}/ping")
		String ping(@PathParam("port") int port);
	}

	@Configuration
	@EnableRestInPeaceClients
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
	void enableRestInPeaceClients_withNoBasePackages_scansTheAnnotatedClasssOwnPackage() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			PingApi pingApi = context.getBean(PingApi.class);

			assertEquals("pong", pingApi.ping(port));
		}
	}

	@Test
	void restClient_withExplicitName_registersUnderThatBeanNameVerbatim() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			Object namedPingApi = context.getBean("custom-ping-api");

			assertEquals("pong", ((NamedPingApi) namedPingApi).ping(port));
		}
	}

}
