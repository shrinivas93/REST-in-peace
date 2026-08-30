package com.shri.restinpeace.validator;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.validator.dto.ValidationResult;

public class RestClientValidator {

	private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

	private static final Set<HTTPMethod> BODY_SUPPORTED_METHODS = EnumSet.of(HTTPMethod.POST, HTTPMethod.PUT,
			HTTPMethod.PATCH, HTTPMethod.DELETE);

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

		if (validationResult.hasError()) {
			throw new RestInPeaceValidationException(validationResult);
		}

	}

	private static void validateRestClientMethod(Method method, ValidationResult validationResult) {
		Optional<Annotation> httpMethodAnnotationOptional = Stream.of(method.getAnnotations())
				.filter(annotation -> Optional
						.ofNullable(annotation.annotationType().getAnnotation(HTTPMethodMarker.class)).isPresent())
				.findFirst();
		if (httpMethodAnnotationOptional.isPresent()) {
			Annotation httpMethodAnnotation = httpMethodAnnotationOptional.get();
			HTTPMethod httpMethod = httpMethodAnnotation.annotationType().getAnnotation(HTTPMethodMarker.class).value();
			String url = getUrlValue(httpMethodAnnotation, httpMethod);
			validateUrl(method, url, validationResult);
			validateBody(method, httpMethod, validationResult);
		}

	}

	private static void validateBody(Method method, HTTPMethod httpMethod, ValidationResult validationResult) {
		long bodyParamCount = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(Body.class) != null).count();

		if (bodyParamCount > 1) {
			validationResult.addError(String.format("The method %s.%s has more than one parameter annotated with @Body.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (bodyParamCount > 0 && !BODY_SUPPORTED_METHODS.contains(httpMethod)) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Body but HTTP method %s does not support a request body.",
					method.getDeclaringClass().getName(), method.getName(), httpMethod));
		}
	}

	private static String getUrlValue(Annotation httpMethodAnnotation, HTTPMethod httpMethod) {
		switch (httpMethod) {
		case GET:
			return ((GET) httpMethodAnnotation).value();
		case POST:
			return ((POST) httpMethodAnnotation).value();
		case PUT:
			return ((PUT) httpMethodAnnotation).value();
		case DELETE:
			return ((DELETE) httpMethodAnnotation).value();
		case PATCH:
			return ((PATCH) httpMethodAnnotation).value();
		case HEAD:
			return ((HEAD) httpMethodAnnotation).value();
		case OPTIONS:
			return ((OPTIONS) httpMethodAnnotation).value();
		default:
			throw new RestInPeaceException(String.format("Unknown HTTP method %s.", httpMethod));
		}
	}

	private static void validateUrl(Method method, String url, ValidationResult validationResult) {
		if (!isURLValid(url)) {
			validationResult.addError(String.format("The method %s.%s has an invalid URL '%s'.",
					method.getDeclaringClass().getName(), method.getName(), url));
			return;
		}
		Set<String> urlPathParams = extractPathParams(url);
		Set<String> methodPathParams = Stream.of(method.getParameters())
				.map(parameter -> parameter.getAnnotation(PathParam.class)).filter(Objects::nonNull)
				.map(PathParam::value).collect(Collectors.toSet());

		for (String urlPathParam : urlPathParams) {
			if (!methodPathParams.contains(urlPathParam)) {
				validationResult.addError(String.format(
						"The method %s.%s has path param '%s' in its URL that is not annotated on any parameter with @PathParam.",
						method.getDeclaringClass().getName(), method.getName(), urlPathParam));
			}
		}
	}

	private static Set<String> extractPathParams(String url) {
		Set<String> pathParams = new HashSet<>();
		Matcher matcher = PATH_PARAM_PATTERN.matcher(url);
		while (matcher.find()) {
			pathParams.add(matcher.group(1));
		}
		return pathParams;
	}

	private static boolean isURLValid(String url) {
		try {
			new URI(PATH_PARAM_PATTERN.matcher(url).replaceAll("x"));
			return true;
		} catch (URISyntaxException e) {
			return false;
		}
	}

}
