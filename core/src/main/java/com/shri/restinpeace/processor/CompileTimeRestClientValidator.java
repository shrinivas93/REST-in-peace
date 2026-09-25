package com.shri.restinpeace.processor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
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

/**
 * The compile-time counterpart of
 * {@link com.shri.restinpeace.validator.ReflectiveRestClientValidator} - the same
 * semantic rules (an invalid {@code @Retry}, a malformed {@code @Headers}
 * entry, an unmatched path param, ...), reimplemented against
 * {@code javax.lang.model}'s {@code ExecutableElement}/{@code VariableElement}
 * instead of {@code java.lang.reflect}'s {@code Method}/{@code Parameter},
 * so a {@code @RestClient} interface that would fail
 * {@code ReflectiveRestClientValidator.validate(...)} at the first
 * {@code RIP.getClient(...)} call instead fails {@code javac} outright - see
 * {@code docs/design/compile-time-proxy-generation.md} step 4.
 *
 * <p>
 * Deliberately a separate, independent implementation rather than a shared
 * abstraction over both worlds (the design doc's §7 open question) - the two
 * APIs differ enough (e.g. resolving a {@code Class<?>}-valued annotation
 * attribute) that forcing a shared abstraction wasn't worth it. Runs on
 * every {@code @RestClient} interface the processor sees, whether or not
 * that interface also happens to fall within the codegen-supported shape -
 * an interface can be semantically invalid yet still structurally
 * "supported" (e.g. {@code @Multipart} on a {@code GET} method), and should
 * fail the build either way, not just silently fall back to the reflective
 * proxy and blow up on first use.
 *
 * <p>
 * One deliberate gap: {@code ReflectiveRestClientValidator} requires either
 * {@code @BaseUrl} on the interface or a runtime base URL
 * ({@code RIP.getClient(Class, String)}/{@code RipClientConfig}) for a
 * relative method URL - which call overload ends up used is inherently a
 * runtime fact, unknowable here. So a relative URL with no {@code @BaseUrl}
 * is not flagged as an error at compile time; every other URL check (syntax,
 * unmatched path params) still runs directly against the method's own URL
 * template, since those never depend on the base at all.
 */
final class CompileTimeRestClientValidator {

	private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

	private static final Set<HTTPMethod> BODY_SUPPORTED_METHODS = new HashSet<>(
			java.util.Arrays.asList(HTTPMethod.POST, HTTPMethod.PUT, HTTPMethod.PATCH, HTTPMethod.DELETE));

	private CompileTimeRestClientValidator() {
		// static utility class
	}

