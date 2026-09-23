package com.shri.restinpeace.validator;

import java.io.File;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.shri.restinpeace.CallAdapter;
import com.shri.restinpeace.Page;
import com.shri.restinpeace.PaginationStrategy;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.internal.RequestExecutor;
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
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.pagination.PaginationAdvance;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;
import com.shri.restinpeace.annotation.pagination.PointerKind;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.Destination;
import com.shri.restinpeace.annotation.request.Field;
import com.shri.restinpeace.annotation.request.FieldMap;
import com.shri.restinpeace.annotation.request.FormUrlEncoded;
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.Headers;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PartMap;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.request.Url;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.constant.RIPConstants;
import com.shri.restinpeace.download.DownloadProgressListener;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.upload.UploadProgressListener;

/**
 * Validates a {@code @RestClient} interface before
 * {@link com.shri.restinpeace.RIP#getClient(Class)} hands back a proxy for
 * it, so a misconfigured interface fails fast with a complete list of
 * problems instead of failing later on the first call.
 */
public class ReflectiveRestClientValidator {

	private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

	private static final Set<HTTPMethod> BODY_SUPPORTED_METHODS = EnumSet.of(HTTPMethod.POST, HTTPMethod.PUT,
			HTTPMethod.PATCH, HTTPMethod.DELETE);

	private ReflectiveRestClientValidator() {
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

		// A default or static interface method (e.g. a convenience wrapper calling
		// another method on the same interface) carries no HTTP method annotation
		// by design and isn't dispatched as an HTTP call at all - see
		// RestClientInvocationHandler.invoke - so it's exempt from every check
		// below rather than disqualifying the whole interface.
		Stream.of(methods).filter(method -> !method.isDefault() && !Modifier.isStatic(method.getModifiers()))
				.forEach(method -> {
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
					validateUrlParam(method, url, validationResult);
					if (!hasUrlParam(method)) {
						validateUrl(method, url, baseUrlOverride, validationResult);
					}
					validateBody(method, httpMethod, validationResult);
					validateReturnType(method, validationResult);
					validatePaginated(method, url, validationResult);
					validateRetry(method, validationResult);
					validateMapParam(method, QueryMap.class, "@QueryMap", validationResult);
					validateMapParam(method, HeaderMap.class, "@HeaderMap", validationResult);
					validateMultipart(method, httpMethod, validationResult);
					validateFormUrlEncoded(method, httpMethod, validationResult);
					validateTimeout(method, validationResult);
					validateHeaders(method, validationResult);
					validateDestination(method, validationResult);
					validateDownloadProgressListener(method, validationResult);
					validateUploadProgressListener(method, validationResult);
				});
	}

