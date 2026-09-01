package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.exception.RestInPeaceException;

class RipTest {

	@RestClient
	public interface ValidApi {
		@GET("http://example.com")
		String foo();
	}

	public interface InvalidApi {
		@GET("http://example.com")
		String foo();
	}

	@Test
	void getClient_validInterface_returnsUsableProxy() {
		ValidApi client = RIP.getClient(ValidApi.class);
		assertNotNull(client);
		assertTrue(ValidApi.class.isInstance(client));
	}

	@Test
	void getClient_withRipClientConfig_returnsUsableProxy() {
		ValidApi client = RIP.getClient(ValidApi.class, RipClientConfig.builder().connectTimeoutMillis(1_000).build());
		assertNotNull(client);
		assertTrue(ValidApi.class.isInstance(client));
	}

	@Test
	void getClient_withRipClientConfigOnInvalidInterface_throwsRestInPeaceException() {
		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(InvalidApi.class, RipClientConfig.builder().build()));
		assertTrue(exception.getMessage().contains("failed during validation"));
	}

	@Test
	void getClient_invalidInterface_throwsRestInPeaceException() {
		RestInPeaceException exception = assertThrows(RestInPeaceException.class, () -> RIP.getClient(InvalidApi.class));
		assertTrue(exception.getMessage().contains("failed during validation"));
	}

	@Test
	void toString_doesNotThrow() {
		ValidApi client = RIP.getClient(ValidApi.class);
		assertDoesNotThrow(client::toString);
		assertTrue(client.toString().contains(ValidApi.class.getName()));
	}

	@Test
	void hashCode_doesNotThrow() {
		ValidApi client = RIP.getClient(ValidApi.class);
		assertDoesNotThrow(client::hashCode);
	}

	@Test
	void equals_comparesByIdentity() {
		ValidApi client = RIP.getClient(ValidApi.class);
		ValidApi otherClient = RIP.getClient(ValidApi.class);

		assertTrue(client.equals(client));
		assertFalse(client.equals(otherClient));
		assertFalse(client.equals(null));
		assertEquals(client.hashCode(), client.hashCode());
	}

}
