package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;
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
	 * Regression test for {@code PageDrain.start()}'s callback registration
	 * order: {@code onCancel}/{@code onDispose} must be registered before
	 * {@code onRequest}, since a real {@code Flux.create} sink invokes a
	 * just-registered {@code onRequest} consumer synchronously when demand is
	 * already outstanding.
	 */
	@Test
	void start_registersCancelAndDisposeCallbacksBeforeOnRequest() {
		OrderCapturingSink sink = new OrderCapturingSink();
		FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain pageDrain = //
				new FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain(sink,
						fakePage(Collections.emptyList(), false, null));

		pageDrain.start();

		assertEquals(Arrays.asList("onCancel", "onDispose", "onRequest"), sink.registrationOrder);
	}

	/**
	 * Reproduces the actual race {@code start()}'s registration order guards
	 * against: {@code onRequest}'s consumer fires synchronously with initial
	 * demand (as a real {@code Flux.create} sink does when the downstream
	 * already requested before subscribing), draining far enough to call
	 * {@code sink.next} - which here simulates a downstream {@code take(1)}
	 * cancelling synchronously on the first item. With the callbacks
	 * registered in the right order, that cancellation reaches {@code cancel()}
	 * immediately, stopping the drain after exactly one item; with the old,
	 * wrong order this would go unnoticed and a second item would be drained
	 * before {@code onRequest} even returns.
	 */
	@Test
	void start_aSynchronousCancelDuringInitialDemandStopsTheDrainImmediately() {
		ImmediateDemandSink sink = new ImmediateDemandSink();
		Page<Object> page = fakePage(Arrays.asList("a", "b", "c"), false, null);
		FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain pageDrain = //
				new FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain(sink, page);

		pageDrain.start();

		assertTrue(sink.cancelInvokedDuringNext);
		assertEquals(Collections.singletonList("a"), sink.emitted);

		pageDrain.onRequest(1); // already cancelled - must be a no-op
		assertEquals(Collections.singletonList("a"), sink.emitted);
	}

	/**
	 * Regression test for {@code fetchNextPageAsync}'s scheduling-rejection
	 * recovery: a scheduler that fails to even accept the fetch task (e.g. a
	 * disposed {@code boundedElastic}) must not leave {@code fetchingNextPage}
	 * stuck {@code true} forever - which would silently swallow every future
	 * {@code onRequest}'s attempt to retry - and must signal the failure to the
	 * subscriber via {@code sink.error} instead of letting it escape unseen.
	 * Verifies both by observing a second onRequest() triggers a second
	 * scheduling attempt (proving the flag was cleared) and a second recorded
	 * error (proving each rejection is signalled).
	 */
	@Test
	void fetchNextPageAsync_aSchedulingRejectionSignalsAnErrorAndClearsTheFetchState() {
		ErrorRecordingSink sink = new ErrorRecordingSink();
		Page<Object> page = fakePage(Collections.singletonList("a"), true,
				() -> fakePage(Collections.emptyList(), false, null));
		RejectedExecutionException rejection = new RejectedExecutionException("scheduler shut down");
		RejectingScheduler scheduler = new RejectingScheduler(rejection);
		FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain pageDrain = //
				new FluxPaginatedCallAdapterFactory.FluxPaginatedCallAdapter.PageDrain(sink, page, scheduler);

		// Demand of 2 against a 1-item page: draining "a" still leaves 1 unit of
		// leftover demand, which is what actually drives drainOnce() to attempt
		// fetching the next page at all (no leftover demand, no fetch attempt).
		pageDrain.onRequest(2); // drains "a", then hits (and fails) the next-page fetch
		assertEquals(1, scheduler.scheduleAttempts);
		assertEquals(Collections.singletonList(rejection), sink.errors);

		pageDrain.onRequest(1); // fetchingNextPage must have been cleared to reach here again
		assertEquals(2, scheduler.scheduleAttempts);
		assertEquals(Arrays.asList(rejection, rejection), sink.errors);
	}

	/**
	 * A minimal {@link Scheduler} double whose {@code schedule(Runnable)}
	 * throws synchronously every time, standing in for a real scheduler
	 * rejecting a task (e.g. {@code RejectedExecutionException} from a
	 * disposed {@code boundedElastic}) - {@link #createWorker} is never called
	 * by {@code PageDrain} and throws if it ever is.
	 */
	private static final class RejectingScheduler implements Scheduler {

		private final RuntimeException rejection;
		private int scheduleAttempts;

		private RejectingScheduler(RuntimeException rejection) {
			this.rejection = rejection;
		}

		@Override
		public Disposable schedule(Runnable task) {
			scheduleAttempts++;
			throw rejection;
		}

		@Override
		public Worker createWorker() {
			throw new UnsupportedOperationException();
		}

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

	/**
	 * A minimal {@link FluxSink} double that just records the order in which
	 * {@code onRequest}/{@code onCancel}/{@code onDispose} are registered -
	 * every other method is unused by {@code start()} and throws if called.
	 */
	private static final class OrderCapturingSink implements FluxSink<Object> {

		private final List<String> registrationOrder = new ArrayList<>();

		@Override
		public FluxSink<Object> onRequest(LongConsumer consumer) {
			registrationOrder.add("onRequest");
			return this;
		}

		@Override
		public FluxSink<Object> onCancel(Disposable disposable) {
			registrationOrder.add("onCancel");
			return this;
		}

		@Override
		public FluxSink<Object> onDispose(Disposable disposable) {
			registrationOrder.add("onDispose");
			return this;
		}

		@Override
		public FluxSink<Object> next(Object o) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void complete() {
			throw new UnsupportedOperationException();
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

	}

	/**
	 * A minimal {@link FluxSink} double reproducing the real {@code Flux.create}
	 * behavior that makes {@code start()}'s registration order matter: {@code
	 * onRequest} invokes its consumer immediately (as if demand were already
	 * outstanding when the sink subscribed), and {@code next} simulates a
	 * downstream {@code take(1)} by synchronously disposing whichever {@code
	 * onCancel} callback has been registered so far - {@code null} if none has,
	 * exactly like a real sink that hasn't been given one yet.
	 */
	private static final class ImmediateDemandSink implements FluxSink<Object> {

		private final List<Object> emitted = new ArrayList<>();
		private Disposable onCancelDisposable;
		private boolean cancelInvokedDuringNext;

		@Override
		public FluxSink<Object> onRequest(LongConsumer consumer) {
			consumer.accept(2);
			return this;
		}

		@Override
		public FluxSink<Object> onCancel(Disposable disposable) {
			this.onCancelDisposable = disposable;
			return this;
		}

		@Override
		public FluxSink<Object> onDispose(Disposable disposable) {
			return this;
		}

		@Override
		public FluxSink<Object> next(Object o) {
			emitted.add(o);
			if (onCancelDisposable != null) {
				cancelInvokedDuringNext = true;
				onCancelDisposable.dispose();
			}
			return this;
		}

		@Override
		public void complete() {
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

	}

	/**
	 * A minimal {@link FluxSink} double that records every {@code error(...)}
	 * call instead of throwing on the first one, so a test can assert on how
	 * many times (and with what) it was signalled - every other method is
	 * unused by the scheduling-rejection path and throws if called.
	 */
	private static final class ErrorRecordingSink implements FluxSink<Object> {

		private final List<Throwable> errors = new ArrayList<>();

		@Override
		public FluxSink<Object> next(Object o) {
			return this;
		}

		@Override
		public void complete() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void error(Throwable e) {
			errors.add(e);
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
			return this;
		}

		@Override
		public FluxSink<Object> onCancel(Disposable disposable) {
			return this;
		}

		@Override
		public FluxSink<Object> onDispose(Disposable disposable) {
			return this;
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
