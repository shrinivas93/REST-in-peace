package com.shri.restinpeace.reactor;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.PaginatedCallAdapter;
import com.shri.restinpeace.PaginatedCallAdapterFactory;

/**
 * Claims a {@code @Paginated} method returning {@code Flux<T>} - "flavor 2"
 * of {@code Flux<T>} support (§7.2), and the actually novel one: a
 * genuinely backpressure-aware stream over {@code @Paginated}'s existing
 * page-fetch machinery, a third return-type-driven flattening mode
 * alongside {@code Page<T>} (manual) and {@code Stream<T>}/{@code Iterator<T>}
 * (eager-per-item-pull, lazy-per-page). {@code RequestExecutor} only ever
 * consults this factory for a method actually annotated {@code @Paginated}
 * (see {@code docs/design/reactor-call-adapter.md} §7.3's disambiguation),
 * so there's no need to re-check that here.
 */
public final class FluxPaginatedCallAdapterFactory implements PaginatedCallAdapterFactory {

	@Override
	public Optional<PaginatedCallAdapter<?>> get(Method method) {
		if (method.getReturnType() != Flux.class) {
			return Optional.empty();
		}
		if (!(method.getGenericReturnType() instanceof ParameterizedType)) {
			// A raw Flux (no type parameter) declines rather than throws - same
			// reasoning as MonoCallAdapterFactory's own raw-Mono handling - letting
			// ReflectiveRestClientValidator report a clean, collected error instead.
			return Optional.empty();
		}
		return Optional.of(new FluxPaginatedCallAdapter());
	}

	// Package-private, not private: FluxPaginatedCallAdapterFactoryTest needs
	// to name PageDrain (nested inside this), which Java requires every
	// enclosing level's access to allow.
	static final class FluxPaginatedCallAdapter implements PaginatedCallAdapter<Flux<Object>> {

		/**
		 * The first page is fetched eagerly, right here - before this method
		 * even returns a {@code Flux} - blocking the calling thread exactly the
		 * way a plain {@code Page<T>} return type already does for the same
		 * {@code @Paginated} method (§6.1's "eager, not deferred" convention,
		 * extended to this flavor: {@code adapt(...)} itself plays the role
		 * {@code CompletableFuture}'s own already-in-flight dispatch plays for
		 * {@code Mono<T>}). A page-1 failure therefore throws synchronously from
		 * the annotated method call, same as {@code Page<T>}'s own precedent -
		 * not a {@code Flux} error signal, since no {@code Flux} exists yet.
		 */
		@Override
		public Flux<Object> adapt(Supplier<Page<Object>> firstPageSupplier) {
			Page<Object> firstPage = firstPageSupplier.get();
			return Flux.create(sink -> new PageDrain(sink, firstPage).start(), FluxSink.OverflowStrategy.BUFFER);
		}

		/**
		 * Buffers one page's worth of items at a time, and fetches the next
		 * page only once the sink's accumulated, not-yet-satisfied demand
		 * exceeds what's already buffered - {@link FluxSink#onRequest} is
		 * Reactor's own backpressure signal driving that decision (§7.2). Each
		 * {@link Page#next()} call is a plain blocking call (matching
		 * {@code Page<T>}'s own design), so it always runs on
		 * {@link Schedulers#boundedElastic()}, never the subscriber's own
		 * thread, per Reactor's documented rule for a blocking call inside
		 * {@link Flux#create}.
		 *
		 * <p>
		 * <b>Cancellation is best-effort (§6.2's weaker cousin for this
		 * flavor):</b> disposing interrupts the {@code boundedElastic} worker
		 * thread if a next-page fetch is genuinely in progress, stopping
		 * further pages from being fetched - but unlike {@code Mono<T>}'s
		 * {@code CompletableFuture#cancel(true)}, whether an in-flight page's
		 * underlying blocking HTTP call actually aborts on that interrupt
		 * depends on Unirest/Apache HttpClient's own interrupt-handling for a
		 * synchronous request, which this layer doesn't control. Already
		 * in-hand, buffered items are never emitted past cancellation either
		 * way.
		 */
		// Package-private, not private: FluxPaginatedCallAdapterFactoryTest (same
		// package) calls the constructor, addCapped, and onRequest directly, and
		// reads requested - exercising this drain-loop arithmetic in isolation
		// from the Flux subscription lifecycle, whose own timing/completion races
		// would make driving these same code paths through it non-deterministic
		// (see that test's own class javadoc). Compile-time-checked direct calls
		// beat reflection here purely because this class already shares a package
		// with its test; the class and its members otherwise stay as narrowly
		// scoped as they can.
		static final class PageDrain {

			private final FluxSink<Object> sink;
			final AtomicLong requested = new AtomicLong();
			private final AtomicBoolean fetchingNextPage = new AtomicBoolean();
			private final AtomicInteger wip = new AtomicInteger();
			private volatile boolean cancelled;
			private Disposable inFlightNextPageFetch;
			private Page<Object> currentPage;
			private Iterator<Object> currentPageItems;

