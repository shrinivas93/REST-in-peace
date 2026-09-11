package com.shri.restinpeace.spring.registration;

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
 * The smallest possible end-to-end slice for
 * {@link EnableRestInPeaceClients}: annotate, scan, register, inject, call -
 * proving a {@code @RestClient} interface comes back as a real, working
 * Spring bean with zero hand-written {@code @Bean} method.
 *
 * <p>
 * Declared in its own dedicated {@code .registration} sub-package, scanned
 * on its own - {@link EnableRestInPeaceClients}'s registrar resolves every
 * {@link com.shri.restinpeace.spring.RestInPeaceClient#baseUrlProperty()} it
 * finds eagerly, at bean-registration time (matching the core library's own
 * fail-fast-at-construction philosophy), so an unrelated test's
 * {@code @RestClient} interface with an unset property sitting in the same
 * scanned package would otherwise fail this context's startup too.
 */
class EnableRestInPeaceClientsTest {

	@RestClient
	interface PingApi {
		@GET("http://localhost:{port}/ping")
		String ping(@PathParam("port") int port);
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.registration")
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
	void enableRestInPeaceClients_registersRestClientInterfaceAsBean() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			PingApi pingApi = context.getBean(PingApi.class);

			assertEquals("pong", pingApi.ping(port));
		}
	}

	@Test
	void enableRestInPeaceClients_derivesBeanNameFromInterfaceSimpleName() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			Object pingApi = context.getBean("pingApi");

			assertEquals("pong", ((PingApi) pingApi).ping(port));
		}
	}

}
