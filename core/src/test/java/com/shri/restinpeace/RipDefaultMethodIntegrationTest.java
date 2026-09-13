package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * A {@code @RestClient} interface with a default method (an ergonomic
 * wrapper calling another method on the same interface) used to fail
 * {@code RIP.getClient(...)} outright - {@code ReflectiveRestClientValidator}
 * required every method returned by {@code Class.getMethods()}, default ones
 * included, to carry an HTTP method annotation. Fixed by exempting default
 * (and static) methods from validation and dispatching them to their own
 * real implementation instead of routing them as an HTTP call - see
 * {@code RestClientInvocationHandler.invokeDefaultMethod}.
 */
class RipDefaultMethodIntegrationTest extends AbstractRipIntegrationTest {

	@RestClient
	private interface ApiWithDefaultMethod {
		@GET("http://localhost:{port}/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id);

		/** Computed purely from the argument - proves this is dispatched as a real default method, not an HTTP call. */
		default String greeting(String name) {
			return "Hello, " + name + "!";
		}

		/** Calls another method on the same interface - proves the delegation actually reaches a real HTTP call too. */
		default String getUpperCase(int port, String id) {
			return get(port, id).toUpperCase();
		}

		static String staticHelper() {
			return "static-helper";
		}
	}

	@Test
	void getClient_withInterfaceHavingADefaultMethod_doesNotFailValidation() {
		ApiWithDefaultMethod api = RIP.getClient(ApiWithDefaultMethod.class);

		assertEquals("ok", api.get(port, "x"));
	}

	@Test
	void defaultMethod_withNoHttpCallAtAll_returnsItsOwnComputedResult() {
		ApiWithDefaultMethod api = RIP.getClient(ApiWithDefaultMethod.class);

		assertEquals("Hello, World!", api.greeting("World"));
	}

	@Test
	void defaultMethod_callingAnotherInterfaceMethod_actuallyIssuesThatHttpCall() {
		ApiWithDefaultMethod api = RIP.getClient(ApiWithDefaultMethod.class);

		assertEquals("OK", api.getUpperCase(port, "x"));
	}

	@Test
	void staticInterfaceMethod_isUnaffectedByTheProxy() {
		assertEquals("static-helper", ApiWithDefaultMethod.staticHelper());
	}

}
