package com.shri.restinpeace.mock;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A JUnit 5 extension that starts a fresh {@link MockRestServer} before each
 * test and stops it afterward - register it as a field to skip the
 * {@code @BeforeEach}/{@code @AfterEach} boilerplate
 * {@link MockRestServer#start()}/{@link MockRestServer#close()} otherwise
 * need.
 *
 * <pre>
 * class OrderServiceTest {
 *
 *     {@literal @}RegisterExtension
 *     MockRestServerExtension serverExtension = new MockRestServerExtension();
 *
 *     private OrderApi api;
 *
 *     {@literal @}BeforeEach
 *     void setUp() {
 *         api = RIP.getClient(OrderApi.class, serverExtension.getServer().baseUrl());
 *     }
 *
 *     {@literal @}Test
 *     void placesAnOrder() {
 *         serverExtension.getServer().on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));
 *         ...
 *     }
 * }
 * </pre>
 *
 * <p>
 * Requires {@code junit-jupiter-api} on the consumer's own test classpath -
 * a {@code provided}-scope dependency of this library, not a transitive
 * runtime one, so using this class is opt-in and costs a consumer who
 * doesn't nothing.
 */
public final class MockRestServerExtension implements BeforeEachCallback, AfterEachCallback {

	private MockRestServer server;

	/** Creates the extension - register it via {@code @RegisterExtension}, it needs no arguments. */
	public MockRestServerExtension() {
	}

	/**
	 * Returns the server started for the current test - only meaningful once
	 * {@code beforeEach} has run, i.e. from inside a {@code @Test} method or a
	 * lifecycle callback that runs after it.
	 *
	 * @return the current test's server
	 */
	public MockRestServer getServer() {
		return server;
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		server = MockRestServer.start();
	}

	@Override
	public void afterEach(ExtensionContext context) {
		server.close();
	}

}
