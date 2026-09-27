package com.shri.restinpeace.reactor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Mixes one ordinary, codegen-supported method with one real
 * {@code Mono<T>}-returning method and one real {@code Flux<T>}-returning
 * method on the same interface - deliberately top-level (not nested), since
 * {@code RestClientProcessor} doesn't support a nested/private interface yet
 * (see {@code core}'s own {@code GeneratedApi}). Unlike {@link ReactorTestApi}/
 * {@link FluxTestApi} (every method there returns {@code Mono<T>}/
 * {@code Flux<T>}, so the whole interface already falls back to the
 * reflective proxy in its entirety, proving nothing about *partial*
 * fallback), this interface proves {@code RestClientProcessor} still
 * generates a real {@code _RipImpl} for {@link #getOrder} while
 * {@link #getOrderReactively}/{@link #listOrdersReactively} each delegate to
 * a lazily-built reflective sub-proxy - the same "partial fallback, not
 * whole-interface fallback" guarantee {@code core}'s own
 * {@code GeneratedApiWithPartialSupport} already proves for a parameterized
 * {@code List<T>} (E9), now locked in for both the {@code Mono<T>} and
 * {@code Flux<T>} shapes too
 * ({@code docs/design/reactor-call-adapter.md} §8.2, §14 chunk 5).
 */
@RestClient
public interface MixedSupportedMonoAndFluxTestApi {

	@GET("/orders/{id}")
	String getOrder(@PathParam("id") String id);

	@GET("/orders/{id}")
	Mono<String> getOrderReactively(@PathParam("id") String id);

	@GET("/orders")
	Flux<String> listOrdersReactively();

}
