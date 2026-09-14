package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;

/**
 * Exercises {@link MockRestServerExtension#reportUnhitRoutes()} by driving
 * its {@code beforeAll}/{@code afterAll} lifecycle methods directly, rather
 * than through a real JUnit 5 test-class run: neither method actually reads
 * its {@link org.junit.jupiter.api.extension.ExtensionContext} argument, so
 * {@code null} stands in for it here without needing a full stub.
 */
class MockRestServerExtensionUnhitRoutesTest {

	private final PrintStream originalErr = System.err;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
	}

	@AfterEach
	void restoreSystemErr() {
		System.setErr(originalErr);
	}

	@Test
	void afterAll_withReportingOn_printsEveryUnhitRoute() {
		MockRestServerExtension extension = new MockRestServerExtension().reportUnhitRoutes();
		extension.beforeAll(null);
		extension.getServer().on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));

		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setErr(new PrintStream(captured));

		extension.afterAll(null);

		String output = captured.toString();
		assertTrue(output.contains("1 registered route(s) were never hit"));
		assertTrue(output.contains("GET /orders/{id}"));
	}

	@Test
	void afterAll_withReportingOn_printsNothingWhenEveryRouteWasHit() {
		MockRestServerExtension extension = new MockRestServerExtension().reportUnhitRoutes();
		extension.beforeAll(null);
		extension.getServer().on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, extension.getServer().baseUrl());
		api.getOrder("abc123", "false");

		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setErr(new PrintStream(captured));

		extension.afterAll(null);

		assertTrue(captured.toString().isEmpty());
	}

	@Test
	void afterAll_withReportingOff_printsNothingEvenWithAnUnhitRoute() {
		MockRestServerExtension extension = new MockRestServerExtension();
		extension.beforeAll(null);
		extension.getServer().on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));

		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setErr(new PrintStream(captured));

		extension.afterAll(null);

		assertFalse(captured.toString().contains("unhit"));
	}

	@Test
	void reportUnhitRoutes_returnsTheSameInstanceForChaining() {
		MockRestServerExtension extension = new MockRestServerExtension();

		assertTrue(extension == extension.reportUnhitRoutes());
	}

}
