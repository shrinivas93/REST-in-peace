package com.shri.restinpeace.mock;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * A JUnit 5 extension that starts one {@link MockRestServer} for a whole test
 * class and {@link MockRestServer#reset() resets} it before each test -
 * register it with {@code @ExtendWith} and receive the server as a test (or
 * {@code @BeforeEach}) method parameter, instead of wiring up
 * {@code @BeforeAll}/{@code @AfterAll}/{@code @BeforeEach} boilerplate by
 * hand.
 *
 * <pre>
 * {@literal @}ExtendWith(MockRestServerExtension.class)
 * class OrderServiceTest {
 *
 *     private OrderApi api;
 *
 *     {@literal @}BeforeEach
 *     void setUp(MockRestServer server) {
 *         api = RIP.getClient(OrderApi.class, server.baseUrl());
 *     }
 *
 *     {@literal @}Test
 *     void placesAnOrder(MockRestServer server) {
 *         server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));
 *         ...
 *     }
 * }
 * </pre>
 *
 * <p>
 * Because the server (and so its {@link MockRestServer#baseUrl()}) is the
 * same object for every test method in the class, a {@code @RestClient}
 * built from it - like {@code api} above - can be built once in
 * {@code @BeforeEach} instead of needing the fresh base URL a new server per
 * test would otherwise force.
 *
 * <p>
 * <b>Registration:</b> the server starts in a {@code BeforeAllCallback},
 * which JUnit only invokes for an extension it can discover before any test
 * instance exists - that means {@code @ExtendWith(MockRestServerExtension.class)}
 * on the class (as above), or a {@code static} field annotated
 * {@code @RegisterExtension}. Registering it on a non-static
 * {@code @RegisterExtension} field silently skips server startup and
 * shutdown, since JUnit can't read an instance field before an instance
 * exists - prefer {@code @ExtendWith} unless a test specifically needs to
 * keep a reference to the extension itself via {@link #getServer()}.
 *
 * <p>
 * <b>Isolation:</b> every test method in the class shares the same server -
 * {@link MockRestServer#reset()} between tests clears routes, queued
 * responses, and recorded requests, but the server itself, and everything
 * built against its base URL, is not safe for concurrent use. Don't combine
 * this extension with parallel test execution within the same class.
 *
 * <p>
 * Requires {@code junit-jupiter-api} on the consumer's own test classpath -
 * a {@code provided}-scope dependency of this library, not a transitive
 * runtime one, so using this class is opt-in and costs a consumer who
 * doesn't nothing.
 */
public final class MockRestServerExtension
		implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, ParameterResolver {

	private MockRestServer server;

	/** Creates the extension - register it via {@code @ExtendWith}, it needs no arguments. */
	public MockRestServerExtension() {
	}

	/**
	 * Returns the server for the current test class - only meaningful once
	 * {@code beforeAll} has run. Prefer receiving {@link MockRestServer} as
	 * a parameter (see the class javadoc) over calling this directly; it
	 * exists for the {@code static @RegisterExtension} field registration
	 * style, where no test instance is available to resolve a parameter
	 * against (e.g. a static {@code @BeforeAll} method).
	 *
	 * @return this class's server
	 */
	public MockRestServer getServer() {
		return server;
	}

	@Override
	public void beforeAll(ExtensionContext context) {
		server = MockRestServer.start();
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		server.reset();
	}

	@Override
	public void afterAll(ExtensionContext context) {
		server.close();
	}

	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
			throws ParameterResolutionException {
		return parameterContext.getParameter().getType() == MockRestServer.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
			throws ParameterResolutionException {
		return server;
	}

}
