package com.example.consumer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.shri.restinpeace.RIP;

import com.sun.net.httpserver.HttpServer;

/**
 * The native-image smoke test for compile-time proxy generation (step 3 of
 * {@code docs/design/compile-time-proxy-generation.md}'s rollout plan) -
 * built into a GraalVM native executable via this project's {@code native}
 * Maven profile (see its {@code pom.xml} and {@code README.md}).
 *
 * <p>
 * Deliberately narrower than {@link Main}: {@code Main} also demonstrates
 * {@code UnsupportedApi}'s fallback to the reflective {@code java.lang.reflect.Proxy}
 * path, which is a genuinely different, expected story under native-image -
 * that fallback still needs hand-written {@code proxy-config.json}, exactly
 * the ergonomics problem this feature exists to remove, and precisely
 * because it *doesn't* fall within that removal. Mixing the two into one
 * native-image binary would muddy what this smoke test is actually proving.
 * This class exercises only the fully-covered path: one {@code @RestClient}
 * interface, one call, zero hand-written native-image configuration -
 * exactly the design doc's own description of the minimal proof needed
 * (§6, §8 step 3).
 */
public final class NativeMain {

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

			// 2. Confirm RIP.getClient(...) actually returns it under native-image,
			// with no hand-written reflect-config.json in this project - only the
			// one RestClientProcessor itself emits alongside the generated source.
			ItemApi api = RIP.getClient(ItemApi.class);
			String actualClassName = api.getClass().getName();
			if (!actualClassName.equals("com.example.consumer.ItemApi_RipImpl")) {
				throw new IllegalStateException(
						"Expected RIP.getClient(...) to return the generated implementation, but got: "
								+ actualClassName);
			}
			System.out.println("RIP.getClient(ItemApi.class) returned: " + actualClassName);

			// 3. Confirm a real call through it produces the correct request - the
			// concrete, checkable proof the whole generated path (HTTP request
			// building, Unirest's HTTP client, response handling) survives
			// native-image's closed-world analysis end to end.
			String result = api.getItem(port, "sample-item-42", 1);
			String expectedResult = "path=/items/sample-item-42;query=verbose=1";
			if (!expectedResult.equals(result)) {
				throw new IllegalStateException(
						"Unexpected response. Expected '" + expectedResult + "' but got '" + result + "'");
			}
			System.out.println("Call succeeded, response: " + result);

			System.out.println(
					"NATIVE-IMAGE VERIFICATION PASSED: compile-time proxy generation works under GraalVM native-image with zero hand-written reflection config.");
		} finally {
			server.stop(0);
		}
	}

}
