package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;
import reactor.util.context.Context;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.RipResponse;

/**
 * Unit-level tests against {@link FluxPaginatedCallAdapterFactory} directly -
 * no MockRestServer/HTTP dispatch involved - for
 * {@link FluxPaginatedCallAdapterFactory#get(Method)}'s claiming logic, and
 * for {@code PageDrain.addCapped}/{@code onRequest}'s demand bookkeeping,
 * which {@link FluxPaginatedCallAdapterIntegrationTest} can't exercise
 * deterministically: driving two back-to-back demand updates through the
 * real {@code Flux} subscription races the async {@code boundedElastic}
 * page fetch that same demand triggers - if that fetch's eventual
 * {@code sink.complete()} lands before the test's second call, the
 * subscription is already terminated and silently drops it, so nothing is
 * actually proven either way. These tests instead construct a
 * {@code PageDrain} directly and call its (package-private, precisely for
 * this test's benefit - see that class's own comment) {@code addCapped}/
 * {@code onRequest} methods, bypassing the {@code Flux} subscription (and
 * that race) entirely, and assert on the resulting {@code requested} value.
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
	void addCapped_aSecondUnboundedRequestSaturatesAtMaxValue() {
		FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain pageDrain = newPageDrain();
		pageDrain.addCapped(Long.MAX_VALUE);
		pageDrain.addCapped(Long.MAX_VALUE); // MAX_VALUE + MAX_VALUE overflows a signed long if summed naively
		assertEquals(Long.MAX_VALUE, pageDrain.requested.get());
	}

	@Test
	void addCapped_aRequestThatWouldOverflowSaturatesInstead() {
		FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain pageDrain = newPageDrain();
		pageDrain.addCapped(Long.MAX_VALUE - 5);
		pageDrain.addCapped(10); // (MAX_VALUE - 5) + 10 overflows a signed long if summed naively
		assertEquals(Long.MAX_VALUE, pageDrain.requested.get());
	}

	/**
	 * Exercises the {@code n <= 0} half of {@code onRequest}'s guard
	 * directly. Reactive Streams Rule 3.9 says a compliant Publisher must
	 * reject a non-positive {@code request()} with {@code onError} before it
	 * ever reaches a registered consumer like this adapter's own
	 * {@code onRequest} - whether {@code Flux.create}'s own subscription
	 * actually enforces that isn't what this test verifies (it calls
	 * {@code onRequest} straight, with no subscription involved); it only
	 * confirms the guard itself does the right thing if reached, which the
	 * class javadoc's empirical note explains {@code Flux.create} needs.
	 */
	@Test
	void onRequest_aNonPositiveRequestIsIgnored() {
		FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain pageDrain = newPageDrain();
		pageDrain.onRequest(0);
		pageDrain.onRequest(-1);
		assertEquals(0, pageDrain.requested.get());
	}

	/**
	 * Exercises {@code drain()}/{@code drainOnce()}/{@code wip} for real,
	 * unlike every other test in this class: a {@link RecordingSink} whose
	 * {@code next()} reentrantly calls {@code onRequest(1)} on its very first
	 * invocation - reproducing the exact scenario {@code drain()}'s own
	 * javadoc describes ({@link FluxSink#next} synchronously driving a
	 * downstream {@code request(n)} before returning) - the same-thread
	 * reentrancy a plain {@code synchronized drain()} would corrupt {@code
	 * requested} under, and the wip-guarded loop must instead just register
	 * as a missed run to pick up afterwards. Three items are requested one at
	 * a time (the second {@code onRequest(1)} call arriving reentrantly, the
	 * third from the test itself once the first call returns) - if the
	 * reentrant demand were lost, only two items would ever be emitted and
	 * {@code sink.complete()} would never be called; if it corrupted {@code
	 * requested} into going negative or double-counting, either an item would
	 * be emitted twice or {@code addCapped}'s saturation math would be thrown
	 * off - none of which this test lets happen unnoticed.
	 */
	@Test
	void drain_reentrantOnRequestFromSinkNextDoesNotCorruptDemandOrDoubleEmit() {
		RecordingSink sink = new RecordingSink();
		Page<Object> page = fakePage(Arrays.asList("a", "b", "c"), false, null);
		FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain pageDrain = //
				new FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain(sink, page);
		sink.reentrantRequestTarget = pageDrain;

		pageDrain.onRequest(1); // triggers the reentrant onRequest(1) on "a", draining "a" and "b"
		assertEquals(Arrays.asList("a", "b"), sink.emitted);
		assertFalse(sink.completed);
		assertEquals(0, pageDrain.requested.get());

		pageDrain.onRequest(1); // drains the remaining "c" and completes
		assertEquals(Arrays.asList("a", "b", "c"), sink.emitted);
		assertEquals(1, sink.completeCalls);
		assertEquals(0, pageDrain.requested.get());
	}

	/**
	 * A minimal {@link FluxSink} double: records emitted items and completion,
	 * and - once, on the first {@link #next} call only, via {@code
	 * reentrantRequestTarget} - reentrantly calls {@code onRequest(1)} back
	 * into the very {@link com.shri.restinpeace.reactor.FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain}
	 * that is currently emitting through it, synchronously, before that
	 * {@code next()} call returns. Every other {@link FluxSink} method is
	 * unused by {@code PageDrain} in this test (neither {@link #start()} nor
	 * the async next-page path is exercised) and throws if ever called.
	 */
	private static final class RecordingSink implements FluxSink<Object> {

		private final List<Object> emitted = new ArrayList<>();
		private boolean completed;
		private int completeCalls;
		private boolean firstNext = true;
		private FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain reentrantRequestTarget;

		@Override
		public FluxSink<Object> next(Object o) {
			emitted.add(o);
			if (firstNext) {
				firstNext = false;
				reentrantRequestTarget.onRequest(1);
			}
			return this;
		}

		@Override
		public void complete() {
			completed = true;
			completeCalls++;
		}

		@Override
		public void error(Throwable e) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Context currentContext() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long requestedFromDownstream() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public FluxSink<Object> onRequest(LongConsumer consumer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FluxSink<Object> onCancel(Disposable disposable) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FluxSink<Object> onDispose(Disposable disposable) {
			throw new UnsupportedOperationException();
		}

	}

	private static FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain newPageDrain() {
		return new FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain(null,
				fakePage(Collections.emptyList(), false, null));
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
