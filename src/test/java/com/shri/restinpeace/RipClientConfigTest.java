package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RipClientConfigTest {

	@Test
	void build_withNoSettings_leavesEverythingUnset() {
		RipClientConfig config = RipClientConfig.builder().build();

		assertNull(config.getBaseUrl());
		assertNull(config.getConnectTimeoutMillis());
		assertNull(config.getReadTimeoutMillis());
		assertNull(config.getProxyHost());
	}

	@Test
	void build_withAllSettings_returnsThem() {
		RipClientConfig config = RipClientConfig.builder().baseUrl("https://api.example.com").connectTimeoutMillis(1_000)
				.readTimeoutMillis(5_000).proxy("proxy.example.com", 8080, "user", "pass").build();

		assertEquals("https://api.example.com", config.getBaseUrl());
		assertEquals(1_000, config.getConnectTimeoutMillis());
		assertEquals(5_000, config.getReadTimeoutMillis());
		assertEquals("proxy.example.com", config.getProxyHost());
		assertEquals(8080, config.getProxyPort());
		assertEquals("user", config.getProxyUsername());
		assertEquals("pass", config.getProxyPassword());
	}

	@Test
	void connectTimeoutMillis_negative_throws() {
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().connectTimeoutMillis(-1));
	}

	@Test
	void readTimeoutMillis_negative_throws() {
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().readTimeoutMillis(-1));
	}

	@Test
	void proxy_withoutCredentials_leavesUsernameAndPasswordNull() {
		RipClientConfig config = RipClientConfig.builder().proxy("proxy.example.com", 8080).build();

		assertEquals("proxy.example.com", config.getProxyHost());
		assertEquals(8080, config.getProxyPort());
		assertNull(config.getProxyUsername());
		assertNull(config.getProxyPassword());
	}

}
