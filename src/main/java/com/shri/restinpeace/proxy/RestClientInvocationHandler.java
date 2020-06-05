package com.shri.restinpeace.proxy;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
import com.shri.restinpeace.annotation.service.RestRequestProcessor;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;

public class RestClientInvocationHandler implements InvocationHandler {

	private RestRequestProcessor restRequestProcessor = new RestRequestProcessor();

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		System.out.println(method.toString());
		HTTPMethod httpMethod = getHTTPMethod(method);
		restRequestProcessor.processRestRequest(method, httpMethod);
		return null;
	}

	private HTTPMethod getHTTPMethod(Method method) {
		List<Annotation> httpAnnotations = Stream.of(method.getAnnotations()).filter(this::isHTTPMethod)
				.collect(Collectors.toList());
		if (httpAnnotations.size() > 1) {
			throw new RestInPeaceException(String
					.format("The interface method %s is annotated with more than 1 HTTP Method", method.toString()));
		}
		Optional<Annotation> httpAnnotationOptional = httpAnnotations.stream().findFirst();
		if (!httpAnnotationOptional.isPresent()) {
			throw new RestInPeaceException(
					String.format("The interface method %s is not annotated with any of the HTTP Method annotation",
							method.toString()));
		}
		return httpAnnotationOptional.get().annotationType().getAnnotation(HTTPMethodMarker.class).value();
	}

	private boolean isHTTPMethod(Annotation annotation) {
		return annotation.annotationType().getAnnotation(HTTPMethodMarker.class) != null;
	}

}
