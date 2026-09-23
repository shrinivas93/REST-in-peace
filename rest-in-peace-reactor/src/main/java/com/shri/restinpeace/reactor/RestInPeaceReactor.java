package com.shri.restinpeace.reactor;

import com.shri.restinpeace.RIP;

/**
 * Entry point for {@code rest-in-peace-reactor}: registers this module's
 * {@link com.shri.restinpeace.CallAdapter} factories with RIP. Call once at
 * startup, before
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
 * }
 * </pre>
 *
 * <p>
 * Only {@code Mono<T>} is registered so far - {@code Flux<T>} lands in a
 * later chunk (see {@code docs/design/reactor-call-adapter.md} §14).
 */
public final class RestInPeaceReactor {

	private RestInPeaceReactor() {
		// private constructor to hide the implicit public one
	}

	private static final MonoCallAdapterFactory MONO_FACTORY = new MonoCallAdapterFactory();

	/**
	 * Registers this module's {@link com.shri.restinpeace.CallAdapterFactory}
	 * instances with {@link RIP#addCallAdapterFactory}. Idempotent by
	 * identity - calling this more than once registers the same singleton
	 * factory instance again, which call-adapter resolution would simply
	 * find twice in a row with the same answer either time; harmless, but
	 * there's no reason to call it more than once.
	 */
	public static void register() {
		RIP.addCallAdapterFactory(MONO_FACTORY);
	}

	/**
	 * Reverses {@link #register()} - removes this module's factories.
	 * Mainly useful for tests.
	 */
	public static void unregister() {
		RIP.removeCallAdapterFactory(MONO_FACTORY);
	}

}
