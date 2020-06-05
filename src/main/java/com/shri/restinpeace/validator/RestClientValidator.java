package com.shri.restinpeace.validator;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.stream.Stream;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
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
			throw new RestInPeaceValidationException(validationResult);
		}

		Method[] methods = restClient.getMethods();

		// Checking if any method is not annotated with HTTP method annotations
		Stream.of(methods)
				.filter(method -> Stream.of(method.getAnnotations()).noneMatch(annotation -> Optional
						.ofNullable(annotation.annotationType().getAnnotation(HTTPMethodMarker.class)).isPresent()))
				.map(method -> String.format(
						"The method %s.%s is not annotated with any of the HTTP method annotations.",
						restClient.getName(), method.getName()))
				.forEach(validationResult::addError);

		// Checking if any method is annotated with more than 1 HTTP method annotations
		Stream.of(methods).filter(method -> Stream.of(method.getAnnotations())
				.filter(annotation -> Optional
						.ofNullable(annotation.annotationType().getAnnotation(HTTPMethodMarker.class)).isPresent())
				.count() > 1)
				.map(method -> String.format("The method %s.%s has more than one HTTP method annotations.",
						restClient.getName(), method.getName()))
				.forEach(validationResult::addError);
		if (validationResult.hasError()) {
			throw new RestInPeaceValidationException(validationResult);
		}
	}

}
