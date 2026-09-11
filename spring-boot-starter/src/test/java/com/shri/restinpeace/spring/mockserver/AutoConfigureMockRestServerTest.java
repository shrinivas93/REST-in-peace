package com.shri.restinpeace.spring.mockserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.mock.MockResponse;
import com.shri.restinpeace.mock.MockRestServer;
import com.shri.restinpeace.spring.AutoConfigureMockRestServer;
import com.shri.restinpeace.spring.EnableRestInPeaceClients;

/**
 * Proves {@link AutoConfigureMockRestServer} actually redirects a registered
 * client away from its real, resolved base URL: {@code UserApi} below
 * declares a real {@code @BaseUrl} pointing at {@code localhost:1} - a port
 * nothing listens on - so the call in
 * {@code getUser_isRoutedToMockRestServerInstead} could only ever succeed
 * if {@link AutoConfigureMockRestServer}'s override actually replaced that
 * base URL with the running {@link MockRestServer}'s own, before the client
 * was ever constructed.
 *
 * <p>
 * Declared in its own dedicated {@code .mockserver} sub-package, scanned on
 * its own - see {@code EnableRestInPeaceClientsTest}'s own javadoc for why
 * sharing a scanned package with another test's {@code @RestClient}
 * interface is unsafe.
 */
class AutoConfigureMockRestServerTest {

	@RestClient
	@BaseUrl("http://localhost:1")
	interface UserApi {
		@GET("/users/{id}")
		String getUser(@PathParam("id") String id);
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.mockserver")
	@AutoConfigureMockRestServer
	static class TestConfig {
	}

	@Test
	void getUser_isRoutedToMockRestServerInstead() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/users/{id}", MockResponse.ok("Shrinivas"));

			UserApi userApi = context.getBean(UserApi.class);

			assertEquals("Shrinivas", userApi.getUser("42"));
			assertEquals(1, server.countOf(HTTPMethod.GET, "/users/{id}"));
		}
	}

}
