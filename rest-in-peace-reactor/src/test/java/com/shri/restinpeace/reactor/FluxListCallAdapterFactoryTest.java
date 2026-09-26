package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import com.shri.restinpeace.CallAdapter;

/**
 * Unit-level tests against {@link FluxListCallAdapterFactory} directly - no
 * MockRestServer/HTTP dispatch involved - for the same two things
 * {@link FluxListCallAdapterIntegrationTest} can't reliably exercise:
 * {@link FluxListCallAdapterFactory#get(Method)}'s decline path for a
 * non-{@code Flux} return type, and the {@code CompletionException}
 * unwrapping branches in {@code adapt(...)}, since RIP's own async pipeline
 * always completes a failed future via {@code completeExceptionally}
 * directly rather than through a dependent stage that would actually
 * produce a wrapped exception - the same reasoning
 * {@code MonoCallAdapterFactoryTest} already established for {@code Mono<T>}.
 */
class FluxListCallAdapterFactoryTest {

	private final FluxListCallAdapterFactory factory = new FluxListCallAdapterFactory();

	@Test
	void get_declinesANonFluxReturnType() throws NoSuchMethodException {
		Method method = String.class.getMethod("trim");
		assertFalse(factory.get(method).isPresent());
	}

	@Test
	void adapt_unwrapsACompletionExceptionsCause() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Flux<Object> flux = adapt(delegate);

		IllegalStateException cause = new IllegalStateException("boom");
		delegate.completeExceptionally(new CompletionException(cause));

		StepVerifier.create(flux).expectErrorMatches(error -> error == cause).verify();
	}

	@Test
	void adapt_propagatesACompletionExceptionWithNoCauseAsIs() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Flux<Object> flux = adapt(delegate);

		CompletionException noCause = new CompletionException(null);
		delegate.completeExceptionally(noCause);

		StepVerifier.create(flux).expectErrorMatches(error -> error == noCause).verify();
	}

	@Test
	void adapt_propagatesANonCompletionExceptionErrorAsIs() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Flux<Object> flux = adapt(delegate);

		IllegalStateException error = new IllegalStateException("boom");
		delegate.completeExceptionally(error);

		StepVerifier.create(flux).expectErrorMatches(e -> e == error).verify();
	}

	/**
	 * {@link FluxListCallAdapterIntegrationTest}'s own disposal test proves
	 * downstream stops seeing signals after disposal - true regardless of
	 * this wiring, since a cancelled {@code Mono.create} sink drops a late
	 * {@code success}/{@code error} on its own. This test instead asserts
	 * directly on {@code delegate} itself (never completed here) that
	 * disposing genuinely propagates to {@code CompletableFuture#cancel(true)}
	 * - the actual behavior {@code sink.onCancel(...)} exists to provide.
	 */
	@Test
	void disposing_cancelsTheUnderlyingDelegateFuture() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Flux<Object> flux = adapt(delegate);

		flux.subscribe().dispose();

		assertTrue(delegate.isCancelled());
	}

	@SuppressWarnings("unchecked")
	private Flux<Object> adapt(CompletableFuture<Object> delegate) throws NoSuchMethodException {
		Method method = FluxTestApi.class.getMethod("listOrders");
		Optional<CallAdapter<?>> adapter = factory.get(method);
		assertTrue(adapter.isPresent());
		return (Flux<Object>) adapter.get().adapt(delegate);
	}

}
