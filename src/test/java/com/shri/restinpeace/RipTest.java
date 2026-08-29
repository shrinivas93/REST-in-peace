package com.shri.restinpeace;

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
	void getClient_invalidInterface_throwsRestInPeaceException() {
		RestInPeaceException exception = assertThrows(RestInPeaceException.class, () -> RIP.getClient(InvalidApi.class));
		assertTrue(exception.getMessage().contains("failed during validation"));
	}

}
