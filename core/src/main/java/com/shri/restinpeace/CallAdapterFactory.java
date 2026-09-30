package com.shri.restinpeace;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Recognizes a method's declared return type and produces a
 * {@link CallAdapter} for it, or declines. Registered via
 * {@link RIP#addCallAdapterFactory(CallAdapterFactory)}; every registered
 * factory is consulted, in registration order, and the first non-empty
 * {@link Optional} wins - mirroring
 * {@link com.shri.restinpeace.interceptor.RequestInterceptor}'s own
 * "registration order matters" convention. See
 * {@code docs/design/reactor-call-adapter.md} §5.
 */
public interface CallAdapterFactory {

	/**
	 * @param method the interface method being dispatched or, at
	 *               {@code RIP.getClient(...)} time, validated -
	 *               {@code ReflectiveRestClientValidator} calls this exact
	 *               method to check a claimed adapter's declared return
	 *               type before the client is ever built, so a factory must
	 *               behave identically both times for the same method
	 * @return an adapter for {@code method}'s return type, or
	 *         {@link Optional#empty()} to decline. RIP's own built-in
	 *         shapes ({@code CompletableFuture}, {@code RipResponse},
	 *         {@code byte[]}, {@code File}) are handled first and always
	 *         win - a factory is only ever consulted for a return type none
	 *         of those already claim, so declaring one of them here has no
	 *         effect. Declining also lets RIP try the next registered
	 *         factory, in registration order.
	 */
	Optional<CallAdapter<?>> get(Method method);

}
