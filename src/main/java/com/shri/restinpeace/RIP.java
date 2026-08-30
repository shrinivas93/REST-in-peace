package com.shri.restinpeace;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.proxy.RestClientInvocationHandler;
import com.shri.restinpeace.validator.RestClientValidator;

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

}
