package com.shri.restinpeace.reactor;

import com.shri.restinpeace.RIP;

/**
 * Entry point for {@code rest-in-peace-reactor}: registers this module's
 * {@link com.shri.restinpeace.CallAdapter}/{@link com.shri.restinpeace.PaginatedCallAdapter}
 * factories with RIP. Call once at startup, before
 * building any {@code @RestClient} - the same "configure globals first"
 * requirement {@link RIP#addCallAdapterFactory} itself already documents,
 * since a factory needs to be registered before
 * {@code ReflectiveRestClientValidator} validates a method claiming it.
 *
 * <pre>
 * RestInPeaceReactor.register();   // once, at startup
 *
 * {@literal @}RestClient
 * {@literal @}BaseUrl("https://api.example.com")
 * interface UserApi {
 *     {@literal @}GET("/users/{id}")
 *     Mono{@literal <}User{@literal >} getUser({@literal @}PathParam("id") String id);
 *
 *     {@literal @}GET("/users")
 *     Flux{@literal <}User{@literal >} listUsers();   // flavor 1 (§7.1) - a single JSON array response
 *
 *     {@literal @}GET("/users")
 *     {@literal @}Paginated(itemsField = "users", pointerField = "next_cursor")
 *     Flux{@literal <}User{@literal >} fluxUsers({@literal @}QueryParam("cursor") {@literal @}PaginationCursor String cursor);   // flavor 2 (§7.2) - real backpressure
 * }
 * </pre>
 *
 * <p>
 * {@code Mono<T>} and both {@code Flux<T>} flavors are registered - see
 * {@code docs/design/reactor-call-adapter.md} §14 for the full rollout plan.
 */
public final class RestInPeaceReactor {

	private RestInPeaceReactor() {
		// private constructor to hide the implicit public one
	}

	private static final MonoCallAdapterFactory MONO_FACTORY = new MonoCallAdapterFactory();
	private static final FluxListCallAdapterFactory FLUX_LIST_FACTORY = new FluxListCallAdapterFactory();
	private static final FluxPaginatedCallAdapterFactory FLUX_PAGINATED_FACTORY = new FluxPaginatedCallAdapterFactory();

	/**
	 * Registers this module's {@link com.shri.restinpeace.CallAdapterFactory}/
	 * {@link com.shri.restinpeace.PaginatedCallAdapterFactory} instances with
	 * {@link RIP#addCallAdapterFactory}/{@link RIP#addPaginatedCallAdapterFactory}.
	 * Idempotent by identity - {@code RequestExecutor}'s registries only ever
	 * add an instance that isn't already present by reference, so calling
	 * this more than once is a no-op for these singletons; harmless, but
	 * there's no reason to call it more than once.
	 */
	public static void register() {
		RIP.addCallAdapterFactory(MONO_FACTORY);
		RIP.addCallAdapterFactory(FLUX_LIST_FACTORY);
		RIP.addPaginatedCallAdapterFactory(FLUX_PAGINATED_FACTORY);
	}

	/**
	 * Reverses {@link #register()} - removes this module's factories.
	 * Mainly useful for tests.
	 */
	public static void unregister() {
		RIP.removeCallAdapterFactory(MONO_FACTORY);
		RIP.removeCallAdapterFactory(FLUX_LIST_FACTORY);
		RIP.removePaginatedCallAdapterFactory(FLUX_PAGINATED_FACTORY);
	}

}
