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
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.Destination;
import com.shri.restinpeace.annotation.request.Field;
import com.shri.restinpeace.annotation.request.FieldMap;
import com.shri.restinpeace.annotation.request.FormUrlEncoded;
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.Headers;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PartMap;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.Url;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.constant.HTTPMethod;

/**
 * The compile-time counterpart of
 * {@link com.shri.restinpeace.validator.RestClientValidator} - the same
 * semantic rules (an invalid {@code @Retry}, a malformed {@code @Headers}
 * entry, an unmatched path param, ...), reimplemented against
 * {@code javax.lang.model}'s {@code ExecutableElement}/{@code VariableElement}
 * instead of {@code java.lang.reflect}'s {@code Method}/{@code Parameter},
 * so a {@code @RestClient} interface that would fail
 * {@code RestClientValidator.validate(...)} at the first
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
 * One deliberate gap: {@code RestClientValidator} requires either
 * {@code @BaseUrl} on the interface or a runtime base URL
 * ({@code RIP.getClient(Class, String)}/{@code RipClientConfig}) for a
 * relative method URL - which call overload ends up used is inherently a
 * runtime fact, unknowable here. So a relative URL with no {@code @BaseUrl}
 * is not flagged as an error at compile time; every other URL check (syntax,
 * unmatched path params) still runs directly against the method's own URL
 * template, since those never depend on the base at all.
 */
final class CompileTimeValidator {

	private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

	private static final Set<HTTPMethod> BODY_SUPPORTED_METHODS = new HashSet<>(
			java.util.Arrays.asList(HTTPMethod.POST, HTTPMethod.PUT, HTTPMethod.PATCH, HTTPMethod.DELETE));

	private CompileTimeValidator() {
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
			HttpMethodAndUrl httpMethodAndUrl = validateHttpMethodAnnotation(method, reporter);
			if (httpMethodAndUrl != null) {
				validateUrlParam(method, httpMethodAndUrl.urlTemplate, reporter);
				if (!hasUrlParam(method)) {
					validateUrl(method, httpMethodAndUrl.urlTemplate, reporter);
				}
				validateBody(method, httpMethodAndUrl.httpMethod, reporter);
				validateReturnType(method, env.getTypeUtils(), reporter);
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
		if (retry != null && retry.times() < 1) {
			reporter.error(String.format("The method %s is annotated with @Retry but times must be at least 1.",
					qualifiedName(method)), method);
		}
	}

	private static void validateTimeout(ExecutableElement method, Reporter reporter) {
		Timeout timeout = method.getAnnotation(Timeout.class);
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
	 * {@code RipResponse<T>} can actually decode into - {@code void}/
	 * {@code Void}, {@code byte[]}, or a non-generic class/interface - not a
	 * further-parameterized type, wildcard, or type variable.
	 */
	private static boolean isSupportedReturnTypeArgument(TypeMirror typeArgument) {
		if (typeArgument.getKind() == TypeKind.VOID) {
			return true;
		}
		if (typeArgument.getKind() == TypeKind.ARRAY) {
			return "byte[]".equals(typeArgument.toString());
		}
		if (typeArgument.getKind() != TypeKind.DECLARED) {
			return false;
		}
		if ("java.lang.Void".equals(typeArgument.toString())) {
			return true;
		}
		return ((DeclaredType) typeArgument).getTypeArguments().isEmpty();
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
		if (!urlParams.isEmpty() && !com.shri.restinpeace.constant.RIPConstant.DEFAULT.equals(url)) {
			reporter.error(String.format("The method %s has both a @Url parameter and a static URL '%s' - remove "
					+ "one or the other.", qualifiedName(method), url), method);
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
			if ("com.shri.restinpeace.multipart.UploadProgressListener".equals(parameter.asType().toString())) {
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
