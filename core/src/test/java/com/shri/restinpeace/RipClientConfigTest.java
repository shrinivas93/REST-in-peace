package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;
import kong.unirest.ObjectMapper;

import com.shri.restinpeace.cache.InMemoryCache;
import com.shri.restinpeace.interceptor.RequestInterceptor;

class RipClientConfigTest {

	@Test
	void build_withNoSettings_leavesEverythingUnset() {
		RipClientConfig config = RipClientConfig.builder().build();

		assertNull(config.getBaseUrl());
		assertNull(config.getConnectTimeoutMillis());
		assertNull(config.getReadTimeoutMillis());
		assertNull(config.getProxyHost());
		assertNull(config.getObjectMapper());
		assertNull(config.getCache());
		assertNull(config.getCacheKeyIncludesQueryString());
		assertNull(config.getNegativeCacheTtlMillis());
		assertNull(config.getRetryBudgetMaxRetries());
		assertNull(config.getRetryBudgetWindowMillis());
		assertTrue(config.getInterceptors().isEmpty());
		assertNull(config.getRetry());
	}

	@Test
	void build_withAllSettings_returnsThem() {
		ObjectMapper objectMapper = new JsonObjectMapper();
		InMemoryCache cache = new InMemoryCache();
		RequestInterceptor interceptor = new RequestInterceptor() {
		};
		RetryConfig retry = RetryConfig.builder().times(5).build();
		RipClientConfig config = RipClientConfig.builder().baseUrl("https://api.example.com").connectTimeoutMillis(1_000)
				.readTimeoutMillis(5_000).proxy("proxy.example.com", 8080, "user", "pass").objectMapper(objectMapper)
				.cache(cache).cacheKeyIncludesQueryString(false).negativeCacheTtlMillis(30_000)
				.retryBudget(5, 60_000).interceptors(Collections.singletonList(interceptor)).retry(retry).build();

		assertEquals("https://api.example.com", config.getBaseUrl());
		assertEquals(1_000, config.getConnectTimeoutMillis());
		assertEquals(5_000, config.getReadTimeoutMillis());
		assertEquals("proxy.example.com", config.getProxyHost());
		assertEquals(8080, config.getProxyPort());
		assertEquals("user", config.getProxyUsername());
		assertEquals("pass", config.getProxyPassword());
		assertEquals(objectMapper, config.getObjectMapper());
		assertEquals(cache, config.getCache());
		assertEquals(Boolean.FALSE, config.getCacheKeyIncludesQueryString());
		assertEquals(30_000L, config.getNegativeCacheTtlMillis());
		assertEquals(5, config.getRetryBudgetMaxRetries());
		assertEquals(60_000L, config.getRetryBudgetWindowMillis());
		assertEquals(Collections.singletonList(interceptor), config.getInterceptors());
		assertEquals(retry, config.getRetry());
	}

	@Test
	void interceptors_null_normalizesToEmptyList() {
		RipClientConfig config = RipClientConfig.builder().interceptors(null).build();

		assertTrue(config.getInterceptors().isEmpty());
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
	void negativeCacheTtlMillis_notPositive_throws() {
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().negativeCacheTtlMillis(0));
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().negativeCacheTtlMillis(-1));
	}

	@Test
	void retryBudget_notPositive_throws() {
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().retryBudget(0, 60_000));
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().retryBudget(-1, 60_000));
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().retryBudget(5, 0));
		assertThrows(IllegalArgumentException.class, () -> RipClientConfig.builder().retryBudget(5, -1));
	}

	@Test
	void proxy_withoutCredentials_leavesUsernameAndPasswordNull() {
		RipClientConfig config = RipClientConfig.builder().proxy("proxy.example.com", 8080).build();

		assertEquals("proxy.example.com", config.getProxyHost());
		assertEquals(8080, config.getProxyPort());
		assertNull(config.getProxyUsername());
		assertNull(config.getProxyPassword());
	}

	@Test
	void circuitBreaker_configAndProvider_areMutuallyExclusive_lastCallWins() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().build();
		CircuitBreakerProvider provider = new CircuitBreakerProvider() {
			public boolean tryAcquirePermission() {
				return true;
			}

			public void onSuccess(long durationNanos, int statusCode) {
			}

			public void onError(long durationNanos, Throwable t) {
			}
		};

		RipClientConfig configThenProvider = RipClientConfig.builder().circuitBreaker(config).circuitBreaker(provider)
				.build();
		assertNull(configThenProvider.getCircuitBreaker());
		assertEquals(provider, configThenProvider.getCircuitBreakerProvider());

		RipClientConfig providerThenConfig = RipClientConfig.builder().circuitBreaker(provider).circuitBreaker(config)
				.build();
		assertNull(providerThenConfig.getCircuitBreakerProvider());
		assertEquals(config, providerThenConfig.getCircuitBreaker());
	}

	@Test
	void bulkhead_configAndProvider_areMutuallyExclusive_lastCallWins() {
		BulkheadConfig config = BulkheadConfig.builder().build();
		BulkheadProvider provider = new BulkheadProvider() {
			public boolean tryAcquirePermission() {
				return true;
			}

			public void onComplete() {
			}
		};

		RipClientConfig configThenProvider = RipClientConfig.builder().bulkhead(config).bulkhead(provider).build();
		assertNull(configThenProvider.getBulkhead());
		assertEquals(provider, configThenProvider.getBulkheadProvider());

		RipClientConfig providerThenConfig = RipClientConfig.builder().bulkhead(provider).bulkhead(config).build();
		assertNull(providerThenConfig.getBulkheadProvider());
		assertEquals(config, providerThenConfig.getBulkhead());
	}

}
