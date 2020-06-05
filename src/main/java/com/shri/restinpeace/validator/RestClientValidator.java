package com.shri.restinpeace.validator;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.stream.Stream;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.validator.dto.ValidationResult;

public class RestClientValidator {

	private RestClientValidator() {
		// private constructor to hide the implicit public one
	}

	public static <T> void validate(Class<T> restClient) throws RestInPeaceValidationException {

		ValidationResult validationResult = new ValidationResult();

		// Checking if the restClient is null
		if (null == restClient) {
			validationResult.addError("Rest Client cannot be null");
			throw new RestInPeaceValidationException(validationResult);
		}

		// Checking if the interface is annotated with @RestClient
		if (null == restClient.getAnnotation(RestClient.class)) {
			validationResult.addError(
					String.format("The interface %s is not annotated with @RestClient.", restClient.getName()));
		}

		Method[] methods = restClient.getMethods();

		for (Method method : methods) {
			long httpMethodAnnotationCount = Stream.of(method.getAnnotations())
					.filter(annotation -> Optional
							.ofNullable(annotation.annotationType().getAnnotation(HTTPMethodMarker.class)).isPresent())
					.count();
			if (httpMethodAnnotationCount == 0) {
				validationResult.addError(
						String.format("The method %s.%s is not annotated with any of the HTTP method annotations.",
								restClient.getName(), method.getName()));
			}
			if (httpMethodAnnotationCount > 1) {
				validationResult.addError(String.format("The method %s.%s has more than one HTTP method annotations.",
						restClient.getName(), method.getName()));
			}
			if (httpMethodAnnotationCount == 1) {
				validateRestClientMethod(method, validationResult);
			}
		}

		throw new RestInPeaceValidationException(validationResult);

	}

	private static void validateRestClientMethod(Method method, ValidationResult validationResult) {
		Optional<Annotation> httpMethodAnnotationOptional = Stream.of(method.getAnnotations())
				.filter(annotation -> Optional
						.ofNullable(annotation.annotationType().getAnnotation(HTTPMethodMarker.class)).isPresent())
				.findFirst();
		if (httpMethodAnnotationOptional.isPresent()) {
			Class<? extends Annotation> httpMethodAnnotation = httpMethodAnnotationOptional.get().annotationType();
			HTTPMethod httpMethod = httpMethodAnnotation.getAnnotation(HTTPMethodMarker.class).value();
			switch (httpMethod) {
			case DELETE:
				processDeleteRequest(method, validationResult);
				break;
			case GET:
				processGetRequest(method, validationResult);
				break;
			case HEAD:
				processHeadRequest(method, validationResult);
				break;
			case OPTIONS:
				processOptionsRequest(method, validationResult);
				break;
			case PATCH:
				processPatchRequest(method, validationResult);
				break;
			case POST:
				processPostRequest(method, validationResult);
				break;
			case PUT:
				processPutRequest(method, validationResult);
				break;
			}
		}

	}

	private static void processPutRequest(Method method, ValidationResult validationResult) {

	}

	private static void processPostRequest(Method method, ValidationResult validationResult) {

	}

	private static void processPatchRequest(Method method, ValidationResult validationResult) {

	}

	private static void processOptionsRequest(Method method, ValidationResult validationResult) {

	}

	private static void processHeadRequest(Method method, ValidationResult validationResult) {

	}

	private static void processGetRequest(Method method, ValidationResult validationResult) {
		GET get = method.getAnnotation(GET.class);
		String methodURL = get.value();
	}

	private static void processDeleteRequest(Method method, ValidationResult validationResult) {

	}

}
