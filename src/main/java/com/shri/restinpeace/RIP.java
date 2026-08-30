package com.shri.restinpeace;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.proxy.RestClientInvocationHandler;
import com.shri.restinpeace.validator.RestClientValidator;

import kong.unirest.Unirest;

public class RIP {

	private static final InvocationHandler REST_CLIENT_INVOCATION_HANDLER = new RestClientInvocationHandler();

	private RIP() {
		// private constructor to hide the implicit public one
	}

	@SuppressWarnings("unchecked")
	public static <T> T getClient(Class<T> restClient) {

		try {
			RestClientValidator.validate(restClient);
		} catch (RestInPeaceValidationException e) {
			throw new RestInPeaceException(String.format("The rest client %s failed during validation with %s errors.",
					restClient.getName(), e.getValidationResult().getErrors().size()), e);
		}

		if (null == restClient.getAnnotation(RestClient.class)) {
			throw new RestInPeaceException(String.format("The interface %s is not annotated with %s.",
					restClient.getName(), RestClient.class.getName()));
		}
		return (T) Proxy.newProxyInstance(restClient.getClassLoader(), new Class[] { restClient },
				REST_CLIENT_INVOCATION_HANDLER);
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

}
