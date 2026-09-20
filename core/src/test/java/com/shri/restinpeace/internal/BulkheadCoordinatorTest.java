package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.BulkheadConfig;
import com.shri.restinpeace.exception.BulkheadFullException;
import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.HttpResponse;

class BulkheadCoordinatorTest {

	private static Supplier<HttpResponse<String>> respondingWith(int status) {
		return () -> new SyntheticHttpResponse<>(status, new kong.unirest.Headers(), "body");
	}

	private static Supplier<CompletableFuture<HttpResponse<String>>> respondingWithAsync(int status) {
		return () -> CompletableFuture.completedFuture(new SyntheticHttpResponse<>(status, new kong.unirest.Headers(), "body"));
	}

	@Test
	void unconfigured_neverRefusesAndNeverThrows() {
		BulkheadCoordinator coordinator = new BulkheadCoordinator(null);

		Supplier<HttpResponse<String>> wrapped = coordinator.wrapWithBulkhead(respondingWith(200));

		for (int i = 0; i < 100; i++) {
			assertEquals(200, wrapped.get().getStatus());
		}
	}

	@Test
	void belowCap_neverRefuses() {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(5).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		Supplier<HttpResponse<String>> wrapped = coordinator.wrapWithBulkhead(respondingWith(200));

		// Sequential calls each acquire and release their own permit - never more
		// than one in flight at once, well under the cap of 5.
		for (int i = 0; i < 10; i++) {
			assertEquals(200, wrapped.get().getStatus());
		}
	}

	@Test
	void permitIsReleasedOnSuccess_soASubsequentCallIsNeverRefused() {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		Supplier<HttpResponse<String>> wrapped = coordinator.wrapWithBulkhead(respondingWith(200));

		// If the permit weren't released after each call, the second of these
		// would find the single permit still held and be refused.
		assertEquals(200, wrapped.get().getStatus());
		assertEquals(200, wrapped.get().getStatus());
	}

	@Test
	void permitIsReleasedOnFailure_soASubsequentCallIsNeverRefused() {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		RuntimeException transportFailure = new RuntimeException("connection refused");
		Supplier<HttpResponse<String>> throwingCall = coordinator.wrapWithBulkhead(() -> {
			throw transportFailure;
		});

		assertThrows(RuntimeException.class, throwingCall::get);
		// The permit consumed by the failed call above must have been released in
		// a finally, not left held - otherwise this would be refused instead.
		Supplier<HttpResponse<String>> succeedingCall = coordinator.wrapWithBulkhead(respondingWith(200));
		assertEquals(200, succeedingCall.get().getStatus());
	}

	@Test
	void atCapWithNoWait_refusesImmediately() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch holdingCallStarted = new CountDownLatch(1);
		CountDownLatch releaseHoldingCall = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			holdingCallStarted.countDown();
			await(releaseHoldingCall);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<HttpResponse<String>> holding = executor.submit(holdingCall::get);
			holdingCallStarted.await(5, TimeUnit.SECONDS);

			// The single permit is held by holdingCall - a second call must be
			// refused right away instead of blocking, since no maxWaitDuration is set.
			Supplier<HttpResponse<String>> secondCall = coordinator.wrapWithBulkhead(respondingWith(200));
			assertThrows(BulkheadFullException.class, secondCall::get);

			releaseHoldingCall.countDown();
			assertEquals(200, holding.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void maxWaitDuration_letsAWaitingCallThroughOnceAPermitFrees() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1)
				.maxWaitDuration(Duration.ofSeconds(5)).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch holdingCallStarted = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			holdingCallStarted.countDown();
			sleep(100);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<HttpResponse<String>> holding = executor.submit(holdingCall::get);
			holdingCallStarted.await(5, TimeUnit.SECONDS);

			// holdingCall releases its permit ~100ms in - well within the 5s
			// maxWaitDuration, so this waiting call must be let through rather than
			// refused immediately, proving it actually waited instead of failing fast.
			Supplier<HttpResponse<String>> waitingCall = coordinator.wrapWithBulkhead(respondingWith(200));
			assertEquals(200, waitingCall.get().getStatus());
			assertEquals(200, holding.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void maxWaitDuration_stillRefusesIfNoPermitFreesInTime() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1)
				.maxWaitDuration(Duration.ofMillis(50)).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch holdingCallStarted = new CountDownLatch(1);
		CountDownLatch releaseHoldingCall = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			holdingCallStarted.countDown();
			await(releaseHoldingCall);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<HttpResponse<String>> holding = executor.submit(holdingCall::get);
			holdingCallStarted.await(5, TimeUnit.SECONDS);

