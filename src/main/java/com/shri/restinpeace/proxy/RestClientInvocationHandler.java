package com.shri.restinpeace.proxy;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
import com.shri.restinpeace.annotation.service.RestRequestProcessor;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * JDK dynamic proxy handler backing every client returned by
 * {@link com.shri.restinpeace.RIP#getClient(Class)}. Routes {@code Object}
 * methods ({@code toString}/{@code equals}/{@code hashCode}) to
 * proxy-aware implementations, and every other method call to
 * {@link RestRequestProcessor} based on its HTTP method annotation.
 */
public class RestClientInvocationHandler implements InvocationHandler {

	private RestRequestProcessor restRequestProcessor = new RestRequestProcessor();

	/** Creates a handler backed by a fresh {@link RestRequestProcessor}. */
	public RestClientInvocationHandler() {
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) {
			return invokeObjectMethod(proxy, method, args);
		}
		HTTPMethod httpMethod = getHTTPMethod(method);
		return restRequestProcessor.processRestRequest(method, httpMethod, args);
	}

	private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
		switch (method.getName()) {
		case "toString":
			return "RestClient[" + proxy.getClass().getInterfaces()[0].getName() + "]";
		case "hashCode":
			return System.identityHashCode(proxy);
		case "equals":
			return proxy == args[0];
		default:
			throw new RestInPeaceException(String.format("Unsupported Object method %s.", method));
		}
	}

	private HTTPMethod getHTTPMethod(Method method) {
		List<Annotation> httpAnnotations = Stream.of(method.getAnnotations()).filter(this::isHTTPMethod)
				.collect(Collectors.toList());
		if (httpAnnotations.size() > 1) {
			throw new RestInPeaceException(String
					.format("The interface method %s is annotated with more than 1 HTTP Method", method.toString()));
		}
		Annotation httpAnnotation = httpAnnotations.stream().findFirst()
				.orElseThrow(() -> new RestInPeaceException(String.format(
						"The interface method %s is not annotated with any of the HTTP Method annotation",
						method.toString())));
		return httpAnnotation.annotationType().getAnnotation(HTTPMethodMarker.class).value();
	}

	private boolean isHTTPMethod(Annotation annotation) {
		return annotation.annotationType().getAnnotation(HTTPMethodMarker.class) != null;
	}

}
