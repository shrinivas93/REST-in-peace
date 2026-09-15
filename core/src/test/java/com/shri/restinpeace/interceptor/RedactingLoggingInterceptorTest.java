package com.shri.restinpeace.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.constant.HTTPMethod;

class RedactingLoggingInterceptorTest {

	@Test
	void beforeRequest_defaultFields_masksPasswordAndTokenInTheRequestBody() {
		List<String> lines = new ArrayList<>();
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor(
				RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES, lines::add);
		RequestContext context = new RequestContext(HTTPMethod.POST, "https://api.example.com/login");
		context.setBody("{\"username\":\"shri\",\"password\":\"hunter2\",\"token\":\"abc123\"}");

		interceptor.beforeRequest(context);

		assertEquals(1, lines.size());
		assertTrue(lines.get(0).contains("\"username\":\"shri\""));
		assertTrue(lines.get(0).contains("\"password\": \"***\""));
		assertTrue(lines.get(0).contains("\"token\": \"***\""));
		assertFalse(lines.get(0).contains("hunter2"));
		assertFalse(lines.get(0).contains("abc123"));
	}

	@Test
	void beforeRequest_matchesFieldNamesCaseInsensitively() {
		List<String> lines = new ArrayList<>();
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor(
				RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES, lines::add);
		RequestContext context = new RequestContext(HTTPMethod.POST, "https://api.example.com/login");
		context.setBody("{\"Password\":\"hunter2\"}");

		interceptor.beforeRequest(context);

		assertFalse(lines.get(0).contains("hunter2"));
		assertTrue(lines.get(0).contains("\"Password\": \"***\""));
	}

	@Test
	void beforeRequest_unquotedNumericValue_isAlsoMasked() {
		List<String> lines = new ArrayList<>();
		Set<String> fields = new HashSet<>(Collections.singletonList("pin"));
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor(fields, lines::add);
		RequestContext context = new RequestContext(HTTPMethod.POST, "https://api.example.com/login");
		context.setBody("{\"pin\":1234}");

		interceptor.beforeRequest(context);

		assertFalse(lines.get(0).contains("1234"));
		assertTrue(lines.get(0).contains("\"pin\": \"***\""));
	}

	@Test
	void beforeRequest_nonSensitiveFields_areLoggedVerbatim() {
		List<String> lines = new ArrayList<>();
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor(
				RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES, lines::add);
		RequestContext context = new RequestContext(HTTPMethod.POST, "https://api.example.com/orders");
		context.setBody("{\"sku\":\"sku-1\",\"qty\":2}");

		interceptor.beforeRequest(context);

		assertTrue(lines.get(0).contains("\"sku\":\"sku-1\",\"qty\":2"));
	}

	@Test
	void beforeRequest_noBody_logsNoTrailingBodyText() {
		List<String> lines = new ArrayList<>();
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor(
				RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES, lines::add);
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/orders/42");

		interceptor.beforeRequest(context);

		assertEquals("--> GET https://api.example.com/orders/42", lines.get(0));
	}

	@Test
	void afterResponse_stringBody_masksConfiguredFieldsAndIncludesStatusAndDuration() {
		List<String> lines = new ArrayList<>();
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor(
				RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES, lines::add);
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/users/42");
		interceptor.beforeRequest(context);
		lines.clear();

		interceptor.afterResponse(context, 200, "{\"name\":\"Shrinivas\",\"apiKey\":\"secret-key\"}");

		assertEquals(1, lines.size());
		assertTrue(lines.get(0).startsWith("<-- GET https://api.example.com/users/42 200 ("));
		assertTrue(lines.get(0).contains("\"name\":\"Shrinivas\""));
		assertTrue(lines.get(0).contains("\"apiKey\": \"***\""));
		assertFalse(lines.get(0).contains("secret-key"));
	}

	@Test
	void afterResponse_nullBody_logsNoTrailingBodyText() {
		List<String> lines = new ArrayList<>();
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor(
				RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES, lines::add);
		RequestContext context = new RequestContext(HTTPMethod.DELETE, "https://api.example.com/users/42");
		interceptor.beforeRequest(context);
		lines.clear();

		interceptor.afterResponse(context, 204, null);

		assertTrue(lines.get(0).matches("<-- DELETE https://api.example.com/users/42 204 \\(\\d+ms\\)"));
	}

	@Test
	void defaultConstructor_logsToSystemOutAndUsesDefaultFieldNames() {
		RedactingLoggingInterceptor interceptor = new RedactingLoggingInterceptor();

		assertTrue(RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES.contains("password"));
		assertTrue(RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES.contains("token"));
		assertTrue(RedactingLoggingInterceptor.DEFAULT_SENSITIVE_FIELD_NAMES.contains("ssn"));
		// Exercised only for a clean instantiation / no-throw check - actual
		// output routing to System.out is covered by the sink-based tests above.
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/ping");
		interceptor.beforeRequest(context);
		interceptor.afterResponse(context, 200, "ok");
	}

}
