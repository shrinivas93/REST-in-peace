package com.shri.restinpeace.mock;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.shri.restinpeace.CallAdapter;
import com.shri.restinpeace.CallAdapterFactory;

/**
 * Adapts any method returning {@link TestBox}{@code <T>} - resolving
 * {@code T} from the method's own generic signature, blocking on the
 * delegate future to resolve synchronously, matching {@link TestBox}'s own
 * simplicity. See {@code docs/design/reactor-call-adapter.md} §5, §6 (a
 * real {@code MonoCallAdapterFactory} would instead wrap the future
 * non-blockingly, e.g. via {@code Mono.create}).
 */
final class TestCallAdapterFactory implements CallAdapterFactory {

	static final TestCallAdapterFactory INSTANCE = new TestCallAdapterFactory();

	@Override
	public Optional<CallAdapter<?>> get(Method method) {
		if (method.getReturnType() != TestBox.class) {
			return Optional.empty();
		}
		Type innerType = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
		return Optional.of(new CallAdapter<TestBox<Object>>() {
			@Override
			public Type responseBodyType() {
				return innerType;
			}

			@Override
			public TestBox<Object> adapt(CompletableFuture<Object> delegate) {
				try {
					return TestBox.of(delegate.join());
				} catch (CompletionException e) {
					Throwable cause = e.getCause();
					return TestBox.failed(cause instanceof RuntimeException ? (RuntimeException) cause : e);
				}
			}
		});
	}

}
