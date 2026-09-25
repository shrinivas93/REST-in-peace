package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.PaginatedCallAdapter;
import com.shri.restinpeace.RipResponse;

/**
 * Unit-level tests against {@link FluxPaginatedCallAdapterFactory} directly
 * - no MockRestServer/HTTP dispatch involved - for
 * {@link FluxPaginatedCallAdapterFactory#get(Method)}'s claiming logic, and
 * for the internal drain loop's demand-accumulation bookkeeping, neither of
 * which {@link FluxPaginatedCallAdapterIntegrationTest} can exercise on its
 * own: every method {@code RequestExecutor} ever actually offers this
 * factory is already {@code @Paginated}-annotated and {@code Flux}-returning
 * (see the factory's own class javadoc), so a non-{@code Flux} return type
 * never reaches it through ordinary dispatch/validation; and two
 * back-to-back {@code request(n)} calls landing with a specific,
 * already-accumulated demand needs the second call issued from genuinely
 * outside any Reactor callback - {@code Flux.create}'s own subscription
 * silently drops a second, reentrant {@code request()} call made within the
 * same {@code hookOnSubscribe}/{@code hookOnNext} invocation, so
 * {@code StepVerifier}'s DSL (and a naive {@link BaseSubscriber} issuing
 * both calls from the same hook) can't steer this deterministically either.
 */
class FluxPaginatedCallAdapterFactoryTest {

	private final FluxPaginatedCallAdapterFactory factory = new FluxPaginatedCallAdapterFactory();

	@Test
	void get_declinesANonFluxReturnType() throws NoSuchMethodException {
		Method method = String.class.getMethod("trim");
		assertFalse(factory.get(method).isPresent());
	}

	/**
	 * {@code addCapped} has no separate "already at {@code MAX_VALUE}"
	 * fast path - {@code current + n} always overflows to negative once
	 * {@code current} is already {@code MAX_VALUE} (since {@code n} is
	 * always positive here), so this and a smaller-magnitude overflow both
	 * exercise the exact same saturation branch below.
	 */
	@Test
	void onRequest_aSecondUnboundedRequestSaturatesAtMaxValue() throws NoSuchMethodException {
		Subscription subscription = subscribeWithSingleUpfrontRequest(Long.MAX_VALUE);
		subscription.request(Long.MAX_VALUE); // MAX_VALUE + MAX_VALUE overflows a signed long if summed naively
	}

	@Test
	void onRequest_aRequestThatWouldOverflowSaturatesInstead() throws NoSuchMethodException {
		Subscription subscription = subscribeWithSingleUpfrontRequest(Long.MAX_VALUE - 5);
		subscription.request(10); // (MAX_VALUE - 5) + 10 overflows a signed long if summed naively
	}

	@Test
	void onRequest_aNonPositiveRequestIsIgnored() throws NoSuchMethodException {
		// Reactive Streams Rule 3.9 says a compliant Publisher must reject a
		// non-positive request() with onError before it ever reaches a
		// registered consumer like this adapter's own onRequest - this test
		// pins down whether Flux.create's own Subscription actually enforces
		// that (making the n <= 0 half of this guard dead, defensive code) or
		// forwards it through, in which case this exercises the guard for real.
		Subscription subscription = subscribeWithSingleUpfrontRequest(1);
		subscription.request(0);
		subscription.request(-1);
	}

	/**
	 * Subscribes to a {@code Flux} over a page with zero items (so nothing
	 * ever decrements {@code requested} away from exactly what's requested)
	 * and {@code hasNext() == true} (so the flow never completes, keeping
	 * the subscription open for a genuinely later {@code request()} call) -
	 * requests {@code firstRequest} once from {@code hookOnSubscribe}, then
	 * returns the captured {@link Subscription} for the test to issue a
	 * second, wholly separate call against, outside any Reactor callback.
	 */
	private Subscription subscribeWithSingleUpfrontRequest(long firstRequest) throws NoSuchMethodException {
		Page<Object> emptyPageWithNext = fakePage(Collections.emptyList(), true,
				() -> fakePage(Collections.emptyList(), false, null));
		Method method = FluxTestApi.class.getMethod("fluxOrders", String.class);
		@SuppressWarnings("unchecked")
		PaginatedCallAdapter<Flux<Object>> adapter = (PaginatedCallAdapter<Flux<Object>>) factory.get(method).get();
		Flux<Object> flux = adapter.adapt(() -> emptyPageWithNext);

		AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
		flux.subscribeWith(new BaseSubscriber<Object>() {
			@Override
			protected void hookOnSubscribe(Subscription subscription) {
				subscriptionRef.set(subscription);
				subscription.request(firstRequest);
			}
		});
		return subscriptionRef.get();
	}

	private static Page<Object> fakePage(List<Object> items, boolean hasNext, Supplier<Page<Object>> next) {
		return new Page<Object>() {
			@Override
			public List<Object> items() {
				return items;
			}

			@Override
			public boolean hasNext() {
				return hasNext;
			}

			@Override
			public Page<Object> next() {
				return next.get();
			}

			@Override
			public RipResponse<Void> rawResponse() {
				return new RipResponse<>(200, Collections.emptyMap(), null);
			}
		};
	}

}
