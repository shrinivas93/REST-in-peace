package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.mock.MockRestServer;
import com.shri.restinpeace.mock.MockResponse;

/**
 * Regression test for {@code docs/design/reactor-call-adapter.md} §8.2 (§14's
 * chunk 5): {@code RestClientProcessor.toSupportedMethodModel} disqualifying a
 * {@code Mono<T>}/{@code Flux<T>}-returning method from codegen was already
 * correct before this test existed - a {@code Mono<T>}/{@code Flux<T>}'s type
 * argument falls into the exact same "some other generic type isn't
 * supported" branch a parameterized {@code List<T>} already does (E9) - but
 * nothing pinned that down against a future {@code RestClientProcessor}
 * refactor accidentally narrowing or widening the disqualification boundary
 * for either shape. Against {@link MixedSupportedMonoAndFluxTestApi}, this
 * proves all three things that refactor could silently break, for both
 * {@code Mono<T>} and {@code Flux<T>}: (a) the reactive method lands in
 * {@code fallbackMethods}, not {@code methods}; (b) the generated
 * {@code _RipImpl} class still compiles and correctly generates the ordinary
 * method; (c) {@code RIP.getClient(...)} against that interface answers each
 * reactive method correctly via the reflective proxy while the ordinary
 * method still uses the generated implementation.
 */
class CompileTimeCodegenFallbackTest {

	private MockRestServer server;
	private MixedSupportedMonoAndFluxTestApi api;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
		RestInPeaceReactor.register();
		api = RIP.getClient(MixedSupportedMonoAndFluxTestApi.class, server.baseUrl());
	}

	@AfterEach
	void tearDown() {
		server.close();
		RestInPeaceReactor.unregister();
	}

	@Test
	void getClient_returnsTheCompileTimeGeneratedImplementationDespiteTheReactiveMethods() {
		// (a)+(b): if getOrderReactively's Mono<T> or listOrdersReactively's
		// Flux<T> return type had wrongly been accepted into `methods` instead of
		// `fallbackMethods`, RestClientProcessor would have generated broken
		// source for it (it has no notion of how to decode/wrap either type) and
		// this interface would fail to compile at all, let alone reach
		// RIP.getClient(...) here as a real _RipImpl.
		assertTrue(api.getClass().getName().endsWith("_RipImpl"),
				"Expected the compile-time-generated implementation despite the Mono<T>/Flux<T> methods, got "
						+ api.getClass().getName());
	}

	@Test
	void getOrder_theSupportedMethod_isServedByTheClient() {
		// This interface has exactly one codegen-supported method: if a future
		// regression wrongly disqualified getOrder itself alongside the reactive
		// ones, `methods` would be empty and RestClientProcessor would skip
		// codegen for the whole interface (see toSupportedMethodModel's own
		// "if (methods.isEmpty())" guard) - a case the test above already catches
		// via its _RipImpl assertion. So a correct value here, combined with that
		// one, does pin down that getOrder specifically is served through the
		// generated implementation, not just some codegen'd class.
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		assertEquals("shipped", api.getOrder("42"));
	}

	@Test
	void getOrderReactively_theMonoMethod_worksThroughTheReflectiveFallback() {
		// (c): dispatched via the lazily-built reflective sub-proxy
		// (appendReflectiveFallbackAccessor), which is where MonoCallAdapterFactory
		// resolution actually lives - a generated method has no way to decode this
		// return type itself.
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		StepVerifier.create(api.getOrderReactively("42")).expectNext("shipped").verifyComplete();
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders/{id}"));
	}

	@Test
	void listOrdersReactively_theFluxMethod_worksThroughTheReflectiveFallback() {
		// Same reasoning as the Mono case above, but for FluxListCallAdapterFactory
		// - proving the disqualification/fallback mechanism holds for Flux<T> too,
		// not just Mono<T>, since both reach the same generic-type-argument check
		// in toSupportedMethodModel but are claimed by different factories at
		// runtime.
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[\"shipped\",\"pending\"]"));

		StepVerifier.create(api.listOrdersReactively()).expectNext("shipped").expectNext("pending").verifyComplete();
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders"));
	}

}
