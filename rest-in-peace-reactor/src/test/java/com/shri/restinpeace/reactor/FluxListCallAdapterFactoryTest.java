package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import com.shri.restinpeace.CallAdapter;

/**
 * Unit-level tests against {@link FluxListCallAdapterFactory} directly - no
 * MockRestServer/HTTP dispatch involved - for three things
 * {@link FluxListCallAdapterIntegrationTest} can't reliably exercise:
 * {@link FluxListCallAdapterFactory#get(Method)}'s decline paths (a
 * non-{@code Flux} return type, a {@code @Paginated} method, a raw or
 * otherwise-unresolvable item type), the {@code CompletionException}
 * unwrapping branches in {@code adapt(...)} - since RIP's own async pipeline
 * always completes a failed future via {@code completeExceptionally}
 * directly rather than through a dependent stage that would actually
 * produce a wrapped exception, the same reasoning
 * {@code MonoCallAdapterFactoryTest} already established for {@code Mono<T>}
 * - and disposal/cancellation propagation to the underlying delegate
 * future, asserted directly on the future itself rather than through a
 * downstream signal a cancelled sink would drop on its own regardless of
 * this wiring.
 */
class FluxListCallAdapterFactoryTest {

	private final FluxListCallAdapterFactory factory = new FluxListCallAdapterFactory();

	@Test
	void get_declinesANonFluxReturnType() throws NoSuchMethodException {
		Method method = String.class.getMethod("trim");
		assertFalse(factory.get(method).isPresent());
	}

	/**
	 * A wildcard item type ({@code Flux<?>}) would otherwise be silently
	 * hidden inside the synthetic {@code List<?>} this factory wraps it in,
	 * passing {@code ReflectiveRestClientValidator}'s top-level
	 * Class/ParameterizedType check clean and failing (or misdecoding) at
	 * Gson's own generic decode instead - {@link #get} must decline this
	 * shape outright instead.
	 */
	@Test
	void get_declinesAWildcardItemType() throws NoSuchMethodException {
		Method method = WildcardFluxApi.class.getMethod("wildcardOrders");
		assertFalse(factory.get(method).isPresent());
	}

	/**
	 * Same gap as {@link #get_declinesAWildcardItemType}, but for an
	 * unresolved type variable - a generic method's own {@code <T> Flux<T>}.
	 */
	@Test
	void get_declinesAnUnresolvedTypeVariableItemType() throws NoSuchMethodException {
		Method method = WildcardFluxApi.class.getMethod("genericOrders");
		assertFalse(factory.get(method).isPresent());
	}

	/**
	 * A {@code @Paginated} method returning {@code Flux<T>} claims the same
	 * declared type this factory otherwise claims for its own, non-paginated
	 * flavor (§7.1/§7.3's disambiguation) - {@code @Paginated}'s presence
	 * must make {@link #get} decline outright, leaving the method entirely to
	 * {@link FluxPaginatedCallAdapterFactory} instead of ever racing it.
	 */
	@Test
	void get_declinesAPaginatedMethod() throws NoSuchMethodException {
		Method method = FluxTestApi.class.getMethod("fluxOrders", String.class);
		assertFalse(factory.get(method).isPresent());
	}

	/**
	 * A raw {@code Flux} (no type parameter at all) has no item type to
	 * extract - {@link #get} must decline rather than throw extracting one,
	 * same reasoning as {@code MonoCallAdapterFactory}'s own raw-{@code Mono}
	 * handling: letting {@code ReflectiveRestClientValidator} report a clean,
	 * collected "raw type" error instead of aborting validation for the whole
	 * interface.
	 */
	@Test
	void get_declinesARawFluxReturnType() throws NoSuchMethodException {
		Method method = WildcardFluxApi.class.getMethod("rawOrders");
		assertFalse(factory.get(method).isPresent());
	}

	private interface WildcardFluxApi {

		Flux<?> wildcardOrders();

		<T> Flux<T> genericOrders();

		@SuppressWarnings("rawtypes")
		Flux rawOrders();

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

	/**
	 * The same cross-subscriber regression coverage
	 * {@code MonoCallAdapterFactoryTest} has for its own shared-delegate
	 * pattern - both factories now go through the same {@link FutureMono}.
	 */
	@Test
	void oneSubscriberDisposing_doesNotCancelAnotherStillActiveSubscribersDelegate() throws NoSuchMethodException {
		CompletableFuture<Object> delegate = new CompletableFuture<>();
		Flux<Object> flux = adapt(delegate);

		Disposable first = flux.subscribe();
		Disposable second = flux.subscribe();

		first.dispose();
		assertFalse(delegate.isCancelled());

		second.dispose();
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