			Supplier<HttpResponse<String>> waitingCall = coordinator.wrapWithBulkhead(respondingWith(200));
			assertThrows(BulkheadFullException.class, waitingCall::get);

			releaseHoldingCall.countDown();
			assertEquals(200, holding.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentCallsBeyondCap_refuseOnlyTheExcess() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(2).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch bothStarted = new CountDownLatch(2);
		CountDownLatch releaseBoth = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			bothStarted.countDown();
			await(releaseBoth);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<HttpResponse<String>> first = executor.submit(holdingCall::get);
			Future<HttpResponse<String>> second = executor.submit(holdingCall::get);
			assertTrue(bothStarted.await(5, TimeUnit.SECONDS));

			// Both permits are held by the two in-flight calls above - a third
			// concurrent call must be refused, proving the cap is genuinely 2, not
			// per-call-instance or unbounded.
			Supplier<HttpResponse<String>> thirdCall = coordinator.wrapWithBulkhead(respondingWith(200));
			assertThrows(BulkheadFullException.class, thirdCall::get);

			releaseBoth.countDown();
			assertEquals(200, first.get(5, TimeUnit.SECONDS).getStatus());
			assertEquals(200, second.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void interruptedWhileWaitingForAPermit_restoresInterruptStatusAndThrows() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1)
				.maxWaitDuration(Duration.ofSeconds(5)).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch holdingCallStarted = new CountDownLatch(1);
		CountDownLatch releaseHoldingCall = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			holdingCallStarted.countDown();
			await(releaseHoldingCall);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService holder = Executors.newSingleThreadExecutor();
		ExecutorService waiter = Executors.newSingleThreadExecutor();
		try {
			Future<HttpResponse<String>> holding = holder.submit(holdingCall::get);
			holdingCallStarted.await(5, TimeUnit.SECONDS);

			Supplier<HttpResponse<String>> waitingCall = coordinator.wrapWithBulkhead(respondingWith(200));
			Future<HttpResponse<String>> waiting = waiter.submit(waitingCall::get);
			// Give the waiting call time to actually block inside tryAcquire's
			// timed wait before interrupting it - the whole point of this test.
			Thread.sleep(100);
			waiter.shutdownNow();

			ExecutionException thrown = assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
			assertTrue(thrown.getCause() instanceof RestInPeaceException);

			releaseHoldingCall.countDown();
			assertEquals(200, holding.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			holder.shutdownNow();
			waiter.shutdownNow();
		}
	}

	@Test
	void async_unconfigured_neverRefusesAndNeverThrows() throws Exception {
		BulkheadCoordinator coordinator = new BulkheadCoordinator(null);

		Supplier<CompletableFuture<HttpResponse<String>>> wrapped = coordinator
				.wrapWithBulkheadAsync(respondingWithAsync(200));

		for (int i = 0; i < 20; i++) {
			assertEquals(200, wrapped.get().get(5, TimeUnit.SECONDS).getStatus());
		}
	}

	@Test
	void async_permitIsReleasedOnCompletion_soASubsequentCallIsNeverRefused() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		Supplier<CompletableFuture<HttpResponse<String>>> wrapped = coordinator
				.wrapWithBulkheadAsync(respondingWithAsync(200));

		// If the permit weren't released once the first future completed, the
		// second of these would find it still held and be refused.
		assertEquals(200, wrapped.get().get(5, TimeUnit.SECONDS).getStatus());
		assertEquals(200, wrapped.get().get(5, TimeUnit.SECONDS).getStatus());
	}

	@Test
	void async_permitIsReleasedOnFailure_soASubsequentCallIsNeverRefused() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		RuntimeException transportFailure = new RuntimeException("connection refused");
		Supplier<CompletableFuture<HttpResponse<String>>> failingCall = coordinator.wrapWithBulkheadAsync(() -> {
			CompletableFuture<HttpResponse<String>> failed = new CompletableFuture<>();
			failed.completeExceptionally(transportFailure);
			return failed;
		});