	private static void validateTimeout(Method method, ValidationResult validationResult) {
		Timeout timeout = method.getAnnotation(Timeout.class);
		if (timeout == null) {
			timeout = method.getDeclaringClass().getAnnotation(Timeout.class);
		}
		if (timeout == null) {
			return;
		}
		if (timeout.connectMillis() < -1) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Timeout but connectMillis must be -1 (unset) or a non-negative number of milliseconds.",
					method.getDeclaringClass().getName(), method.getName()));
		}
		if (timeout.readMillis() < -1) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Timeout but readMillis must be -1 (unset) or a non-negative number of milliseconds.",
					method.getDeclaringClass().getName(), method.getName()));
		}
	}

	private static void validateHeaders(Method method, ValidationResult validationResult) {
		Headers headers = method.getAnnotation(Headers.class);
		if (headers == null) {
			return;
		}
		for (String entry : headers.value()) {
			int colon = entry.indexOf(':');
			if (colon < 0) {
				validationResult.addError(String.format(
						"The method %s.%s has a @Headers entry '%s' with no ':' - expected 'Name: Value'.",
						method.getDeclaringClass().getName(), method.getName(), entry));
				continue;
			}
			if (entry.substring(0, colon).trim().isEmpty()) {
				validationResult.addError(String.format(
						"The method %s.%s has a @Headers entry '%s' with an empty header name.",
						method.getDeclaringClass().getName(), method.getName(), entry));
			}
		}
	}

	private static void validateMultipart(Method method, HTTPMethod httpMethod, ValidationResult validationResult) {
		boolean multipart = method.getAnnotation(Multipart.class) != null;
		List<Parameter> parts = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(Part.class) != null).collect(Collectors.toList());
		List<Parameter> partMaps = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(PartMap.class) != null).collect(Collectors.toList());

		if (multipart && !BODY_SUPPORTED_METHODS.contains(httpMethod)) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Multipart but HTTP method %s does not support a request body.",
					method.getDeclaringClass().getName(), method.getName(), httpMethod));
		}

		if (multipart && parts.isEmpty() && partMaps.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Multipart but has no @Part or @PartMap parameters.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (multipart && Stream.of(method.getParameters()).anyMatch(parameter -> parameter.getAnnotation(Body.class) != null)) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Multipart and also has a @Body parameter - use one or the other.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (multipart && method.getAnnotation(FormUrlEncoded.class) != null) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with both @Multipart and @FormUrlEncoded - use one or the other.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (!multipart && !parts.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s has a @Part parameter but is not annotated with @Multipart.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (!multipart && !partMaps.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s has a @PartMap parameter but is not annotated with @Multipart.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		parts.stream().filter(parameter -> !isSupportedPartType(parameter.getType()))
				.forEach(parameter -> validationResult.addError(String.format(
						"The method %s.%s has a @Part parameter of type %s - only String, File, byte[], and InputStream are supported.",
						method.getDeclaringClass().getName(), method.getName(), parameter.getType().getName())));

		validateMapParam(method, PartMap.class, "@PartMap", validationResult);
	}

	private static boolean isSupportedPartType(Class<?> type) {
		return type == String.class || type == File.class || type == byte[].class || type == InputStream.class;
	}

	private static void validateFormUrlEncoded(Method method, HTTPMethod httpMethod,
			ValidationResult validationResult) {
		boolean formUrlEncoded = method.getAnnotation(FormUrlEncoded.class) != null;
		List<Parameter> fields = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(Field.class) != null).collect(Collectors.toList());
		List<Parameter> fieldMaps = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(FieldMap.class) != null).collect(Collectors.toList());

		if (formUrlEncoded && !BODY_SUPPORTED_METHODS.contains(httpMethod)) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @FormUrlEncoded but HTTP method %s does not support a request body.",
					method.getDeclaringClass().getName(), method.getName(), httpMethod));
		}

		if (formUrlEncoded && fields.isEmpty() && fieldMaps.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @FormUrlEncoded but has no @Field or @FieldMap parameters.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (formUrlEncoded && Stream.of(method.getParameters()).anyMatch(parameter -> parameter.getAnnotation(Body.class) != null)) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @FormUrlEncoded and also has a @Body parameter - use one or the other.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (!formUrlEncoded && !fields.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s has a @Field parameter but is not annotated with @FormUrlEncoded.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (!formUrlEncoded && !fieldMaps.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s has a @FieldMap parameter but is not annotated with @FormUrlEncoded.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		validateMapParam(method, FieldMap.class, "@FieldMap", validationResult);
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
		if (retry == null) {
			retry = method.getDeclaringClass().getAnnotation(Retry.class);
		}
		if (retry == null) {
			return;
		}
		if (retry.times() < 1) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Retry but times must be at least 1.",
					method.getDeclaringClass().getName(), method.getName()));
		}
		if (retry.jitterFactor() < 0.0 || retry.jitterFactor() > 1.0) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Retry but jitterFactor must be between 0.0 and 1.0 inclusive.",
					method.getDeclaringClass().getName(), method.getName()));
		}
	}

	/**
	 * Return types known to be an opaque reactive/async wrapper with no
	 * JSON-mappable fields of its own (Project Reactor's {@code Mono}/
	 * {@code Flux}, RxJava 2 and 3's {@code Single}/{@code Observable}/
	 * {@code Maybe}/{@code Completable}/{@code Flowable}) - checked by
	 * fully-qualified name only, so recognizing them costs zero dependency
	 * on any of these libraries. Without a registered {@link CallAdapter},
	 * decoding into one of these via {@code ResponseDecoder}'s ordinary
	 * generic (Gson/{@code RuntimeGenericType}) path can construct a
	 * broken instance instead of throwing - Gson can often instantiate an
	 * arbitrary class via {@code Unsafe} even with no matching fields - so
	 * these are rejected by name at validation time instead, per
	 * {@code docs/design/reactor-call-adapter.md} §8.3/§8.4. This is
	 * deliberately narrower than "reject any unrecognized generic return
	 * type": a generic collection like {@code List<User>}/{@code Map<String,User>}
	 * decodes correctly via that same generic path today and must keep
	 * working unchanged - only these specific known-opaque wrapper types
	 * are denylisted.
	 */
	private static final Set<String> KNOWN_UNSUPPORTED_REACTIVE_TYPES = new HashSet<>(Arrays.asList(
			"reactor.core.publisher.Mono", "reactor.core.publisher.Flux", "io.reactivex.rxjava3.core.Single",
			"io.reactivex.rxjava3.core.Observable", "io.reactivex.rxjava3.core.Maybe",
			"io.reactivex.rxjava3.core.Completable", "io.reactivex.rxjava3.core.Flowable", "io.reactivex.Single",
			"io.reactivex.Observable", "io.reactivex.Maybe", "io.reactivex.Completable", "io.reactivex.Flowable"));

	private static void validateReturnType(Method method, ValidationResult validationResult) {
		Class<?> returnType = method.getReturnType();
		if (returnType == CompletableFuture.class) {
			validateParameterizedReturnType(method, method.getGenericReturnType(), "CompletableFuture", true,
					validationResult);
			return;
		}
		if (returnType == RipResponse.class) {
			validateParameterizedReturnType(method, method.getGenericReturnType(), "RipResponse", false,
					validationResult);
			return;
		}
		Optional<CallAdapter<?>> callAdapter = RequestExecutor.resolveCallAdapter(method);
		if (callAdapter.isPresent()) {
			validateCallAdapterResponseBodyType(method, callAdapter.get().responseBodyType(), validationResult);
			return;
		}
		if (KNOWN_UNSUPPORTED_REACTIVE_TYPES.contains(returnType.getName())) {
			validationResult.addError(String.format(
					"The method %s.%s returns %s, which RIP has no built-in support for and no registered "
							+ "CallAdapterFactory claims. If this is a Mono<T>/Flux<T>, add the rest-in-peace-reactor "
							+ "dependency and call RestInPeaceReactor.register() (or RIP.addCallAdapterFactory(...) "
							+ "directly) before building this client.",
					method.getDeclaringClass().getName(), method.getName(), returnType.getName()));
		}
	}

	/**
	 * Validates a claimed {@link CallAdapter}'s {@code responseBodyType()} -
	 * a plain {@code Class} (the common case), a {@code RipResponse<T>}
	 * (recursively validated the same way {@code CompletableFuture<RipResponse<T>>}'s
	 * inner type already is), or a {@link ParameterizedType} like
	 * {@code List<User>} - rejecting anything else, such as an unresolved
	 * type variable or wildcard, the same restriction
	 * {@code RequestExecutor.requireDecodableType} already enforces at call
	 * time for the ordinary {@code CompletableFuture<T>} path.
	 */
	private static void validateCallAdapterResponseBodyType(Method method, Type responseBodyType,
			ValidationResult validationResult) {
		if (responseBodyType instanceof ParameterizedType
				&& ((ParameterizedType) responseBodyType).getRawType() == RipResponse.class) {
			validateParameterizedReturnType(method, responseBodyType, "RipResponse", false, validationResult);
			return;
		}
		if (!(responseBodyType instanceof Class) && !(responseBodyType instanceof ParameterizedType)) {
			validationResult.addError(String.format(
					"The method %s.%s returns %s, whose registered CallAdapter declares an unsupported "
							+ "responseBodyType() (%s).",
					method.getDeclaringClass().getName(), method.getName(), method.getReturnType().getName(),
					responseBodyType));
		}
	}

	private static void validateParameterizedReturnType(Method method, Type genericReturnType, String typeName,
			boolean allowRipResponseInner, ValidationResult validationResult) {
		if (!(genericReturnType instanceof ParameterizedType)) {
			validationResult.addError(String.format("The method %s.%s returns a raw %s with no type parameter.",
					method.getDeclaringClass().getName(), method.getName(), typeName));
			return;
		}
		Type innerType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
		if (allowRipResponseInner && innerType instanceof ParameterizedType
				&& ((ParameterizedType) innerType).getRawType() == RipResponse.class) {
			validateParameterizedReturnType(method, innerType, "RipResponse", false, validationResult);
			return;
		}
		if (!(innerType instanceof Class) && !(innerType instanceof ParameterizedType)) {
			validationResult.addError(String.format(
					"The method %s.%s returns %s<%s>, which is not a supported type parameter.",
					method.getDeclaringClass().getName(), method.getName(), typeName, innerType));
			return;
		}
		if ("RipResponse".equals(typeName) && innerType == File.class) {
			validationResult.addError(String.format(
					"The method %s.%s returns RipResponse<File>, which is not supported - use a plain File return "
							+ "type with @Destination instead.",
					method.getDeclaringClass().getName(), method.getName()));
		}
	}

	/**
	 * Validates a {@code @Paginated} method or a {@code PaginationStrategy<T>}-parameter method - the
	 * chunk-2/3/4/5/7/8-supported subset of {@code docs/design/pagination-helper.md} §7:
	 * {@link PointerKind#FULL_URL}/{@link PointerKind#VALUE} pointers sourced from
	 * {@link PaginationSignalSource#RESPONSE_BODY}/{@link PaginationSignalSource#RESPONSE_HEADER}/
	 * {@link PaginationSignalSource#ITEM_FIELD}, resent via {@code @QueryParam}/
	 * {@code @PathParam}/{@code @HeaderParam}/{@code @Body} {@code @PaginationCursor}
	 * parameter(s) - one per comma-separated {@code pointerField} entry for a
	 * non-{@code @Body} composite keyset (§6.6), or one {@code @Body} parameter
	 * whose comma-separated {@code bodyField} matches that count (§6.7) - or,
	 * with {@code pointerSource = NONE}, client-driven {@code advance()}
	 * (offset/page-number arithmetic, chunk 7) via exactly one
	 * {@code @PaginationCursor} parameter; or, as the fully programmatic
	 * alternative (chunk 8, §6.8), a single {@code PaginationStrategy<T>}
	 * parameter, mutually exclusive with {@code @Paginated}. Requires a
	 * {@code Page<T>}/{@code Stream<T>}/{@code Iterator<T>} return type. Every
	 * not-yet-implemented shape ({@code CompletableFuture<Page<T>>}) is
	 * rejected by name rather than silently misbehaving at call time.
	 */
	private static void validatePaginated(Method method, String url, ValidationResult validationResult) {
		Paginated paginated = method.getAnnotation(Paginated.class);
		List<Parameter> strategyParams = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getType() == PaginationStrategy.class).collect(Collectors.toList());
		Class<?> returnType = method.getReturnType();
		boolean returnsPage = returnType == Page.class;
		boolean returnsStream = returnType == Stream.class;
		boolean returnsIterator = returnType == Iterator.class;
		boolean returnsSupportedType = returnsPage || returnsStream || returnsIterator;

		if (paginated != null && !strategyParams.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Paginated and also has a PaginationStrategy<T> "
							+ "parameter - use one or the other.",
					method.getDeclaringClass().getName(), method.getName()));
			return;
		}
		if (returnsSupportedType && paginated == null && strategyParams.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s returns %s<T> but is not annotated with @Paginated and has no "
							+ "PaginationStrategy<T> parameter.",
					method.getDeclaringClass().getName(), method.getName(), returnType.getSimpleName()));
			return;
		}
		if (paginated == null && strategyParams.isEmpty()) {
			return;
		}
		// A raw RipResponse/CompletableFuture (no type parameter at all) is already
		// flagged by validateReturnType regardless of @Paginated/PaginationStrategy -
		// skip adding a second, overlapping message about the same underlying "raw
		// generic return type" mistake on the same method.
		if ((returnType == RipResponse.class || returnType == CompletableFuture.class)
				&& !(method.getGenericReturnType() instanceof ParameterizedType)) {
			return;
		}
		if (returnType == RipResponse.class && isStreamOrIteratorInner(method.getGenericReturnType())) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Paginated and returns RipResponse<%s<T>>, which is not "
							+ "supported - auto-flattening spans an unknown number of underlying calls, so there is "
							+ "no single response to wrap; use Page<T> and its rawResponse() instead.",
					method.getDeclaringClass().getName(), method.getName(),
					streamOrIteratorInnerName(method.getGenericReturnType())));
			return;
		}
		if (!returnsSupportedType) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with @Paginated or has a PaginationStrategy<T> parameter but "
							+ "does not return Page<T>, Stream<T>, or Iterator<T> - wrapping in CompletableFuture is "
							+ "not implemented yet.",
					method.getDeclaringClass().getName(), method.getName()));
			return;
		}
		String typeName = returnsPage ? "Page" : returnsStream ? "Stream" : "Iterator";
		validateParameterizedReturnType(method, method.getGenericReturnType(), typeName, false, validationResult);

		// Only reported when there's no static URL - validateUrlParam already reports
		// a more specific "has both a @Url parameter and a static URL" error for that
		// combination; stacking a second, less specific message about the same
		// underlying @Url misuse on the same method would be redundant noise.
		if (hasUrlParam(method) && RIPConstants.DEFAULT.equals(url)) {
			validationResult.addError(String.format(
					"The method %s.%s is annotated with both @Paginated and @Url - remove one or the other.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (paginated == null) {
			validatePaginationStrategyParams(method, method.getGenericReturnType(), returnType, strategyParams,
					validationResult);
			return;
		}

		List<Parameter> cursorParams = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(PaginationCursor.class) != null)
				.collect(Collectors.toList());

		if (paginated.advance() != PaginationAdvance.NONE && paginated.pointerSource() != PaginationSignalSource.NONE) {
			validationResult.addError(String.format(
					"The method %s.%s's @Paginated sets advance() but pointerSource is not NONE - advance() is "
							+ "only meaningful for client-driven pagination with no server-given pointer "
							+ "(pointerSource = NONE).",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (paginated.advance() == PaginationAdvance.INCREMENT_BY_PAGE_SIZE && paginated.pageSize() <= 0) {
			validationResult.addError(String.format(
					"The method %s.%s's @Paginated sets advance = INCREMENT_BY_PAGE_SIZE, which needs pageSize() "
							+ "to be a positive number.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (paginated.pointerKind() == PointerKind.FULL_URL) {
			if (!cursorParams.isEmpty()) {
				validationResult.addError(String.format(
						"The method %s.%s's @Paginated has pointerKind = FULL_URL but also has a "
								+ "@PaginationCursor parameter - a full URL is followed as-is, with nothing to inject.",
						method.getDeclaringClass().getName(), method.getName()));
			}
			validatePaginationFieldNonEmpty(method, paginated.pointerField(), "pointerField", validationResult);
		} else {
			validatePaginatedValuePointer(method, paginated, cursorParams, validationResult);
		}

		validatePaginationSignal(method, paginated.hasMoreSource(), paginated.hasMoreField(), "hasMoreSource",
				"hasMoreField", validationResult);
		validatePaginationSignal(method, paginated.totalSource(), paginated.totalField(), "totalSource", "totalField",
				validationResult);
		validatePaginationSignal(method, paginated.totalPagesSource(), paginated.totalPagesField(), "totalPagesSource",
				"totalPagesField", validationResult);

		for (Parameter cursorParam : cursorParams) {
			validatePaginationCursorParam(method, cursorParam, validationResult);
		}
	}

	/**
	 * Validates the {@code PaginationStrategy<T>}-parameter path (§6.8, chunk 8): exactly one such parameter is
	 * allowed, and when both its {@code T} and the method's own {@code Page<T>}/{@code Stream<T>}/{@code Iterator<T>}
	 * return type argument are reflectively known (i.e. neither is a raw, unparameterized use), they must match -
	 * otherwise the items {@code PaginationContext<T>.items()} hands back at runtime silently wouldn't be the type
	 * the consumer's strategy lambda declared.
	 */
	private static void validatePaginationStrategyParams(Method method, Type genericReturnType, Class<?> returnType,
			List<Parameter> strategyParams, ValidationResult validationResult) {
		if (strategyParams.size() > 1) {
			validationResult.addError(String.format(
					"The method %s.%s has more than one PaginationStrategy<T> parameter.",
					method.getDeclaringClass().getName(), method.getName()));
			return;
		}
		Type itemType = genericReturnType instanceof ParameterizedType
				? ((ParameterizedType) genericReturnType).getActualTypeArguments()[0] : null;
		Type strategyGenericType = strategyParams.get(0).getParameterizedType();
		Type strategyItemType = strategyGenericType instanceof ParameterizedType
				? ((ParameterizedType) strategyGenericType).getActualTypeArguments()[0] : null;
		if (itemType != null && strategyItemType != null && !itemType.equals(strategyItemType)) {
			validationResult.addError(String.format(
					"The method %s.%s's PaginationStrategy<%s> parameter doesn't match its %s<%s> return type - "
							+ "they must share the same item type.",
					method.getDeclaringClass().getName(), method.getName(), strategyItemType,
					returnType.getSimpleName(), itemType));
		}
	}

	/**
	 * Whether {@code RipResponse<T>}'s {@code T} is itself {@code Stream<?>}/{@code Iterator<?>} - see §7's
	 * dedicated rejection for that shape. Callers only reach this once the raw-generic-return-type guard above
	 * has confirmed {@code ripResponseGenericType} is parameterized, so that case isn't re-checked here.
	 */
	private static boolean isStreamOrIteratorInner(Type ripResponseGenericType) {
		Type inner = ((ParameterizedType) ripResponseGenericType).getActualTypeArguments()[0];
		if (!(inner instanceof ParameterizedType)) {
			return false;
		}
		Type innerRawType = ((ParameterizedType) inner).getRawType();
		return innerRawType == Stream.class || innerRawType == Iterator.class;
	}

	private static String streamOrIteratorInnerName(Type ripResponseGenericType) {
		Type inner = ((ParameterizedType) ripResponseGenericType).getActualTypeArguments()[0];
		Type innerRawType = ((ParameterizedType) inner).getRawType();
		return innerRawType == Stream.class ? "Stream" : "Iterator";
	}

	private static void validatePaginatedValuePointer(Method method, Paginated paginated,
			List<Parameter> cursorParams, ValidationResult validationResult) {
		PaginationSignalSource source = paginated.pointerSource();
		if (source == PaginationSignalSource.NONE) {
			if (paginated.advance() == PaginationAdvance.NONE) {
				validationResult.addError(String.format(
						"The method %s.%s's @Paginated has pointerSource = NONE, which needs advance() to be set "
								+ "for client-driven pagination.",
						method.getDeclaringClass().getName(), method.getName()));
				return;
			}
			validateAdvanceCursorParam(method, cursorParams, validationResult);
			return;
		}
		validatePaginationFieldNonEmpty(method, paginated.pointerField(), "pointerField", validationResult);
		if (paginated.pointerField().isEmpty()) {
			return;
		}
		int fieldCount = paginated.pointerField().split(",", -1).length;
		if (fieldCount > 1 && source != PaginationSignalSource.ITEM_FIELD) {
			validationResult.addError(String.format(
					"The method %s.%s's @Paginated has a comma-separated pointerField - a composite pointer is "
							+ "only supported for pointerSource = ITEM_FIELD.",
					method.getDeclaringClass().getName(), method.getName()));
			return;
		}
		validateCursorParamCount(method, cursorParams, fieldCount, validationResult);
	}

	/**
	 * A {@code VALUE} pointer needs either exactly {@code fieldCount} non-{@code @Body} cursor parameters,
	 * positionally matched to {@code pointerField}'s comma-separated entries (§6.6 - N-way composite keyset via
	 * N separate carriers), or exactly one {@code @Body} cursor parameter whose comma-separated
	 * {@code bodyField} names the same {@code fieldCount} values (§6.7 - composite keyset into one JSON body).
	 */
	private static void validateCursorParamCount(Method method, List<Parameter> cursorParams, int fieldCount,
			ValidationResult validationResult) {
		List<Parameter> bodyParams = cursorParams.stream().filter(p -> p.getAnnotation(Body.class) != null)
				.collect(Collectors.toList());
		if (!bodyParams.isEmpty()) {
			if (cursorParams.size() > 1) {
				validationResult.addError(String.format(
						"The method %s.%s has a @PaginationCursor stacked on @Body alongside other "
								+ "@PaginationCursor parameters - a @Body carrier must be the method's only "
								+ "@PaginationCursor parameter.",
						method.getDeclaringClass().getName(), method.getName()));
				return;
			}
			String bodyField = bodyParams.get(0).getAnnotation(PaginationCursor.class).bodyField();
			if (bodyField.isEmpty()) {
				return; // already flagged by validatePaginationCursorParam
			}
			int bodyFieldCount = bodyField.split(",", -1).length;
			if (bodyFieldCount != fieldCount) {
				validationResult.addError(String.format(
						"The method %s.%s's @Paginated has a pointerField naming %d value(s) but its @Body "
								+ "@PaginationCursor's bodyField names %d - they must match.",
						method.getDeclaringClass().getName(), method.getName(), fieldCount, bodyFieldCount));
			}
			return;
		}
		if (cursorParams.size() != fieldCount) {
			validationResult.addError(String.format(
					"The method %s.%s's @Paginated has pointerKind = VALUE with pointerSource != NONE, which "
							+ "needs %d @PaginationCursor parameter(s) (matching pointerField's %d "
							+ "comma-separated entries) - found %d.",
					method.getDeclaringClass().getName(), method.getName(), fieldCount, fieldCount,
					cursorParams.size()));
		}
	}

	/**
	 * Client-driven pagination (§6.5's advance-based fallback, chunk 7) has exactly one computed value - an offset
	 * or a page number - so it needs exactly one {@code @PaginationCursor} parameter, on any one of the four
	 * carriers; unlike {@link #validateCursorParamCount}, there's no {@code pointerField} entry count to match it
	 * against.
	 */
	private static void validateAdvanceCursorParam(Method method, List<Parameter> cursorParams,
			ValidationResult validationResult) {
		if (cursorParams.size() != 1) {
			validationResult.addError(String.format(
					"The method %s.%s's @Paginated has pointerSource = NONE with advance() set, which needs "
							+ "exactly one @PaginationCursor parameter to carry the client-computed offset/page "
							+ "value - found %d.",
					method.getDeclaringClass().getName(), method.getName(), cursorParams.size()));
		}
	}

	private static void validatePaginationSignal(Method method, PaginationSignalSource source, String field,
			String sourceAttributeName, String fieldAttributeName, ValidationResult validationResult) {
		if (source == PaginationSignalSource.NONE) {
			return;
		}
		if (source == PaginationSignalSource.ITEM_FIELD) {
			validationResult.addError(String.format(
					"The method %s.%s's @Paginated sets %s = ITEM_FIELD, which is only meaningful for "
							+ "pointerSource.",
					method.getDeclaringClass().getName(), method.getName(), sourceAttributeName));
			return;
		}
		validatePaginationFieldNonEmpty(method, field, fieldAttributeName, validationResult);
	}

	private static void validatePaginationFieldNonEmpty(Method method, String field, String attributeName,
			ValidationResult validationResult) {
		if (field.isEmpty()) {
			validationResult.addError(String.format("The method %s.%s's @Paginated must set %s.",
					method.getDeclaringClass().getName(), method.getName(), attributeName));
		}
	}

	private static void validatePaginationCursorParam(Method method, Parameter cursorParam,
			ValidationResult validationResult) {
		boolean onQuery = cursorParam.getAnnotation(QueryParam.class) != null;
		boolean onPath = cursorParam.getAnnotation(PathParam.class) != null;
		boolean onHeader = cursorParam.getAnnotation(HeaderParam.class) != null;
		boolean onBody = cursorParam.getAnnotation(Body.class) != null;
		int carrierCount = (onQuery ? 1 : 0) + (onPath ? 1 : 0) + (onHeader ? 1 : 0) + (onBody ? 1 : 0);
		if (carrierCount != 1) {
			validationResult.addError(String.format(
					"The method %s.%s has a @PaginationCursor parameter that must be stacked on exactly one of "
							+ "@QueryParam/@PathParam/@HeaderParam/@Body.",
					method.getDeclaringClass().getName(), method.getName()));
			return;
		}
		String bodyField = cursorParam.getAnnotation(PaginationCursor.class).bodyField();
		if (onBody) {
			if (bodyField.isEmpty()) {
				validationResult.addError(String.format(
						"The method %s.%s has a @PaginationCursor stacked on @Body but bodyField is empty - set it "
								+ "to the JSON path inside the body to write the next-page value to.",
						method.getDeclaringClass().getName(), method.getName()));
			}
			if (!isMapOfStringToObject(cursorParam)) {
				validationResult.addError(String.format(
						"The method %s.%s has a @PaginationCursor stacked on @Body but the parameter's declared "
								+ "type is not Map<String,Object>.",
						method.getDeclaringClass().getName(), method.getName()));
			}
			return;
		}
		if (!bodyField.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s has a @PaginationCursor with a non-empty bodyField but is not stacked on "
							+ "@Body - bodyField is only meaningful there.",
					method.getDeclaringClass().getName(), method.getName()));
		}
		Class<?> type = cursorParam.getType();
		if (type != String.class && type != int.class && type != long.class) {
			validationResult.addError(String.format(
					"The method %s.%s has a @PaginationCursor parameter of type %s - only String, int, and long "
							+ "are supported.",
					method.getDeclaringClass().getName(), method.getName(), type.getName()));
		}
	}

	/** Whether {@code parameter}'s declared type is exactly {@code Map<String,Object>} - the required shape for a {@code @Body @PaginationCursor} carrier (§6.7). */
	private static boolean isMapOfStringToObject(Parameter parameter) {
		if (!Map.class.isAssignableFrom(parameter.getType())) {
			return false;
		}
		Type genericType = parameter.getParameterizedType();
		if (!(genericType instanceof ParameterizedType)) {
			return false;
		}
		Type[] typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
		return typeArguments.length == 2 && typeArguments[0] == String.class && typeArguments[1] == Object.class;
	}

	/**
	 * Whether the method's return type is {@code File} directly, or
	 * {@code CompletableFuture<File>} - the two shapes a {@code @Destination}
	 * parameter is valid on. {@code RipResponse<File>} is rejected by
	 * {@link #validateParameterizedReturnType} instead of being treated as
	 * File-returning here.
	 */
	private static boolean returnsFile(Method method) {
		Class<?> returnType = method.getReturnType();
		if (returnType == File.class) {
			return true;
		}
		if (returnType == CompletableFuture.class && method.getGenericReturnType() instanceof ParameterizedType) {
			Type innerType = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
			return innerType == File.class;
		}
		return false;
	}

	/**
	 * Whether the method's return type is {@code byte[]}, {@code File}, or
	 * either wrapped in {@code CompletableFuture}/{@code RipResponse} - the
	 * shapes a {@code DownloadProgressListener} parameter is meaningful on.
	 */
	private static boolean returnsDownloadableBody(Method method) {
		Class<?> returnType = method.getReturnType();
		if (returnType == byte[].class || returnsFile(method)) {
			return true;
		}
		if ((returnType == CompletableFuture.class || returnType == RipResponse.class)
				&& method.getGenericReturnType() instanceof ParameterizedType) {
			Type innerType = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
			return innerType == byte[].class;
		}
		return false;
	}

	private static boolean hasUrlParam(Method method) {
		return Stream.of(method.getParameters()).anyMatch(parameter -> parameter.getAnnotation(Url.class) != null);
	}

	private static void validateUrlParam(Method method, String url, ValidationResult validationResult) {
		List<Parameter> urlParams = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(Url.class) != null).collect(Collectors.toList());

		if (urlParams.size() > 1) {
			validationResult.addError(String.format("The method %s.%s has more than one parameter annotated with @Url.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		urlParams.stream().filter(parameter -> parameter.getType() != String.class)
				.forEach(parameter -> validationResult.addError(String.format(
						"The method %s.%s has a @Url parameter of type %s - only String is supported.",
						method.getDeclaringClass().getName(), method.getName(), parameter.getType().getName())));

		if (!urlParams.isEmpty() && !RIPConstants.DEFAULT.equals(url)) {
			validationResult.addError(String.format(
					"The method %s.%s has both a @Url parameter and a static URL '%s' - remove one or the other.",
					method.getDeclaringClass().getName(), method.getName(), url));
		}

		// A @Url method bypasses @BaseUrl/a runtime base URL/@PathParam entirely -
		// there's no URL template left for @PathParam to apply to (see
		// UrlResolver.resolveUrl) - so a @PathParam alongside @Url is always
		// silently ignored at runtime rather than doing anything. Catch it here
		// instead of leaving it a confusing, silently-dead annotation.
		if (!urlParams.isEmpty() && Stream.of(method.getParameters())
				.anyMatch(parameter -> parameter.getAnnotation(PathParam.class) != null)) {
			validationResult.addError(String.format(
					"The method %s.%s has both a @Url parameter and a @PathParam parameter - @PathParam has no "
							+ "effect when @Url is used.",
					method.getDeclaringClass().getName(), method.getName()));
		}
	}

	private static void validateDestination(Method method, ValidationResult validationResult) {
		List<Parameter> destinations = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getAnnotation(Destination.class) != null).collect(Collectors.toList());
		boolean returnsFile = returnsFile(method);

		if (destinations.size() > 1) {
			validationResult.addError(String.format(
					"The method %s.%s has more than one parameter annotated with @Destination.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		destinations.stream().filter(parameter -> parameter.getType() != File.class)
				.forEach(parameter -> validationResult.addError(String.format(
						"The method %s.%s has a @Destination parameter of type %s - only File is supported.",
						method.getDeclaringClass().getName(), method.getName(), parameter.getType().getName())));

		if (!returnsFile && !destinations.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s has a @Destination parameter but does not return File.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (returnsFile && destinations.isEmpty()) {
			validationResult.addError(String.format(
					"The method %s.%s returns File but has no @Destination parameter to write the response to.",
					method.getDeclaringClass().getName(), method.getName()));
		}
	}

	private static void validateDownloadProgressListener(Method method, ValidationResult validationResult) {
		List<Parameter> listeners = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getType() == DownloadProgressListener.class)
				.collect(Collectors.toList());

		if (listeners.size() > 1) {
			validationResult.addError(String.format(
					"The method %s.%s has more than one DownloadProgressListener parameter.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (!listeners.isEmpty() && !returnsDownloadableBody(method)) {
			validationResult.addError(String.format(
					"The method %s.%s has a DownloadProgressListener parameter but does not return byte[] or File.",
					method.getDeclaringClass().getName(), method.getName()));
		}
	}

	private static void validateUploadProgressListener(Method method, ValidationResult validationResult) {
		List<Parameter> listeners = Stream.of(method.getParameters())
				.filter(parameter -> parameter.getType() == UploadProgressListener.class)
				.collect(Collectors.toList());

		if (listeners.size() > 1) {
			validationResult.addError(String.format(
					"The method %s.%s has more than one UploadProgressListener parameter.",
					method.getDeclaringClass().getName(), method.getName()));
		}

		if (!listeners.isEmpty() && method.getAnnotation(Multipart.class) == null) {
			validationResult.addError(String.format(
					"The method %s.%s has an UploadProgressListener parameter but is not annotated with @Multipart.",
					method.getDeclaringClass().getName(), method.getName()));
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

		// The reverse direction: a @PathParam whose name doesn't appear in the URL
		// at all (a stale annotation left over from a renamed/edited URL template, or
		// a case-sensitive typo) is never substituted - resolvePathParams's
		// url.replace("{" + pathParam.value() + "}", ...) is then a silent no-op,
		// so the literal, unresolved "{name}" token goes out on the wire instead of
		// the intended value. Caught here instead of only surfacing as a broken
		// request at call time.
		methodPathParams.stream().filter(methodPathParam -> !urlPathParams.contains(methodPathParam))
				.forEach(methodPathParam -> validationResult.addError(String.format(
						"The method %s.%s has a @PathParam('%s') that does not appear as '{%s}' in its URL.",
						method.getDeclaringClass().getName(), method.getName(), methodPathParam, methodPathParam)));
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
