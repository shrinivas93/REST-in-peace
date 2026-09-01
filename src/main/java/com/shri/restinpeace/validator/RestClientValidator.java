package com.shri.restinpeace.validator;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.shri.restinpeace.annotation.marker.BaseUrl;
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
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.validator.dto.ValidationResult;

/**
 * Validates a {@code @RestClient} interface before
 * {@link com.shri.restinpeace.RIP#getClient(Class)} hands back a proxy for
 * it, so a misconfigured interface fails fast with a complete list of
 * problems instead of failing later on the first call.
 */
public class RestClientValidator {

	private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

	private static final Set<HTTPMethod> BODY_SUPPORTED_METHODS = EnumSet.of(HTTPMethod.POST, HTTPMethod.PUT,
			HTTPMethod.PATCH, HTTPMethod.DELETE);

	private RestClientValidator() {
		// private constructor to hide the implicit public one
	}

	/**
	 * Validates the given interface, collecting every problem found (missing
	 * {@code @RestClient}, missing/duplicate HTTP method annotations, invalid
	 * URLs, unmatched path params, misused {@code @Body}, unsupported
	 * {@code CompletableFuture} type parameters) rather than stopping at the
	 * first one.
	 *
	 * @param <T>        the rest client interface type
	 * @param restClient the interface to validate
	 * @throws RestInPeaceValidationException if any validation error is found
	 */
	public static <T> void validate(Class<T> restClient) throws RestInPeaceValidationException {
		validate(restClient, null);
	}

	/**
	 * Same as {@link #validate(Class)}, but a relative method URL is
	 * validated against {@code baseUrlOverride} instead of requiring
	 * {@code @BaseUrl} on the interface - for
	 * {@link com.shri.restinpeace.RIP#getClient(Class, String)}, where the
	 * base URL is supplied at runtime instead of declared on the interface.
	 *
	 * @param <T>             the rest client interface type
	 * @param restClient      the interface to validate
	 * @param baseUrlOverride the runtime base URL a relative method URL is
	 *                        validated against, or {@code null} to require
	 *                        {@code @BaseUrl} on the interface instead
	 * @throws RestInPeaceValidationException if any validation error is found
	 */
	public static <T> void validate(Class<T> restClient, String baseUrlOverride)
			throws RestInPeaceValidationException {

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

		Stream.of(methods).forEach(method -> {
			long httpMethodAnnotationCount = Stream.of(method.getAnnotations())
					.filter(annotation -> annotation.annotationType().getAnnotation(HTTPMethodMarker.class) != null)
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
				validateRestClientMethod(method, baseUrlOverride, validationResult);
			}
		});

		if (validationResult.hasError()) {
			throw new RestInPeaceValidationException(validationResult);
		}

	}

	private static void validateRestClientMethod(Method method, String baseUrlOverride,
			ValidationResult validationResult) {
		Stream.of(method.getAnnotations())
				.filter(annotation -> annotation.annotationType().getAnnotation(HTTPMethodMarker.class) != null)
				.findFirst().ifPresent(httpMethodAnnotation -> {
					HTTPMethod httpMethod = httpMethodAnnotation.annotationType().getAnnotation(HTTPMethodMarker.class)
							.value();
					String url = getUrlValue(httpMethodAnnotation, httpMethod);
					validateUrl(method, url, baseUrlOverride, validationResult);
					validateBody(method, httpMethod, validationResult);
					validateReturnType(method, validationResult);
					validateRetry(method, validationResult);
					validateMapParam(method, QueryMap.class, "@QueryMap", validationResult);
					validateMapParam(method, HeaderMap.class, "@HeaderMap", validationResult);
				});
	}

	private static void validateMapParam(Method method, Class<? extends Annotation> annotationType,
			String annotationName, ValidationResult validationResult) {
		List<Parameter> annotated = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(annotationType) != null).collect(Collectors.toList());

		if (annotated.size() > 1) {
			validationResult.addError(String.format("The method %s.%s has more than one parameter annotated with %s.",
					method.getDeclaringClass().getName(), method.getName(), annotationName));
		}

		annotated.stream().filter(parameter -> !Map.class.isAssignableFrom(parameter.getType()))
				.forEach(parameter -> validationResult.addError(String.format(
						"The method %s.%s has a parameter annotated with %s that is not a Map.",
						method.getDeclaringClass().getName(), method.getName(), annotationName)));
	}

	private static void validateRetry(Method method, ValidationResult validationResult) {
		Retry retry = method.getAnnotation(Retry.class);
		if (retry != null && retry.times() < 1) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Retry but times must be at least 1.",
					method.getDeclaringClass().getName(), method.getName()));
		}
	}

	private static void validateReturnType(Method method, ValidationResult validationResult) {
		if (method.getReturnType() != CompletableFuture.class) {
			return;
		}
		Type genericReturnType = method.getGenericReturnType();
		if (!(genericReturnType instanceof ParameterizedType)) {
			validationResult.addError(String.format(
					"The method %s.%s returns a raw CompletableFuture with no type parameter.",
					method.getDeclaringClass().getName(), method.getName()));
			return;
		}
		Type innerType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
		if (!(innerType instanceof Class)) {
			validationResult.addError(String.format(
					"The method %s.%s returns CompletableFuture<%s>, which is not a supported type parameter.",
					method.getDeclaringClass().getName(), method.getName(), innerType));
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

	private static void validateUrl(Method method, String url, String baseUrlOverride,
			ValidationResult validationResult) {
		String effectiveUrl = url;
		if (!isAbsoluteUrl(url)) {
			String base = resolveBase(method, baseUrlOverride);
			if (base == null) {
				validationResult.addError(String.format(
						"The method %s.%s has a relative URL '%s' but the interface is not annotated with @BaseUrl "
								+ "and no runtime base URL was given to RIP.getClient(...).",
						method.getDeclaringClass().getName(), method.getName(), url));
				return;
			}
			effectiveUrl = combineWithBaseUrl(base, url);
		}
		if (!isURLValid(effectiveUrl)) {
			validationResult.addError(String.format("The method %s.%s has an invalid URL '%s'.",
					method.getDeclaringClass().getName(), method.getName(), effectiveUrl));
			return;
		}
		Set<String> urlPathParams = extractPathParams(effectiveUrl);
		Set<String> methodPathParams = Stream.of(method.getParameters())
				.map(parameter -> parameter.getAnnotation(PathParam.class)).filter(Objects::nonNull)
				.map(PathParam::value).collect(Collectors.toSet());

		urlPathParams.stream().filter(urlPathParam -> !methodPathParams.contains(urlPathParam))
				.forEach(urlPathParam -> validationResult.addError(String.format(
						"The method %s.%s has path param '%s' in its URL that is not annotated on any parameter with @PathParam.",
						method.getDeclaringClass().getName(), method.getName(), urlPathParam)));
	}

	private static boolean isAbsoluteUrl(String url) {
		return url.startsWith("http://") || url.startsWith("https://");
	}

	private static String resolveBase(Method method, String baseUrlOverride) {
		if (baseUrlOverride != null) {
			return baseUrlOverride;
		}
		BaseUrl baseUrl = method.getDeclaringClass().getAnnotation(BaseUrl.class);
		return baseUrl == null ? null : baseUrl.value();
	}

	private static String combineWithBaseUrl(String base, String url) {
		if (base.endsWith("/") && url.startsWith("/")) {
			return base + url.substring(1);
		}
		if (!base.endsWith("/") && !url.startsWith("/")) {
			return base + "/" + url;
		}
		return base + url;
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
