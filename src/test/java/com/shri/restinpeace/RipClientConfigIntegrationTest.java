package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.ObjectMapper;
import kong.unirest.Unirest;

/**
 * Per-call/per-client behavior configured via {@code @Timeout} or
 * {@link RipClientConfig} - connect/read timeout, an unreachable proxy, and
 * a custom {@code ObjectMapper} (global via {@code RIP.setObjectMapper}, or
 * per-client) - split out of {@code RipIntegrationTest} (see
 * {@link AbstractRipIntegrationTest}).
 */
class RipClientConfigIntegrationTest extends AbstractRipIntegrationTest {

	private static final class FixedValueObjectMapper implements ObjectMapper {
		private final Object fixedValue;

		FixedValueObjectMapper(Object fixedValue) {
			this.fixedValue = fixedValue;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T readValue(String value, Class<T> valueType) {
			return (T) fixedValue;
		}

		@Override
		public String writeValue(Object value) {
			return "{}";
		}
	}

	@Test
	void get_withNoTimeoutAnnotation_toleratesSlowResponse() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getSlow(port, "abc");

		assertEquals("ok", result);
	}

	@Test
	void get_withShortTimeoutAnnotation_throwsOnSlowResponse() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RuntimeException.class, () -> api.getSlowWithShortTimeout(port, "abc"));
	}

	@Test
	void get_withClientConfigReadTimeout_throwsOnSlowResponse() {
		LocalApi api = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().readTimeoutMillis(50).build());

		assertThrows(RuntimeException.class, () -> api.getSlow(port, "abc"));
	}

	@Test
	void get_withMethodTimeoutOverridingShortClientConfig_succeeds() {
		LocalApi api = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().readTimeoutMillis(50).build());

		String result = api.getSlowWithLongTimeoutOverride(port, "abc");

		assertEquals("ok", result);
	}

	@Test
	void getClient_withUnreachableProxy_throws() {
		LocalApi api = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().proxy("localhost", 1).build());

		assertThrows(RuntimeException.class, () -> api.getSlow(port, "abc"));
	}

	@Test
	void get_withRipSetObjectMapper_usesGivenMapperForSharedClient() {
		Payload fixedPayload = new Payload("custom-mapper", 1);
		RIP.setObjectMapper(new FixedValueObjectMapper(fixedPayload));
		LocalApi api = RIP.getClient(LocalApi.class);

		Payload payload = api.getPayload(port, "abc");

		assertEquals("custom-mapper", payload.name);
	}

	@Test
	void getClient_withRipClientConfigObjectMapper_usesGivenMapperForThatClientOnly() {
		Payload fixedPayload = new Payload("client-mapper", 2);
		LocalApi customApi = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().objectMapper(new FixedValueObjectMapper(fixedPayload)).build());
		LocalApi defaultApi = RIP.getClient(LocalApi.class);

		Payload customResult = customApi.getPayload(port, "abc");
		Payload defaultResult = defaultApi.getPayload(port, "abc");

		assertEquals("client-mapper", customResult.name);
		assertEquals("Shrinivas", defaultResult.name);
	}

	@Test
	void get_withNoObjectMapperConfigured_throwsRestInPeaceExceptionNotUnirestOne() {
		Unirest.config().setObjectMapper(null);
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class, () -> api.getPayload(port, "abc"));
		assertTrue(exception.getMessage().contains("No JSON ObjectMapper is configured"));
	}

}