			PageDrain(FluxSink<Object> sink, Page<Object> firstPage) {
				this.sink = sink;
				this.currentPage = firstPage;
				this.currentPageItems = firstPage.items().iterator();
			}

			void start() {
				sink.onRequest(this::onRequest);
				sink.onCancel(this::cancel);
				sink.onDispose(this::cancel);
			}

			void onRequest(long n) {
				if (n <= 0 || cancelled) {
					return;
				}
				addCapped(n);
				drain();
			}

			/**
			 * No separate "already at {@code MAX_VALUE}" fast path is needed:
			 * {@code n} is always positive here ({@link #onRequest} already
			 * filters {@code n <= 0}), so {@code current == Long.MAX_VALUE} always
			 * overflows {@code current + n} to a negative value in two's-complement,
			 * which the overflow check below already saturates correctly.
			 */
			void addCapped(long n) {
				while (true) {
					long current = requested.get();
					long next = current + n;
					if (next < 0L) {
						next = Long.MAX_VALUE;
					}
					if (requested.compareAndSet(current, next)) {
						return;
					}
				}
			}

			/**
			 * Guarded by {@code wip} (the standard RxJava/Reactor "drain loop"
			 * idiom) rather than {@code synchronized}, specifically because this
			 * can genuinely be re-entered from the SAME thread, not just raced
			 * from a different one: {@link FluxSink#next} synchronously invoking
			 * a downstream subscriber's {@code onNext}, which itself calls
			 * {@code request(n)} again (a common flow-controlled consumption
			 * pattern) before returning, reaches {@link #onRequest} and this
			 * method again while the outer call is still on the stack. A plain
			 * {@code synchronized} method would let that reentrant call execute
			 * the whole body a second time concurrently with the outer call's
			 * own in-progress loop, corrupting {@code requested} (e.g. a second,
			 * nested drain fully consuming the newly-added demand, then the
			 * outer call's own pending {@code requested.decrementAndGet()} - for
			 * an item the nested call already accounted for - driving it
			 * negative). Incrementing {@code wip} first and only ever running
			 * {@link #drainOnce} while holding "the right" to do so (the thread
			 * that took it from 0) means a reentrant or concurrent call instead
			 * just registers a missed run for the loop below to pick up,
			 * guaranteeing exactly one {@link #drainOnce} execution at a time
			 * with no reentrancy - which is what actually needs to be true for
			 * {@link FluxSink#next}/{@code complete}/{@code error} to be safe to
			 * call here, not merely mutual exclusion between two threads.
			 */
			private void drain() {
				if (wip.getAndIncrement() != 0) {
					return;
				}
				int missed = 1;
				do {
					drainOnce();
					missed = wip.addAndGet(-missed);
				} while (missed != 0);
			}

			private void drainOnce() {
				if (cancelled) {
					return;
				}
				while (requested.get() > 0 && currentPageItems.hasNext()) {
					sink.next(currentPageItems.next());
					requested.decrementAndGet();
					if (cancelled) {
						return;
					}
				}
				if (currentPageItems.hasNext() || cancelled) {
					return;
				}
				if (!currentPage.hasNext()) {
					sink.complete();
					return;
				}
				if (requested.get() <= 0) {
					return;
				}
				if (fetchingNextPage.compareAndSet(false, true)) {
					fetchNextPageAsync();
				}
			}

			private void fetchNextPageAsync() {
				Disposable fetch = Schedulers.boundedElastic().schedule(() -> {
					try {
						Page<Object> nextPage = currentPage.next();
						if (cancelled) {
							return;
						}
						// currentPage/currentPageItems are safely published to
						// whichever thread's drainOnce() runs next by wip's own
						// atomic read-modify-write ordering in drain() below - the
						// same "safe publication via a shared atomic" guarantee
						// fetchingNextPage's volatile write alone can't provide on
						// its own, since drainOnce() reads currentPageItems before
						// it ever touches fetchingNextPage.
						currentPage = nextPage;
						currentPageItems = nextPage.items().iterator();
						fetchingNextPage.set(false);
						drain();
					} catch (Throwable error) {
						if (!cancelled) {
							sink.error(error);
						}
					}
				});
				// Published under the same lock cancel() uses, and disposed
				// immediately if cancellation already won that race - otherwise a
				// fetch scheduled just as/after disposal could sit unpublished
				// past cancel()'s own read of inFlightNextPageFetch, leaving it
				// running (and its result silently ignored) instead of interrupted.
				synchronized (this) {
					if (cancelled) {
						fetch.dispose();
					} else {
						inFlightNextPageFetch = fetch;
					}
				}
			}

			private void cancel() {
				Disposable fetch;
				synchronized (this) {
					cancelled = true;
					fetch = inFlightNextPageFetch;
					inFlightNextPageFetch = null;
				}
				if (fetch != null) {
					fetch.dispose();
				}
			}

		}

	}

}
