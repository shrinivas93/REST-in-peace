package com.example.consumer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.shri.restinpeace.RIP;

import com.sun.net.httpserver.HttpServer;

/**
 * A standalone consumer program - a completely separate Maven build from
 * REST-in-peace's own - demonstrating the compile-time proxy generation
 * feature (see {@code docs/design/compile-time-proxy-generation.md}) the way
 * a real downstream user experiences it: just add the published jar as a
 * dependency, write a plain {@code @RestClient} interface, and
 * {@code RIP.getClient(...)} hands back the compile-time-generated
 * implementation with zero extra configuration - falling back to the
 * original reflective proxy for an interface using a feature the generator
 * doesn't cover yet.
 *
 * <p>
 * See this directory's {@code README.md} for how to build and run it (after
 * {@code mvn install}-ing the parent {@code rest-in-peace} project).
 */
public final class Main {

	public static void main(String[] args) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> {
			String response = "path=" + exchange.getRequestURI().getPath() + ";query="
					+ exchange.getRequestURI().getRawQuery();
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.start();
		int port = server.getAddress().getPort();

		try {
			// 1. Confirm the generated class actually exists on the classpath -
			// proof RestClientProcessor ran during this project's own build.
			Class<?> generatedClass = Class.forName("com.example.consumer.ItemApi_RipImpl");
			System.out.println("Found generated class: " + generatedClass.getName());

			// 2. Confirm RIP.getClient(...) actually returns it, not the
			// reflective java.lang.reflect.Proxy fallback.
			ItemApi api = RIP.getClient(ItemApi.class);
			String actualClassName = api.getClass().getName();
			if (!actualClassName.equals("com.example.consumer.ItemApi_RipImpl")) {
				throw new IllegalStateException(
						"Expected RIP.getClient(...) to return the generated implementation, but got: "
								+ actualClassName);
			}
			System.out.println("RIP.getClient(ItemApi.class) returned: " + actualClassName);

			// 3. Confirm a real call through it produces the correct request.
			String result = api.getItem(port, "sample-item-42", 1);
			String expectedResult = "path=/items/sample-item-42;query=verbose=1";
			if (!expectedResult.equals(result)) {
				throw new IllegalStateException(
						"Unexpected response. Expected '" + expectedResult + "' but got '" + result + "'");
			}
			System.out.println("Call succeeded, response: " + result);

			// 4. Confirm an interface using an unsupported feature (@HeaderParam)
			// falls back to the reflective proxy instead of a broken partial
			// generation - no ItemApi_RipImpl-style class should exist for it.
			try {
				Class.forName("com.example.consumer.UnsupportedApi_RipImpl");
				throw new IllegalStateException("Expected no generated class for UnsupportedApi, but one exists.");
			} catch (ClassNotFoundException notFound) {
				System.out.println("Confirmed no generated class exists for UnsupportedApi (as expected).");
			}
			UnsupportedApi unsupportedApi = RIP.getClient(UnsupportedApi.class);
			if (!unsupportedApi.getClass().getName().contains("Proxy")) {
				throw new IllegalStateException(
						"Expected the reflective proxy fallback for UnsupportedApi, but got: "
								+ unsupportedApi.getClass().getName());
			}
			System.out.println("RIP.getClient(UnsupportedApi.class) fell back to: " + unsupportedApi.getClass());
			String unsupportedResult = unsupportedApi.getItem(port, "abc", "trace-1");
			System.out.println("Reflective-proxy call still works, response: " + unsupportedResult);

			System.out
					.println("VERIFICATION PASSED: compile-time proxy generation works for a real downstream consumer.");
		} finally {
			server.stop(0);
		}
	}

}
