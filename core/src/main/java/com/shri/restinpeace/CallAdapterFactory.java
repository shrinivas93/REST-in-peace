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
	 * @param method the interface method being dispatched
	 * @return an adapter for {@code method}'s return type, or
	 *         {@link Optional#empty()} to decline (RIP tries the next
	 *         registered factory, then its own built-in shapes, in that
	 *         order)
	 */
	Optional<CallAdapter<?>> get(Method method);

}
