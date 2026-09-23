package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import com.shri.restinpeace.CallAdapter;

/**
 * Unit-level tests against {@link MonoCallAdapterFactory} directly - no
 * MockRestServer/HTTP dispatch involved - specifically for
 * {@link MonoCallAdapterFactory#get(Method)}'s claiming logic for a
 * non-{@code Mono}-returning method, and for the {@code CompletionException}
 * unwrapping branches in {@code adapt(...)} that a MockRestServer-based
 * integration test can't reliably steer: RIP's own async pipeline completes
 * a failed future via {@code completeExceptionally} directly, never through
 * a dependent stage that would actually produce a {@code CompletionException}
 * wrapper, so exercising those branches needs a hand-built
 * {@link CompletableFuture} instead.
 */
class MonoCallAdapterFactoryTest {

	private final MonoCallAdapterFactory factory = new MonoCallAdapterFactory();

	@Test
	void get_declinesANonMonoReturnType() throws NoSuchMethodException {
		Method method = String.class.getMethod("trim");
		assertFalse(factory.get(method).isPresent());
	}

	@Test
	void adapt_unwrapsACompletionExceptionsCause() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Mono<Object> mono = adapt(delegate);

		IllegalStateException cause = new IllegalStateException("boom");
		delegate.completeExceptionally(new CompletionException(cause));

		StepVerifier.create(mono).expectErrorMatches(error -> error == cause).verify();
	}

	@Test
	void adapt_propagatesACompletionExceptionWithNoCauseAsIs() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Mono<Object> mono = adapt(delegate);

		CompletionException noCause = new CompletionException(null);
		delegate.completeExceptionally(noCause);

		StepVerifier.create(mono).expectErrorMatches(error -> error == noCause).verify();
	}

	@Test
	void adapt_propagatesANonCompletionExceptionErrorAsIs() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Mono<Object> mono = adapt(delegate);

		IllegalStateException error = new IllegalStateException("boom");
		delegate.completeExceptionally(error);

		StepVerifier.create(mono).expectErrorMatches(e -> e == error).verify();
	}

	@SuppressWarnings("unchecked")
	private Mono<Object> adapt(CompletableFuture<Object> delegate) throws NoSuchMethodException {
		Method method = ReactorTestApi.class.getMethod("getOrder", String.class);
		Optional<CallAdapter<?>> adapter = factory.get(method);
		assertTrue(adapter.isPresent());
		return (Mono<Object>) adapter.get().adapt(delegate);
	}

}
