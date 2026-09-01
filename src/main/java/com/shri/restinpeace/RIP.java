package com.shri.restinpeace;

import java.lang.reflect.Proxy;

import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.service.RestRequestProcessor;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.interceptor.RequestInterceptor;
import com.shri.restinpeace.proxy.RestClientInvocationHandler;
import com.shri.restinpeace.validator.RestClientValidator;

import kong.unirest.Unirest;

/**
 * Entry point for REST-in-peace: turns an annotated {@code @RestClient}
 * interface into a working HTTP client backed by a JDK dynamic proxy, via
 * {@link #getClient(Class)}.
 */
public class RIP {

	private RIP() {
		// private constructor to hide the implicit public one
	}

	/**
	 * Validates {@code restClient} and returns a proxy implementing it, where
	 * each method call issues the HTTP request its annotations describe.
	 *
	 * @param <T>        the rest client interface type
	 * @param restClient an interface annotated with
	 *                    {@link com.shri.restinpeace.annotation.marker.RestClient @RestClient}
	 * @return a proxy instance implementing {@code restClient}
	 * @throws RestInPeaceException if the interface is missing
	 *                              {@code @RestClient} or fails validation
	 */
	public static <T> T getClient(Class<T> restClient) {
		return getClient(restClient, (String) null);
	}

	/**
	 * Same as {@link #getClient(Class)}, but every relative method URL is
	 * resolved against {@code baseUrl} instead of the interface's
	 * {@code @BaseUrl} - for a base URL that's only known at runtime (e.g.
	 * an app that deploys the same {@code @RestClient} interface against a
	 * different environment per deployment, read from an env var or config
	 * file). An absolute method URL still ignores this, same as it ignores
	 * {@code @BaseUrl}; {@code @BaseUrl} on the interface is itself optional
	 * when every relative method URL is covered by {@code baseUrl} here.
	 *
	 * @param <T>        the rest client interface type
	 * @param restClient an interface annotated with
	 *                    {@link com.shri.restinpeace.annotation.marker.RestClient @RestClient}
	 * @param baseUrl    the runtime base URL to resolve relative method URLs
	 *                    against, or {@code null} to require {@code @BaseUrl}
	 *                    on the interface instead
	 * @return a proxy instance implementing {@code restClient}
	 * @throws RestInPeaceException if the interface is missing
	 *                              {@code @RestClient} or fails validation
	 */
	@SuppressWarnings("unchecked")
	public static <T> T getClient(Class<T> restClient, String baseUrl) {

		try {
			RestClientValidator.validate(restClient, baseUrl);
		} catch (RestInPeaceValidationException e) {
			throw new RestInPeaceException(String.format("The rest client %s failed during validation with %s errors.",
					restClient.getName(), e.getValidationResult().getErrors().size()), e);
		}

		if (null == restClient.getAnnotation(RestClient.class)) {
			throw new RestInPeaceException(String.format("The interface %s is not annotated with %s.",
					restClient.getName(), RestClient.class.getName()));
		}
		return (T) Proxy.newProxyInstance(restClient.getClassLoader(), new Class[] { restClient },
				new RestClientInvocationHandler(baseUrl));
	}

	/**
	 * Same as {@link #getClient(Class)}, but resolves the base URL,
	 * connect/read timeout, and proxy from {@code config} instead of the
	 * interface's {@code @BaseUrl} and the shared client's global config -
	 * for a {@code @RestClient} whose environment (base URL, timeout,
	 * proxy) differs from every other client's. See {@link RipClientConfig}.
	 *
	 * @param <T>        the rest client interface type
	 * @param restClient an interface annotated with
	 *                    {@link com.shri.restinpeace.annotation.marker.RestClient @RestClient}
	 * @param config     the per-client settings
	 * @return a proxy instance implementing {@code restClient}
	 * @throws RestInPeaceException if the interface is missing
	 *                              {@code @RestClient} or fails validation
	 */
	@SuppressWarnings("unchecked")
	public static <T> T getClient(Class<T> restClient, RipClientConfig config) {

		try {
			RestClientValidator.validate(restClient, config.getBaseUrl());
		} catch (RestInPeaceValidationException e) {
			throw new RestInPeaceException(String.format("The rest client %s failed during validation with %s errors.",
					restClient.getName(), e.getValidationResult().getErrors().size()), e);
		}

		if (null == restClient.getAnnotation(RestClient.class)) {
			throw new RestInPeaceException(String.format("The interface %s is not annotated with %s.",
					restClient.getName(), RestClient.class.getName()));
		}
		return (T) Proxy.newProxyInstance(restClient.getClassLoader(), new Class[] { restClient },
				new RestClientInvocationHandler(config));
	}

	/**
	 * Opt-in: makes a CompletableFuture-returning call use daemon threads for
	 * Unirest's async client, so a short-lived program can exit on its own
	 * after an async call instead of hanging on non-daemon I/O threads. Not
	 * the default, since it reconfigures Unirest's shared global client -
	 * call this once at startup only if your app doesn't already configure
	 * Unirest's async client itself.
	 */
	public static void useDaemonThreadsForAsync() {
		CloseableHttpAsyncClient client = HttpAsyncClients.custom().setThreadFactory(runnable -> {
			Thread thread = new Thread(runnable, "rip-async-client");
			thread.setDaemon(true);
			return thread;
		}).build();
		client.start();
		Unirest.config().asyncClient(client);
	}

	/**
	 * Registers a global hook into every request/response made through RIP -
	 * see {@link RequestInterceptor} for what it can and can't do.
	 *
	 * @param interceptor the interceptor to register
	 */
	public static void addInterceptor(RequestInterceptor interceptor) {
		RestRequestProcessor.addInterceptor(interceptor);
	}

	/**
	 * Removes all registered interceptors. Mainly useful for tests.
	 */
	public static void clearInterceptors() {
		RestRequestProcessor.clearInterceptors();
	}

}