	/**
	 * Validates every method of {@code interfaceElement}, reporting every
	 * problem found (not just the first) as a compile {@code ERROR} via
	 * {@code env}'s {@code Messager}.
	 *
	 * @return {@code true} if no problems were found, {@code false} if at
	 *         least one error was reported
	 */
	static boolean validate(TypeElement interfaceElement, ProcessingEnvironment env) {
		Reporter reporter = new Reporter(env.getMessager());
		for (Element enclosed : interfaceElement.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.METHOD) {
				continue;
			}
			ExecutableElement method = (ExecutableElement) enclosed;
			// A default or static interface method carries no HTTP method annotation by
			// design and isn't dispatched as an HTTP call at all (see
			// ReflectiveRestClientValidator.validate and RestClientProcessor's own
			// default/static skip before codegen) - exempt from every check below
			// instead of failing compilation outright.
			if (method.getModifiers().contains(javax.lang.model.element.Modifier.DEFAULT)
					|| method.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
				continue;
			}
			HttpMethodAndUrl httpMethodAndUrl = validateHttpMethodAnnotation(method, reporter);
			if (httpMethodAndUrl != null) {
				validateUrlParam(method, httpMethodAndUrl.urlTemplate, reporter);
				if (!hasUrlParam(method)) {
					validateUrl(method, httpMethodAndUrl.urlTemplate, reporter);
				}
				validateBody(method, httpMethodAndUrl.httpMethod, reporter);
				validateReturnType(method, env.getTypeUtils(), reporter);
				validatePaginated(method, httpMethodAndUrl.urlTemplate, env, reporter);
				validateRetry(method, reporter);
				validateMapParam(method, QueryMap.class, "@QueryMap", env, reporter);
				validateMapParam(method, HeaderMap.class, "@HeaderMap", env, reporter);
				validateMultipart(method, httpMethodAndUrl.httpMethod, env, reporter);
				validateFormUrlEncoded(method, httpMethodAndUrl.httpMethod, env, reporter);
				validateTimeout(method, reporter);
				validateHeaders(method, reporter);
				validateDestination(method, env.getTypeUtils(), reporter);
				validateDownloadProgressListener(method, env.getTypeUtils(), reporter);
				validateUploadProgressListener(method, reporter);
			}
		}
		return !reporter.hasError;
	}

	private static HttpMethodAndUrl validateHttpMethodAnnotation(ExecutableElement method, Reporter reporter) {
		List<HttpMethodAndUrl> matches = new ArrayList<>();
		addIfPresent(matches, method.getAnnotation(GET.class), HTTPMethod.GET, GET::value);
		addIfPresent(matches, method.getAnnotation(POST.class), HTTPMethod.POST, POST::value);
		addIfPresent(matches, method.getAnnotation(PUT.class), HTTPMethod.PUT, PUT::value);
		addIfPresent(matches, method.getAnnotation(PATCH.class), HTTPMethod.PATCH, PATCH::value);
		addIfPresent(matches, method.getAnnotation(DELETE.class), HTTPMethod.DELETE, DELETE::value);
		addIfPresent(matches, method.getAnnotation(HEAD.class), HTTPMethod.HEAD, HEAD::value);
		addIfPresent(matches, method.getAnnotation(OPTIONS.class), HTTPMethod.OPTIONS, OPTIONS::value);

		if (matches.isEmpty()) {
			reporter.error(String.format("The method %s is not annotated with any of the HTTP method annotations.",
					qualifiedName(method)), method);
			return null;
		}
		if (matches.size() > 1) {
			reporter.error(
					String.format("The method %s has more than one HTTP method annotations.", qualifiedName(method)),
					method);
			return null;
		}
		return matches.get(0);
	}

	private static <A> void addIfPresent(List<HttpMethodAndUrl> matches, A annotation, HTTPMethod httpMethod,
			java.util.function.Function<A, String> urlOf) {
		if (annotation != null) {
			matches.add(new HttpMethodAndUrl(httpMethod, urlOf.apply(annotation)));
		}
	}

	private static void validateBody(ExecutableElement method, HTTPMethod httpMethod, Reporter reporter) {
		long bodyParamCount = method.getParameters().stream().filter(p -> p.getAnnotation(Body.class) != null)
				.count();
		if (bodyParamCount > 1) {
			reporter.error(String.format("The method %s has more than one parameter annotated with @Body.",
					qualifiedName(method)), method);
		}
		if (bodyParamCount > 0 && !BODY_SUPPORTED_METHODS.contains(httpMethod)) {
			reporter.error(
					String.format("The method %s is annotated with @Body but HTTP method %s does not support a "
							+ "request body.", qualifiedName(method), httpMethod),
					method);
		}
	}

	private static void validateRetry(ExecutableElement method, Reporter reporter) {
		Retry retry = method.getAnnotation(Retry.class);
		if (retry == null) {
			retry = method.getEnclosingElement().getAnnotation(Retry.class);
		}
		if (retry == null) {
			return;
		}
		if (retry.times() < 1) {
			reporter.error(String.format("The method %s is annotated with @Retry but times must be at least 1.",
					qualifiedName(method)), method);
		}
		if (retry.jitterFactor() < 0.0 || retry.jitterFactor() > 1.0) {
			reporter.error(String.format(
					"The method %s is annotated with @Retry but jitterFactor must be between 0.0 and 1.0 inclusive.",
					qualifiedName(method)), method);
		}
	}

	private static void validateTimeout(ExecutableElement method, Reporter reporter) {
		Timeout timeout = method.getAnnotation(Timeout.class);
		if (timeout == null) {
			timeout = method.getEnclosingElement().getAnnotation(Timeout.class);
		}
		if (timeout == null) {
			return;
		}
		if (timeout.connectMillis() < -1) {
			reporter.error(String.format("The method %s is annotated with @Timeout but connectMillis must be -1 "
					+ "(unset) or a non-negative number of milliseconds.", qualifiedName(method)), method);
		}
		if (timeout.readMillis() < -1) {
			reporter.error(String.format("The method %s is annotated with @Timeout but readMillis must be -1 "
					+ "(unset) or a non-negative number of milliseconds.", qualifiedName(method)), method);
		}
	}

	private static void validateHeaders(ExecutableElement method, Reporter reporter) {
		Headers headers = method.getAnnotation(Headers.class);
		if (headers == null) {
			return;
		}
		for (String entry : headers.value()) {
			int colon = entry.indexOf(':');
			if (colon < 0) {
				reporter.error(String.format("The method %s has a @Headers entry '%s' with no ':' - expected "
						+ "'Name: Value'.", qualifiedName(method), entry), method);
				continue;
			}
			if (entry.substring(0, colon).trim().isEmpty()) {
				reporter.error(String.format("The method %s has a @Headers entry '%s' with an empty header name.",
						qualifiedName(method), entry), method);
			}
		}
	}

	private static void validateMapParam(ExecutableElement method, Class<? extends java.lang.annotation.Annotation> annotationType,
			String annotationName, ProcessingEnvironment env, Reporter reporter) {
		List<VariableElement> annotated = new ArrayList<>();
		for (VariableElement parameter : method.getParameters()) {
			if (parameter.getAnnotation(annotationType) != null) {
				annotated.add(parameter);
			}
		}
		if (annotated.size() > 1) {
			reporter.error(String.format("The method %s has more than one parameter annotated with %s.",
					qualifiedName(method), annotationName), method);
		}
		for (VariableElement parameter : annotated) {
			if (!isMapType(parameter.asType(), env)) {
				reporter.error(String.format("The method %s has a parameter annotated with %s that is not a Map.",
						qualifiedName(method), annotationName), parameter);
			}
		}
	}

	private static void validateMultipart(ExecutableElement method, HTTPMethod httpMethod, ProcessingEnvironment env,
			Reporter reporter) {
		boolean multipart = method.getAnnotation(Multipart.class) != null;
		List<VariableElement> parts = new ArrayList<>();
		List<VariableElement> partMaps = new ArrayList<>();
		VariableElement bodyParam = null;
		for (VariableElement parameter : method.getParameters()) {
			if (parameter.getAnnotation(Part.class) != null) {
				parts.add(parameter);
			}
			if (parameter.getAnnotation(PartMap.class) != null) {
				partMaps.add(parameter);
			}
			if (parameter.getAnnotation(Body.class) != null) {
				bodyParam = parameter;
			}
		}

		if (multipart && !BODY_SUPPORTED_METHODS.contains(httpMethod)) {
			reporter.error(String.format("The method %s is annotated with @Multipart but HTTP method %s does not "
					+ "support a request body.", qualifiedName(method), httpMethod), method);
		}
		if (multipart && parts.isEmpty() && partMaps.isEmpty()) {
			reporter.error(String.format("The method %s is annotated with @Multipart but has no @Part or @PartMap "
					+ "parameters.", qualifiedName(method)), method);
		}
		if (multipart && bodyParam != null) {
			reporter.error(String.format("The method %s is annotated with @Multipart and also has a @Body "
					+ "parameter - use one or the other.", qualifiedName(method)), method);
		}
		if (multipart && method.getAnnotation(FormUrlEncoded.class) != null) {
			reporter.error(String.format("The method %s is annotated with both @Multipart and @FormUrlEncoded - "
					+ "use one or the other.", qualifiedName(method)), method);
		}
		if (!multipart && !parts.isEmpty()) {
			reporter.error(String.format("The method %s has a @Part parameter but is not annotated with @Multipart.",
					qualifiedName(method)), parts.get(0));
		}
		if (!multipart && !partMaps.isEmpty()) {
			reporter.error(
					String.format("The method %s has a @PartMap parameter but is not annotated with @Multipart.",
							qualifiedName(method)), partMaps.get(0));
		}
		for (VariableElement part : parts) {
			if (!isSupportedPartType(part.asType())) {
				reporter.error(String.format("The method %s has a @Part parameter of type %s - only String, File, "
						+ "byte[], and InputStream are supported.", qualifiedName(method), part.asType()), part);
			}
		}
		validateMapParam(method, PartMap.class, "@PartMap", env, reporter);
	}

	private static boolean isSupportedPartType(TypeMirror type) {
		String name = type.toString();
		return "java.lang.String".equals(name) || "java.io.File".equals(name) || "byte[]".equals(name)
				|| "java.io.InputStream".equals(name);
	}

	private static void validateFormUrlEncoded(ExecutableElement method, HTTPMethod httpMethod,
			ProcessingEnvironment env, Reporter reporter) {
		boolean formUrlEncoded = method.getAnnotation(FormUrlEncoded.class) != null;
		List<VariableElement> fields = new ArrayList<>();
		List<VariableElement> fieldMaps = new ArrayList<>();
		VariableElement bodyParam = null;
		for (VariableElement parameter : method.getParameters()) {
			if (parameter.getAnnotation(Field.class) != null) {
				fields.add(parameter);
			}
			if (parameter.getAnnotation(FieldMap.class) != null) {
				fieldMaps.add(parameter);
			}
			if (parameter.getAnnotation(Body.class) != null) {
				bodyParam = parameter;
			}
		}

		if (formUrlEncoded && !BODY_SUPPORTED_METHODS.contains(httpMethod)) {
			reporter.error(String.format("The method %s is annotated with @FormUrlEncoded but HTTP method %s does "
					+ "not support a request body.", qualifiedName(method), httpMethod), method);
		}
		if (formUrlEncoded && fields.isEmpty() && fieldMaps.isEmpty()) {
			reporter.error(String.format("The method %s is annotated with @FormUrlEncoded but has no @Field or "
					+ "@FieldMap parameters.", qualifiedName(method)), method);
		}
		if (formUrlEncoded && bodyParam != null) {
			reporter.error(String.format("The method %s is annotated with @FormUrlEncoded and also has a @Body "
					+ "parameter - use one or the other.", qualifiedName(method)), method);
		}
		if (!formUrlEncoded && !fields.isEmpty()) {
			reporter.error(String.format("The method %s has a @Field parameter but is not annotated with "
					+ "@FormUrlEncoded.", qualifiedName(method)), fields.get(0));
		}
		if (!formUrlEncoded && !fieldMaps.isEmpty()) {
			reporter.error(String.format("The method %s has a @FieldMap parameter but is not annotated with "
					+ "@FormUrlEncoded.", qualifiedName(method)), fieldMaps.get(0));
		}
		validateMapParam(method, FieldMap.class, "@FieldMap", env, reporter);
	}

	private static void validateReturnType(ExecutableElement method, Types types, Reporter reporter) {
		TypeMirror returnType = method.getReturnType();
		if (returnType.getKind() != TypeKind.DECLARED) {
			return;
		}
		DeclaredType declaredType = (DeclaredType) returnType;
		String rawTypeName = types.erasure(declaredType).toString();
		if ("java.util.concurrent.CompletableFuture".equals(rawTypeName)) {
			validateParameterizedReturnType(method, declaredType, "CompletableFuture", true, types, reporter);
		} else if ("com.shri.restinpeace.RipResponse".equals(rawTypeName)) {
			validateParameterizedReturnType(method, declaredType, "RipResponse", false, types, reporter);
		}
	}

	private static void validateParameterizedReturnType(ExecutableElement method, DeclaredType declaredType,
			String typeName, boolean allowRipResponseInner, Types types, Reporter reporter) {
		if (declaredType.getTypeArguments().isEmpty()) {
			reporter.error(String.format("The method %s returns a raw %s with no type parameter.",
					qualifiedName(method), typeName), method);
			return;
		}
		TypeMirror innerType = declaredType.getTypeArguments().get(0);
		if (allowRipResponseInner && innerType.getKind() == TypeKind.DECLARED
				&& "com.shri.restinpeace.RipResponse".equals(types.erasure(innerType).toString())) {
			validateParameterizedReturnType(method, (DeclaredType) innerType, "RipResponse", false, types, reporter);
			return;
		}
		if (!isSupportedReturnTypeArgument(innerType)) {
			reporter.error(String.format("The method %s returns %s<%s>, which is not a supported type parameter.",
					qualifiedName(method), typeName, innerType), method);
			return;
		}
		if ("RipResponse".equals(typeName) && "java.io.File".equals(innerType.toString())) {
			reporter.error(String.format("The method %s returns RipResponse<File>, which is not supported - use a "
					+ "plain File return type with @Destination instead.", qualifiedName(method)), method);
		}
	}

	/**
	 * Whether {@code typeArgument} is a shape {@code CompletableFuture<T>}/
	 * {@code RipResponse<T>} can actually decode into at runtime -
	 * {@code byte[]}, or any class/interface, generic or not (e.g.
	 * {@code Void}, {@code User}, or {@code List<User>} - the latter two
	 * handled by {@code ResponseDecoder} via {@code RuntimeGenericType},
	 * see E12) - not a wildcard or type variable, which carry no runtime
	 * type to decode into at all. {@code TypeKind.VOID} is deliberately not
	 * checked here: it's the primitive {@code void} keyword's own kind, and
	 * a generic type argument can never legally be a primitive type - only
	 * boxed {@code java.lang.Void} (a plain {@code DECLARED} type, needing
	 * no special-casing) can ever appear in this position. Mirrors
	 * {@code ReflectiveRestClientValidator}'s own equivalent check: a
	 * parameterized inner type is a codegen-ineligible shape (that one
	 * method falls back to the reflective proxy - see
	 * {@code RestClientProcessor#toSupportedMethodModel}), not a validation
	 * error, exactly like a plain (non-generic) class/interface already
	 * wasn't.
	 */
	private static boolean isSupportedReturnTypeArgument(TypeMirror typeArgument) {
		if (typeArgument.getKind() == TypeKind.ARRAY) {
			return "byte[]".equals(typeArgument.toString());
		}
		return typeArgument.getKind() == TypeKind.DECLARED;
	}

	/**
	 * The compile-time counterpart of
	 * {@code ReflectiveRestClientValidator#validatePaginated} - see its own
	 * javadoc for the chunk-2/3/4/5/7/8-supported subset of
	 * {@code docs/design/pagination-helper.md} §7 this implements.
	 */
	private static void validatePaginated(ExecutableElement method, String url, ProcessingEnvironment env,
			Reporter reporter) {
		Types types = env.getTypeUtils();
		Paginated paginated = method.getAnnotation(Paginated.class);
		List<VariableElement> strategyParams = new ArrayList<>();
		for (VariableElement parameter : method.getParameters()) {
			if (parameter.asType().getKind() == TypeKind.DECLARED
					&& "com.shri.restinpeace.PaginationStrategy".equals(types.erasure(parameter.asType()).toString())) {
				strategyParams.add(parameter);
			}
		}
		TypeMirror returnType = method.getReturnType();
		boolean isDeclared = returnType.getKind() == TypeKind.DECLARED;
		String rawReturnTypeName = isDeclared ? types.erasure(returnType).toString() : "";
		boolean returnsPage = isDeclared && "com.shri.restinpeace.Page".equals(rawReturnTypeName);
		boolean returnsStream = isDeclared && "java.util.stream.Stream".equals(rawReturnTypeName);
		boolean returnsIterator = isDeclared && "java.util.Iterator".equals(rawReturnTypeName);
		boolean returnsSupportedType = returnsPage || returnsStream || returnsIterator;

		if (paginated != null && !strategyParams.isEmpty()) {
			reporter.error(String.format(
					"The method %s is annotated with @Paginated and also has a PaginationStrategy<T> parameter - "
							+ "use one or the other.",
					qualifiedName(method)), method);
			return;
		}
		if (returnsSupportedType && paginated == null && strategyParams.isEmpty()) {
			reporter.error(String.format(
					"The method %s returns %s<T> but is not annotated with @Paginated and has no "
							+ "PaginationStrategy<T> parameter.",
					qualifiedName(method), simpleTypeName(rawReturnTypeName)), method);
			return;
		}
		if (paginated == null && strategyParams.isEmpty()) {
			return;
		}
		// A raw RipResponse/CompletableFuture (no type parameter at all) is already
		// flagged by validateReturnType regardless of @Paginated/PaginationStrategy -
		// skip adding a second, overlapping message about the same underlying "raw
		// generic return type" mistake on the same method.
		if (isDeclared
				&& ("com.shri.restinpeace.RipResponse".equals(rawReturnTypeName)
						|| "java.util.concurrent.CompletableFuture".equals(rawReturnTypeName))
				&& ((DeclaredType) returnType).getTypeArguments().isEmpty()) {
			return;
		}
		if (isDeclared && "com.shri.restinpeace.RipResponse".equals(rawReturnTypeName)
				&& streamOrIteratorInnerName(returnType, types) != null) {
			reporter.error(String.format(
					"The method %s is annotated with @Paginated and returns RipResponse<%s<T>>, which is not "
							+ "supported - auto-flattening spans an unknown number of underlying calls, so there is "
							+ "no single response to wrap; use Page<T> and its rawResponse() instead.",
					qualifiedName(method), streamOrIteratorInnerName(returnType, types)), method);
			return;
		}
		if (!returnsSupportedType) {
			// A @Paginated method (never a PaginationStrategy<T>-parameter one -
			// PaginatedCallAdapterFactory is explicitly scoped to the declarative path
			// only, §7.2/§7.3) returning some other plain declared type is not
			// necessarily a mistake, unlike ReflectiveRestClientValidator's own
			// equivalent check: a registered PaginatedCallAdapterFactory (e.g.
			// rest-in-peace-reactor's Flux<T> pagination flavor) can legitimately claim
			// it - but only at runtime, since factory registration is a plain method
			// call (RIP.addPaginatedCallAdapterFactory) this processor has no way to
			// see. A @Paginated method is reflective-only regardless (never
			// compile-time-generated - see processPaginatedRequest's own javadoc), so
			// there's no codegen correctness risk in deferring to the reflective
			// validator's own, adapter-aware check at RIP.getClient(...) time instead.
			// void/RipResponse<T>/CompletableFuture<T> are excluded from this
			// deferral and still hard-error unconditionally - each is a single-value
			// wrapper/future concept fundamentally incompatible with "an unknown
			// number of underlying calls" (the exact reasoning the
			// RipResponse<Stream/Iterator<T>> check above already uses), so no future
			// pagination adapter could ever legitimately claim one either.
			//
			// Falls through rather than returning outright: deferring the *return
			// type* check to the reflective validator doesn't mean @Paginated's own
			// attribute/parameter checks below (URL conflict, advance/pointerSource,
			// pageSize, pointerKind/cursor params, hasMore/total signals) stop
			// applying - those are independent of what the return type actually is.
			if (!(paginated != null && isDeclared && !"com.shri.restinpeace.RipResponse".equals(rawReturnTypeName)
					&& !"java.util.concurrent.CompletableFuture".equals(rawReturnTypeName))) {
				reporter.error(String.format(
						"The method %s is annotated with @Paginated or has a PaginationStrategy<T> parameter but does "
								+ "not return Page<T>, Stream<T>, or Iterator<T>; void, RipResponse<T>, and "
								+ "CompletableFuture<T> are not supported for pagination.",
						qualifiedName(method)), method);
				return;
			}
		} else {
			String typeName = returnsPage ? "Page" : returnsStream ? "Stream" : "Iterator";
			validateParameterizedReturnType(method, (DeclaredType) returnType, typeName, false, types, reporter);
		}

		// Only reported when there's no static URL - validateUrlParam already reports
		// a more specific "has both a @Url parameter and a static URL" error for that
		// combination; stacking a second, less specific message about the same
		// underlying @Url misuse on the same method would be redundant noise.
		if (hasUrlParam(method) && com.shri.restinpeace.constant.RIPConstants.DEFAULT.equals(url)) {
			reporter.error(String.format("The method %s is annotated with both @Paginated and @Url - remove one "
					+ "or the other.", qualifiedName(method)), method);
		}

		if (paginated == null) {
			validatePaginationStrategyParams(method, returnType, strategyParams, types, reporter);
			return;
		}

		List<VariableElement> cursorParams = new ArrayList<>();
		for (VariableElement parameter : method.getParameters()) {
			if (parameter.getAnnotation(PaginationCursor.class) != null) {
				cursorParams.add(parameter);
			}
		}

		if (paginated.advance() != PaginationAdvance.NONE && paginated.pointerSource() != PaginationSignalSource.NONE) {
			reporter.error(String.format(
					"The method %s's @Paginated sets advance() but pointerSource is not NONE - advance() is only "
							+ "meaningful for client-driven pagination with no server-given pointer (pointerSource "
							+ "= NONE).",
					qualifiedName(method)), method);
		}

		if (paginated.advance() == PaginationAdvance.INCREMENT_BY_PAGE_SIZE && paginated.pageSize() <= 0) {
			reporter.error(String.format(
					"The method %s's @Paginated sets advance = INCREMENT_BY_PAGE_SIZE, which needs pageSize() to "
							+ "be a positive number.",
					qualifiedName(method)), method);
		}

		if (paginated.pointerKind() == PointerKind.FULL_URL) {
			if (!cursorParams.isEmpty()) {
				reporter.error(String.format(
						"The method %s's @Paginated has pointerKind = FULL_URL but also has a @PaginationCursor "
								+ "parameter - a full URL is followed as-is, with nothing to inject.",
						qualifiedName(method)), method);
			}
			validatePaginationFieldNonEmpty(method, paginated.pointerField(), "pointerField", reporter);
		} else {
			validatePaginatedValuePointer(method, paginated, cursorParams, reporter);
		}

		validatePaginationSignal(method, paginated.hasMoreSource(), paginated.hasMoreField(), "hasMoreSource",
				"hasMoreField", reporter);
		validatePaginationSignal(method, paginated.totalSource(), paginated.totalField(), "totalSource",
				"totalField", reporter);
		validatePaginationSignal(method, paginated.totalPagesSource(), paginated.totalPagesField(),
				"totalPagesSource", "totalPagesField", reporter);

		for (VariableElement cursorParam : cursorParams) {
			validatePaginationCursorParam(method, cursorParam, env, reporter);
		}
	}

	/**
	 * Validates the {@code PaginationStrategy<T>}-parameter path (§6.8, chunk 8): exactly one such parameter is
	 * allowed, and when both its {@code T} and the method's own {@code Page<T>}/{@code Stream<T>}/{@code Iterator<T>}
	 * return type argument are known (i.e. neither is a raw, unparameterized use), they must be the same type -
	 * otherwise the items {@code PaginationContext<T>.items()} hands back at runtime silently wouldn't be the type
	 * the consumer's strategy lambda declared.
	 */
	private static void validatePaginationStrategyParams(ExecutableElement method, TypeMirror returnType,
			List<VariableElement> strategyParams, Types types, Reporter reporter) {
		if (strategyParams.size() > 1) {
			reporter.error(String.format("The method %s has more than one PaginationStrategy<T> parameter.",
					qualifiedName(method)), method);
			return;
		}
		List<? extends TypeMirror> returnTypeArguments = ((DeclaredType) returnType).getTypeArguments();
		TypeMirror itemType = returnTypeArguments.isEmpty() ? null : returnTypeArguments.get(0);
		// Always a DeclaredType - strategyParams is already filtered to erasure-match
		// com.shri.restinpeace.PaginationStrategy - getTypeArguments() is empty for a
		// raw (unparameterized) PaginationStrategy parameter, same as the return type
		// case above.
		List<? extends TypeMirror> strategyTypeArguments = ((DeclaredType) strategyParams.get(0).asType())
				.getTypeArguments();
		TypeMirror strategyItemType = strategyTypeArguments.isEmpty() ? null : strategyTypeArguments.get(0);
		if (itemType != null && strategyItemType != null && !types.isSameType(itemType, strategyItemType)) {
			reporter.error(String.format(
					"The method %s's PaginationStrategy<%s> parameter doesn't match its %s<%s> return type - they "
							+ "must share the same item type.",
					qualifiedName(method), strategyItemType, simpleTypeName(types.erasure(returnType).toString()),
					itemType), method);
		}
	}

	/** Only ever called with one of the three fully-qualified pagination return type names, which always have a dot. */
	private static String simpleTypeName(String qualifiedName) {
		return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
	}

	/**
	 * "Stream"/"Iterator" if {@code ripResponseType}'s (a {@code RipResponse<T>}
	 * return type) {@code T} is itself {@code Stream<?>}/{@code Iterator<?>},
	 * else {@code null} - see §7's dedicated rejection for that shape. Callers only reach this once the
	 * raw-generic-return-type guard above has confirmed {@code ripResponseType} has a type argument, so an empty
	 * argument list isn't re-checked here.
	 */
	private static String streamOrIteratorInnerName(TypeMirror ripResponseType, Types types) {
		List<? extends TypeMirror> typeArguments = ((DeclaredType) ripResponseType).getTypeArguments();
		if (typeArguments.get(0).getKind() != TypeKind.DECLARED) {
			return null;
		}
		String innerRawTypeName = types.erasure(typeArguments.get(0)).toString();
		if ("java.util.stream.Stream".equals(innerRawTypeName)) {
			return "Stream";
		}
		if ("java.util.Iterator".equals(innerRawTypeName)) {
			return "Iterator";
		}
		return null;
	}

	private static void validatePaginatedValuePointer(ExecutableElement method, Paginated paginated,
			List<VariableElement> cursorParams, Reporter reporter) {
		PaginationSignalSource source = paginated.pointerSource();
		if (source == PaginationSignalSource.NONE) {
			if (paginated.advance() == PaginationAdvance.NONE) {
				reporter.error(String.format(
						"The method %s's @Paginated has pointerSource = NONE, which needs advance() to be set for "
								+ "client-driven pagination.",
						qualifiedName(method)), method);
				return;
			}
			validateAdvanceCursorParam(method, cursorParams, reporter);
			return;
		}
		validatePaginationFieldNonEmpty(method, paginated.pointerField(), "pointerField", reporter);
		if (paginated.pointerField().isEmpty()) {
			return;
		}
		int fieldCount = paginated.pointerField().split(",", -1).length;
		if (fieldCount > 1 && source != PaginationSignalSource.ITEM_FIELD) {
			reporter.error(String.format(
					"The method %s's @Paginated has a comma-separated pointerField - a composite pointer is only "
							+ "supported for pointerSource = ITEM_FIELD.",
					qualifiedName(method)), method);
			return;
		}
		validateCursorParamCount(method, cursorParams, fieldCount, reporter);
	}

	/**
	 * A {@code VALUE} pointer needs either exactly {@code fieldCount} non-{@code @Body} cursor parameters,
	 * positionally matched to {@code pointerField}'s comma-separated entries (§6.6 - N-way composite keyset via
	 * N separate carriers), or exactly one {@code @Body} cursor parameter whose comma-separated
	 * {@code bodyField} names the same {@code fieldCount} values (§6.7 - composite keyset into one JSON body).
	 */
	private static void validateCursorParamCount(ExecutableElement method, List<VariableElement> cursorParams,
			int fieldCount, Reporter reporter) {
		List<VariableElement> bodyParams = new ArrayList<>();
		for (VariableElement param : cursorParams) {
			if (param.getAnnotation(Body.class) != null) {
				bodyParams.add(param);
			}
		}
		if (!bodyParams.isEmpty()) {
			if (cursorParams.size() > 1) {
				reporter.error(String.format(
						"The method %s has a @PaginationCursor stacked on @Body alongside other @PaginationCursor "
								+ "parameters - a @Body carrier must be the method's only @PaginationCursor "
								+ "parameter.",
						qualifiedName(method)), method);
				return;
			}
			String bodyField = bodyParams.get(0).getAnnotation(PaginationCursor.class).bodyField();
			if (bodyField.isEmpty()) {
				return; // already flagged by validatePaginationCursorParam
			}
			int bodyFieldCount = bodyField.split(",", -1).length;
			if (bodyFieldCount != fieldCount) {
				reporter.error(String.format(
						"The method %s's @Paginated has a pointerField naming %d value(s) but its @Body "
								+ "@PaginationCursor's bodyField names %d - they must match.",
						qualifiedName(method), fieldCount, bodyFieldCount), method);
			}
			return;
		}
		if (cursorParams.size() != fieldCount) {
			reporter.error(String.format(
					"The method %s's @Paginated has pointerKind = VALUE with pointerSource != NONE, which needs "
							+ "%d @PaginationCursor parameter(s) (matching pointerField's %d comma-separated "
							+ "entries) - found %d.",
					qualifiedName(method), fieldCount, fieldCount, cursorParams.size()), method);
		}
	}

	/**
	 * Client-driven pagination (§6.5's advance-based fallback, chunk 7) has exactly one computed value - an offset
	 * or a page number - so it needs exactly one {@code @PaginationCursor} parameter, on any one of the four
	 * carriers; unlike {@link #validateCursorParamCount}, there's no {@code pointerField} entry count to match it
	 * against.
	 */
	private static void validateAdvanceCursorParam(ExecutableElement method, List<VariableElement> cursorParams,
			Reporter reporter) {
		if (cursorParams.size() != 1) {
			reporter.error(String.format(
					"The method %s's @Paginated has pointerSource = NONE with advance() set, which needs exactly "
							+ "one @PaginationCursor parameter to carry the client-computed offset/page value - "
							+ "found %d.",
					qualifiedName(method), cursorParams.size()), method);
		}
	}

	private static void validatePaginationSignal(ExecutableElement method, PaginationSignalSource source,
			String field, String sourceAttributeName, String fieldAttributeName, Reporter reporter) {
		if (source == PaginationSignalSource.NONE) {
			return;
		}
		if (source == PaginationSignalSource.ITEM_FIELD) {
			reporter.error(String.format(
					"The method %s's @Paginated sets %s = ITEM_FIELD, which is only meaningful for pointerSource.",
					qualifiedName(method), sourceAttributeName), method);
			return;
		}
		validatePaginationFieldNonEmpty(method, field, fieldAttributeName, reporter);
	}

	private static void validatePaginationFieldNonEmpty(ExecutableElement method, String field,
			String attributeName, Reporter reporter) {
		if (field.isEmpty()) {
			reporter.error(String.format("The method %s's @Paginated must set %s.", qualifiedName(method),
					attributeName), method);
		}
	}

	private static void validatePaginationCursorParam(ExecutableElement method, VariableElement cursorParam,
			ProcessingEnvironment env, Reporter reporter) {
		boolean onQuery = cursorParam.getAnnotation(QueryParam.class) != null;
		boolean onPath = cursorParam.getAnnotation(PathParam.class) != null;
		boolean onHeader = cursorParam.getAnnotation(HeaderParam.class) != null;
		boolean onBody = cursorParam.getAnnotation(Body.class) != null;
		int carrierCount = (onQuery ? 1 : 0) + (onPath ? 1 : 0) + (onHeader ? 1 : 0) + (onBody ? 1 : 0);
		if (carrierCount != 1) {
			reporter.error(String.format(
					"The method %s has a @PaginationCursor parameter that must be stacked on exactly one of "
							+ "@QueryParam/@PathParam/@HeaderParam/@Body.",
					qualifiedName(method)), cursorParam);
			return;
		}
		String bodyField = cursorParam.getAnnotation(PaginationCursor.class).bodyField();
		if (onBody) {
			if (bodyField.isEmpty()) {
				reporter.error(String.format(
						"The method %s has a @PaginationCursor stacked on @Body but bodyField is empty - set it to "
								+ "the JSON path inside the body to write the next-page value to.",
						qualifiedName(method)), cursorParam);
			}
			if (!isMapOfStringToObject(cursorParam.asType(), env)) {
				reporter.error(String.format(
						"The method %s has a @PaginationCursor stacked on @Body but the parameter's declared type "
								+ "is not Map<String,Object>.",
						qualifiedName(method)), cursorParam);
			}
			return;
		}
		if (!bodyField.isEmpty()) {
			reporter.error(String.format(
					"The method %s has a @PaginationCursor with a non-empty bodyField but is not stacked on @Body "
							+ "- bodyField is only meaningful there.",
					qualifiedName(method)), cursorParam);
		}
		String typeName = cursorParam.asType().toString();
		if (!"java.lang.String".equals(typeName) && !"int".equals(typeName) && !"long".equals(typeName)) {
			reporter.error(String.format(
					"The method %s has a @PaginationCursor parameter of type %s - only String, int, and long are "
							+ "supported.",
					qualifiedName(method), typeName), cursorParam);
		}
	}

	private static boolean returnsFile(ExecutableElement method, Types types) {
		TypeMirror returnType = method.getReturnType();
		if (returnType.getKind() == TypeKind.DECLARED && "java.io.File".equals(types.erasure(returnType).toString())) {
			return true;
		}
		if (returnType.getKind() == TypeKind.DECLARED
				&& "java.util.concurrent.CompletableFuture".equals(types.erasure(returnType).toString())) {
			List<? extends TypeMirror> args = ((DeclaredType) returnType).getTypeArguments();
			return args.size() == 1 && "java.io.File".equals(args.get(0).toString());
		}
		return false;
	}

	private static boolean returnsDownloadableBody(ExecutableElement method, Types types) {
		TypeMirror returnType = method.getReturnType();
		if (returnType.getKind() == TypeKind.ARRAY && "byte[]".equals(returnType.toString())) {
			return true;
		}
		if (returnsFile(method, types)) {
			return true;
		}
		if (returnType.getKind() == TypeKind.DECLARED) {
			String rawTypeName = types.erasure(returnType).toString();
			if ("java.util.concurrent.CompletableFuture".equals(rawTypeName)
					|| "com.shri.restinpeace.RipResponse".equals(rawTypeName)) {
				List<? extends TypeMirror> args = ((DeclaredType) returnType).getTypeArguments();
				return args.size() == 1 && "byte[]".equals(args.get(0).toString());
			}
		}
		return false;
	}

	private static boolean hasUrlParam(ExecutableElement method) {
		return method.getParameters().stream().anyMatch(p -> p.getAnnotation(Url.class) != null);
	}

	private static void validateUrlParam(ExecutableElement method, String url, Reporter reporter) {
		List<VariableElement> urlParams = new ArrayList<>();
		for (VariableElement parameter : method.getParameters()) {
			if (parameter.getAnnotation(Url.class) != null) {
				urlParams.add(parameter);
			}
		}
		if (urlParams.size() > 1) {
			reporter.error(String.format("The method %s has more than one parameter annotated with @Url.",
					qualifiedName(method)), method);
		}
		for (VariableElement parameter : urlParams) {
			if (!"java.lang.String".equals(parameter.asType().toString())) {
				reporter.error(String.format("The method %s has a @Url parameter of type %s - only String is "
						+ "supported.", qualifiedName(method), parameter.asType()), parameter);
			}
		}
		if (!urlParams.isEmpty() && !com.shri.restinpeace.constant.RIPConstants.DEFAULT.equals(url)) {
			reporter.error(String.format("The method %s has both a @Url parameter and a static URL '%s' - remove "
					+ "one or the other.", qualifiedName(method), url), method);
		}
		boolean hasPathParam = method.getParameters().stream().anyMatch(p -> p.getAnnotation(PathParam.class) != null);
		if (!urlParams.isEmpty() && hasPathParam) {
			reporter.error(String.format("The method %s has both a @Url parameter and a @PathParam parameter - "
					+ "@PathParam has no effect when @Url is used.", qualifiedName(method)), method);
		}
	}

	private static void validateUrl(ExecutableElement method, String url, Reporter reporter) {
		if (!isURLValid(url)) {
			reporter.error(String.format("The method %s has an invalid URL '%s'.", qualifiedName(method), url),
					method);
			return;
		}
		Set<String> urlPathParams = extractPathParams(url);
		Set<String> methodPathParams = new HashSet<>();
		for (VariableElement parameter : method.getParameters()) {
			PathParam pathParam = parameter.getAnnotation(PathParam.class);
			if (pathParam != null) {
				methodPathParams.add(pathParam.value());
			}
		}
		for (String urlPathParam : urlPathParams) {
			if (!methodPathParams.contains(urlPathParam)) {
				reporter.error(String.format("The method %s has path param '%s' in its URL that is not annotated "
						+ "on any parameter with @PathParam.", qualifiedName(method), urlPathParam), method);
			}
		}
		for (String methodPathParam : methodPathParams) {
			if (!urlPathParams.contains(methodPathParam)) {
				reporter.error(String.format("The method %s has a @PathParam('%s') that does not appear as '{%s}' "
						+ "in its URL.", qualifiedName(method), methodPathParam, methodPathParam), method);
			}
		}
	}

	private static void validateDestination(ExecutableElement method, Types types, Reporter reporter) {
		List<VariableElement> destinations = new ArrayList<>();
		for (VariableElement parameter : method.getParameters()) {
			if (parameter.getAnnotation(Destination.class) != null) {
				destinations.add(parameter);
			}
		}
		boolean returnsFile = returnsFile(method, types);

		if (destinations.size() > 1) {
			reporter.error(String.format("The method %s has more than one parameter annotated with @Destination.",
					qualifiedName(method)), method);
		}
		for (VariableElement parameter : destinations) {
			if (!"java.io.File".equals(parameter.asType().toString())) {
				reporter.error(String.format("The method %s has a @Destination parameter of type %s - only File "
						+ "is supported.", qualifiedName(method), parameter.asType()), parameter);
			}
		}
		if (!returnsFile && !destinations.isEmpty()) {
			reporter.error(String.format("The method %s has a @Destination parameter but does not return File.",
					qualifiedName(method)), method);
		}
		if (returnsFile && destinations.isEmpty()) {
			reporter.error(String.format(
					"The method %s returns File but has no @Destination parameter to write the response to.",
					qualifiedName(method)), method);
		}
	}

	private static void validateDownloadProgressListener(ExecutableElement method, Types types, Reporter reporter) {
		List<VariableElement> listeners = new ArrayList<>();
		for (VariableElement parameter : method.getParameters()) {
			if ("com.shri.restinpeace.download.DownloadProgressListener".equals(parameter.asType().toString())) {
				listeners.add(parameter);
			}
		}
		if (listeners.size() > 1) {
			reporter.error(String.format("The method %s has more than one DownloadProgressListener parameter.",
					qualifiedName(method)), method);
		}
		if (!listeners.isEmpty() && !returnsDownloadableBody(method, types)) {
			reporter.error(String.format(
					"The method %s has a DownloadProgressListener parameter but does not return byte[] or File.",
					qualifiedName(method)), method);
		}
	}

	private static void validateUploadProgressListener(ExecutableElement method, Reporter reporter) {
		List<VariableElement> listeners = new ArrayList<>();
		for (VariableElement parameter : method.getParameters()) {
			if ("com.shri.restinpeace.upload.UploadProgressListener".equals(parameter.asType().toString())) {
				listeners.add(parameter);
			}
		}
		if (listeners.size() > 1) {
			reporter.error(String.format("The method %s has more than one UploadProgressListener parameter.",
					qualifiedName(method)), method);
		}
		if (!listeners.isEmpty() && method.getAnnotation(Multipart.class) == null) {
			reporter.error(String.format(
					"The method %s has an UploadProgressListener parameter but is not annotated with @Multipart.",
					qualifiedName(method)), method);
		}
	}

	private static boolean isMapType(TypeMirror type, ProcessingEnvironment env) {
		if (type.getKind() != TypeKind.DECLARED) {
			return false;
		}
		Types types = env.getTypeUtils();
		TypeMirror mapErasure = types.erasure(env.getElementUtils().getTypeElement("java.util.Map").asType());
		TypeMirror paramErasure = types.erasure(type);
		return types.isSubtype(paramErasure, mapErasure);
	}

	/** Whether {@code type} is exactly {@code Map<String,Object>} - the required shape for a {@code @Body @PaginationCursor} carrier (§6.7). */
	private static boolean isMapOfStringToObject(TypeMirror type, ProcessingEnvironment env) {
		if (!isMapType(type, env)) {
			return false;
		}
		List<? extends TypeMirror> typeArguments = ((DeclaredType) type).getTypeArguments();
		if (typeArguments.size() != 2) {
			return false;
		}
		Types types = env.getTypeUtils();
		TypeMirror stringType = env.getElementUtils().getTypeElement("java.lang.String").asType();
		TypeMirror objectType = env.getElementUtils().getTypeElement("java.lang.Object").asType();
		return types.isSameType(typeArguments.get(0), stringType) && types.isSameType(typeArguments.get(1), objectType);
	}

	private static boolean isURLValid(String url) {
		try {
			new URI(PATH_PARAM_PATTERN.matcher(url).replaceAll("x"));
			return true;
		} catch (URISyntaxException e) {
			return false;
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

	private static String qualifiedName(ExecutableElement method) {
		TypeElement enclosing = (TypeElement) method.getEnclosingElement();
		return enclosing.getQualifiedName() + "." + method.getSimpleName();
	}

	private static final class HttpMethodAndUrl {
		final HTTPMethod httpMethod;
		final String urlTemplate;

		HttpMethodAndUrl(HTTPMethod httpMethod, String urlTemplate) {
			this.httpMethod = httpMethod;
			this.urlTemplate = urlTemplate;
		}
	}

	private static final class Reporter {
		private final Messager messager;
		private boolean hasError;

		Reporter(Messager messager) {
			this.messager = messager;
		}

		void error(String message, Element element) {
			messager.printMessage(Diagnostic.Kind.ERROR, message, element);
			hasError = true;
		}
	}

}
