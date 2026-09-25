package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.FluxSink;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.RipResponse;

/**
 * Unit-level tests against {@link FluxPaginatedCallAdapterFactory} directly -
 * no MockRestServer/HTTP dispatch involved - for
 * {@link FluxPaginatedCallAdapterFactory#get(Method)}'s claiming logic, and
 * for the private {@code PageDrain.addCapped}/{@code onRequest} demand
 * bookkeeping, which {@link FluxPaginatedCallAdapterIntegrationTest} can't
 * exercise deterministically: driving two back-to-back demand updates
 * through the real {@code Flux} subscription races the async
 * {@code boundedElastic} page fetch that same demand triggers - if that
 * fetch's eventual {@code sink.complete()} lands before the test's second
 * call, the subscription is already terminated and silently drops it,
 * so nothing is actually proven either way. These tests instead invoke
 * {@code PageDrain}'s package-private arithmetic directly via reflection,
 * bypassing the {@code Flux} subscription (and that race) entirely, and
 * assert on the resulting {@code requested} value.
 */
class FluxPaginatedCallAdapterFactoryTest {

	private final FluxPaginatedCallAdapterFactory factory = new FluxPaginatedCallAdapterFactory();

	@Test
	void get_declinesANonFluxReturnType() throws NoSuchMethodException {
		Method method = String.class.getMethod("trim");
		assertFalse(factory.get(method).isPresent());
	}

	/**
	 * No separate "already at {@code MAX_VALUE}" fast path exists in
	 * {@code addCapped} - {@code current + n} always overflows to negative
	 * once {@code current} is already {@code MAX_VALUE} (since {@code n} is
	 * always positive here), so this and a smaller-magnitude overflow both
	 * exercise the exact same saturation branch.
	 */
	@Test
	void addCapped_aSecondUnboundedRequestSaturatesAtMaxValue() throws ReflectiveOperationException {
		Object pageDrain = newPageDrain();
		invokeAddCapped(pageDrain, Long.MAX_VALUE);
		invokeAddCapped(pageDrain, Long.MAX_VALUE); // MAX_VALUE + MAX_VALUE overflows a signed long if summed naively
		assertEquals(Long.MAX_VALUE, readRequested(pageDrain));
	}

	@Test
	void addCapped_aRequestThatWouldOverflowSaturatesInstead() throws ReflectiveOperationException {
		Object pageDrain = newPageDrain();
		invokeAddCapped(pageDrain, Long.MAX_VALUE - 5);
		invokeAddCapped(pageDrain, 10); // (MAX_VALUE - 5) + 10 overflows a signed long if summed naively
		assertEquals(Long.MAX_VALUE, readRequested(pageDrain));
	}

	/**
	 * Reactive Streams Rule 3.9 says a compliant Publisher must reject a
	 * non-positive {@code request()} with {@code onError} before it ever
	 * reaches a registered consumer like this adapter's own
	 * {@code onRequest} - empirically, {@code Flux.create}'s own
	 * subscription does not enforce that and forwards it through, making
	 * the {@code n <= 0} half of {@code onRequest}'s guard genuinely live
	 * code, not unreachable defensive code.
	 */
	@Test
	void onRequest_aNonPositiveRequestIsIgnored() throws ReflectiveOperationException {
		Object pageDrain = newPageDrain();
		invokeOnRequest(pageDrain, 0);
		invokeOnRequest(pageDrain, -1);
		assertEquals(0, readRequested(pageDrain));
	}

	private static Object newPageDrain() throws ReflectiveOperationException {
		Class<?> pageDrainClass = Class
				.forName("com.shri.restinpeace.reactor.FluxPaginatedCallAdapterFactory$FluxPaginatedCallAdapter$PageDrain");
		Constructor<?> constructor = pageDrainClass.getDeclaredConstructor(FluxSink.class, Page.class);
		constructor.setAccessible(true);
		return constructor.newInstance(null, fakePage(Collections.emptyList(), false, null));
	}

	private static void invokeAddCapped(Object pageDrain, long n) throws ReflectiveOperationException {
		Method addCapped = pageDrain.getClass().getDeclaredMethod("addCapped", long.class);
		addCapped.setAccessible(true);
		addCapped.invoke(pageDrain, n);
	}

	private static void invokeOnRequest(Object pageDrain, long n) throws ReflectiveOperationException {
		Method onRequest = pageDrain.getClass().getDeclaredMethod("onRequest", long.class);
		onRequest.setAccessible(true);
		onRequest.invoke(pageDrain, n);
	}

	private static long readRequested(Object pageDrain) throws ReflectiveOperationException {
		Field requestedField = pageDrain.getClass().getDeclaredField("requested");
		requestedField.setAccessible(true);
		return ((AtomicLong) requestedField.get(pageDrain)).get();
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
