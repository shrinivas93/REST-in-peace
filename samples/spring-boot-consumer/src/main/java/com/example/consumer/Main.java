package com.example.consumer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.shri.restinpeace.spring.EnableRestInPeaceClients;

import com.sun.net.httpserver.HttpServer;

/**
 * A standalone consumer program - a completely separate Maven build from
 * both {@code rest-in-peace} and {@code rest-in-peace-spring-boot-starter}'s
 * own - demonstrating the Spring Boot starter (see
 * {@code docs/design/spring-boot-starter.md}) the way a real downstream
 * user experiences it: add both published jars as ordinary dependencies,
 * {@code @EnableRestInPeaceClients}, and inject {@link UserApi} like any
 * other Spring bean, with zero hand-written {@code @Bean} method.
 *
 * <p>
 * See this directory's {@code README.md} for how to build and run it
 * (after {@code mvn install}-ing both the core project and
 * {@code spring-boot-starter/} first). No {@code spring-boot-starter-web}
 * dependency is pulled in, so the app runs {@link #verify} once via
 * {@link CommandLineRunner} and then exits - there's no embedded server to
 * keep the JVM alive, and none is needed to prove the point.
 */
@SpringBootApplication
@EnableRestInPeaceClients
public class Main {

	private static final int LOCAL_SERVER_PORT = 18085;

	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(LOCAL_SERVER_PORT), 0);
		server.createContext("/users/", exchange -> {
			String id = exchange.getRequestURI().getPath().substring("/users/".length());
			byte[] response = ("Hello, " + id).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
			exchange.close();
		});
		server.start();

		try {
			SpringApplication.run(Main.class, args);
		} finally {
			server.stop(0);
		}
	}

	@Bean
	CommandLineRunner verify(UserApi userApi) {
		return args -> {
			// Confirm the starter actually registered UserApi as a real,
			// working Spring bean - not just that the context started.
			String result = userApi.getUser("42");
			String expected = "Hello, 42";
			if (!expected.equals(result)) {
				throw new IllegalStateException(
						"Unexpected response. Expected '" + expected + "' but got '" + result + "'");
			}
			System.out.println("UserApi.getUser(\"42\") returned: " + result);
			System.out.println("VERIFICATION PASSED: the Spring Boot starter registered UserApi as a real "
					+ "Spring bean for a real downstream consumer.");
		};
	}

}