		assertThrows(ExecutionException.class, () -> failingCall.get().get(5, TimeUnit.SECONDS));
		// The permit consumed by the failed call above must have been released,
		// not left held - otherwise this would be refused instead.
		Supplier<CompletableFuture<HttpResponse<String>>> succeedingCall = coordinator
				.wrapWithBulkheadAsync(respondingWithAsync(200));
		assertEquals(200, succeedingCall.get().get(5, TimeUnit.SECONDS).getStatus());
	}

	@Test
	void async_atCapWithNoWait_returnsAlreadyFailedFuture_withoutThrowingSynchronously() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch holdingCallStarted = new CountDownLatch(1);
		CountDownLatch releaseHoldingCall = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			holdingCallStarted.countDown();
			await(releaseHoldingCall);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<HttpResponse<String>> holding = executor.submit(holdingCall::get);
			holdingCallStarted.await(5, TimeUnit.SECONDS);

			// The whole point of the async wrap: calling .get() on the returned
			// Supplier itself must never throw - the failure must arrive via the
			// future it returns, since a synchronous throw here would be lost if
			// this supplier is ever invoked from inside a scheduled retry callback
			// (see the coordinator's own javadoc for wrapWithBulkheadAsync).
			Supplier<CompletableFuture<HttpResponse<String>>> secondCall = coordinator
					.wrapWithBulkheadAsync(respondingWithAsync(200));
			CompletableFuture<HttpResponse<String>> result = secondCall.get();
			ExecutionException thrown = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
			assertTrue(thrown.getCause() instanceof BulkheadFullException);

			releaseHoldingCall.countDown();
			assertEquals(200, holding.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void async_maxWaitDuration_doesNotBlockTheCallingThreadWhileWaiting() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1)
				.maxWaitDuration(Duration.ofSeconds(5)).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch holdingCallStarted = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			holdingCallStarted.countDown();
			sleep(200);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<HttpResponse<String>> holding = executor.submit(holdingCall::get);
			holdingCallStarted.await(5, TimeUnit.SECONDS);

			Supplier<CompletableFuture<HttpResponse<String>>> waitingCall = coordinator
					.wrapWithBulkheadAsync(respondingWithAsync(200));

			// Called directly on this test thread, not a background executor - if
			// waiting for a permit blocked this thread for the ~200ms the holding
			// call takes to finish, this call itself would take that long. It must
			// return near-instantly instead, with the actual wait happening on a
			// background thread and completing the returned future later - the
			// whole reason wrapWithBulkheadAsync's timed wait exists separately
			// from the sync path's plain blocking Semaphore#tryAcquire.
			long startNanos = System.nanoTime();
			CompletableFuture<HttpResponse<String>> waitingResult = waitingCall.get();
			long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
			assertTrue(elapsedMillis < 100, "wrapWithBulkheadAsync blocked the calling thread for " + elapsedMillis + "ms");

			assertEquals(200, waitingResult.get(5, TimeUnit.SECONDS).getStatus());
			assertEquals(200, holding.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void async_maxWaitDuration_stillRefusesIfNoPermitFreesInTime() throws Exception {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1)
				.maxWaitDuration(Duration.ofMillis(50)).build();
		BulkheadCoordinator coordinator = new BulkheadCoordinator(config);
		CountDownLatch holdingCallStarted = new CountDownLatch(1);
		CountDownLatch releaseHoldingCall = new CountDownLatch(1);
		Supplier<HttpResponse<String>> holdingCall = coordinator.wrapWithBulkhead(() -> {
			holdingCallStarted.countDown();
			await(releaseHoldingCall);
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<HttpResponse<String>> holding = executor.submit(holdingCall::get);
			holdingCallStarted.await(5, TimeUnit.SECONDS);

			Supplier<CompletableFuture<HttpResponse<String>>> waitingCall = coordinator
					.wrapWithBulkheadAsync(respondingWithAsync(200));
			CompletableFuture<HttpResponse<String>> result = waitingCall.get();
			ExecutionException thrown = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
			assertTrue(thrown.getCause() instanceof BulkheadFullException);

			releaseHoldingCall.countDown();
			assertEquals(200, holding.get(5, TimeUnit.SECONDS).getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

}
