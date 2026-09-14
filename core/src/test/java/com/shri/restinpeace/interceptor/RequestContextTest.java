package com.shri.restinpeace.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * {@link RequestContext#toCurlCommand()}/{@link
 * RequestContext#toCurlCommand(RequestContext.CurlVerbosity)} - pure
 * String-formatting logic, so exercised directly rather than through a real
 * HTTP round trip.
 */
class RequestContextTest {

	@Test
	void toCurlCommand_noHeadersOrBody_rendersJustMethodAndUrl() {
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/users/42");

		assertEquals("curl -X GET 'https://api.example.com/users/42'", context.toCurlCommand());
	}

	@Test
	void toCurlCommand_withHeadersAndBody_includesBothInDeclarationOrder() {
		RequestContext context = new RequestContext(HTTPMethod.POST, "https://api.example.com/charges");
		context.addHeader("Content-Type", "application/json");
		context.addHeader("Idempotency-Key", "abc123");
		context.setBody("{\"amount\":500}");

		assertEquals("curl -X POST 'https://api.example.com/charges' -H 'Content-Type: application/json' "
				+ "-H 'Idempotency-Key: abc123' -d '{\"amount\":500}'", context.toCurlCommand());
	}

	@Test
	void toCurlCommand_bodyContainingASingleQuote_isShellEscaped() {
		RequestContext context = new RequestContext(HTTPMethod.POST, "https://api.example.com/notes");
		context.setBody("it's a test");

		assertEquals("curl -X POST 'https://api.example.com/notes' -d 'it'\\''s a test'", context.toCurlCommand());
	}

	@Test
	void toCurlCommand_noArgOverload_isEquivalentToVerbosityNone() {
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/users/42");

		assertEquals(context.toCurlCommand(RequestContext.CurlVerbosity.NONE), context.toCurlCommand());
	}

	@Test
	void toCurlCommand_verbose_addsTheDashVFlagRightAfterTheMethod() {
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/users/42");

		assertEquals("curl -X GET -v 'https://api.example.com/users/42'",
				context.toCurlCommand(RequestContext.CurlVerbosity.VERBOSE));
	}

	@Test
	void toCurlCommand_trace_addsTheTraceAsciiFlagRightAfterTheMethod() {
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/users/42");

		assertEquals("curl -X GET --trace-ascii - 'https://api.example.com/users/42'",
				context.toCurlCommand(RequestContext.CurlVerbosity.TRACE));
	}

}
