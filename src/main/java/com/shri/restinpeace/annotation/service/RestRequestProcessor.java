package com.shri.restinpeace.annotation.service;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.error.ErrorType;
import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.constant.RIPConstant;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;

import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import kong.unirest.MultipartBody;
import kong.unirest.Unirest;

/**
 * Builds and executes the actual HTTP request for a {@code @RestClient}
 * method call, applying path/query/header/body parameters and registered
 * {@link RequestInterceptor}s. Used internally by
 * {@link com.shri.restinpeace.proxy.RestClientInvocationHandler}; not part
 * of the library's public API - use {@link com.shri.restinpeace.RIP}
 * instead.
 */
public class RestRequestProcessor {

	private static final List<RequestInterceptor> INTERCEPTORS = new CopyOnWriteArrayList<>();

	private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "rip-retry-scheduler");
		thread.setDaemon(true);
		return thread;
	});

	private final String baseUrlOverride;

	/** Creates a processor with no runtime base URL override. Cheap and stateless beyond the shared interceptor registry. */
	public RestRequestProcessor() {
		this(null);
	}

	/**
	 * Creates a processor that resolves every relative method URL against
	 * {@code baseUrlOverride} instead of the interface's {@code @BaseUrl},
	 * for a base URL that's only known at runtime (e.g. per deployment
	 * environment). An absolute method URL still ignores this, same as it
	 * ignores {@code @BaseUrl}.
	 *
	 * @param baseUrlOverride the runtime base URL, or {@code null} to fall
	 *                        back to the interface's {@code @BaseUrl}
	 */
	public RestRequestProcessor(String baseUrlOverride) {
		this.baseUrlOverride = baseUrlOverride;
	}

	/**
	 * Registers a global interceptor applied to every request/response made
	 * through RIP. See
	 * {@link com.shri.restinpeace.RIP#addInterceptor(RequestInterceptor)}.
	 *
	 * @param interceptor the interceptor to register
	 */
	public static void addInterceptor(RequestInterceptor interceptor) {
		INTERCEPTORS.add(interceptor);
	}

	/** Removes all registered interceptors. */
	public static void clearInterceptors() {
		INTERCEPTORS.clear();
	}

	/**
	 * Executes the given {@code @RestClient} method call and returns its result.
	 *
	 * @param method     the interface method that was called
	 * @param httpMethod the HTTP method it maps to
	 * @param args       the call's argument values, in declaration order
	 * @return the call's result: the raw body, a deserialized object, a
	 *         {@code CompletableFuture} of either, or {@code null} for
	 *         {@code void} methods
	 */
	public Object processRestRequest(Method method, HTTPMethod httpMethod, Object[] args) {
		String url = resolvePathParams(applyBaseUrl(method, getUrlTemplate(method, httpMethod)), method, args);
		RequestContext context = new RequestContext(httpMethod, url);

		HttpRequest<?> request = createRequest(httpMethod, url);
		request = applyParams(request, method, args);
		request = applyInterceptors(request, context);

		Class<?> returnType = method.getReturnType();
		if (returnType == CompletableFuture.class) {
			return processAsync(request, method, context);
		}
		HttpResponse<String> response = executeSyncWithRetry(method, returnType, context, request::asString);
		return decodeOrThrow(response, method, returnType);
	}

	private HttpRequest<?> applyInterceptors(HttpRequest<?> request, RequestContext context) {
		if (INTERCEPTORS.isEmpty()) {
			return request;
		}
		INTERCEPTORS.forEach(interceptor -> interceptor.beforeRequest(context));
		context.getHeaders().forEach(request::header);
		return request;
	}

	private void notifyAfterResponse(RequestContext context, HttpResponse<String> response, Method method,
			Class<?> returnType) {
		if (INTERCEPTORS.isEmpty()) {
			return;
		}
		Object body = decodeBody(response, method, returnType);
		// LIFO, mirroring beforeRequest: the first interceptor registered wraps every
		// other one and is notified last, symmetric with it running beforeRequest first.
		List<RequestInterceptor> reversed = new ArrayList<>(INTERCEPTORS);
		Collections.reverse(reversed);
		reversed.forEach(interceptor -> interceptor.afterResponse(context, response.getStatus(), body));
	}

	private CompletableFuture<?> processAsync(HttpRequest<?> request, Method method, RequestContext context) {
		Class<?> innerType = resolveFutureInnerType(method);
		return executeAsyncWithRetry(method, innerType, context, request::asStringAsync)
				.thenApply(response -> decodeOrThrow(response, method, innerType));
	}

	/**
	 * Decodes a settled response, throwing {@link RestInPeaceHttpException}
	 * for a non-2xx status instead of returning a value.
	 */
	private Object decodeOrThrow(HttpResponse<String> response, Method method, Class<?> returnType) {
		if (!isSuccessStatus(response.getStatus())) {
			throw new RestInPeaceHttpException(response.getStatus(), response.getBody(),
					decodeBody(response, method, returnType));
		}
		return decodeBody(response, method, returnType);
	}

	/**
	 * Decodes a response's body for a success status (into {@code returnType},
	 * the same as {@link #decodeOrThrow}'s success case) or a non-2xx one
	 * (into the method's {@code @ErrorType}, or left as the raw body if it
	 * has none) - without throwing either way, for reporting to
	 * interceptors mid-retry as well as for the final settled response.
	 */
	private Object decodeBody(HttpResponse<String> response, Method method, Class<?> returnType) {
		String rawBody = response.getBody();
		if (!isSuccessStatus(response.getStatus())) {
			ErrorType errorType = method.getAnnotation(ErrorType.class);
			if (errorType != null && rawBody != null && !rawBody.isEmpty()) {
				return Unirest.config().getObjectMapper().readValue(rawBody, errorType.value());
			}
			return rawBody;
		}
		if (returnType == String.class) {
			return rawBody;
		}
		if (returnType == void.class || returnType == Void.class) {
			return null;
		}
		return Unirest.config().getObjectMapper().readValue(rawBody, returnType);
	}

	private static boolean isSuccessStatus(int status) {
		return status >= 200 && status < 300;
	}

	private HttpResponse<String> executeSyncWithRetry(Method method, Class<?> returnType, RequestContext context,
			Supplier<HttpResponse<String>> call) {
		Retry retry = method.getAnnotation(Retry.class);
		if (retry == null) {
			HttpResponse<String> response = call.get();
			notifyAfterResponse(context, response, method, returnType);
			return response;
		}
		long delay = retry.delayMillis();
		for (int attempt = 1;; attempt++) {
			HttpResponse<String> response = null;
			RuntimeException failure = null;
			try {
				response = call.get();
				notifyAfterResponse(context, response, method, returnType);
			} catch (RuntimeException e) {
				failure = e;
			}
			boolean retryable = failure != null || isRetryableStatus(response.getStatus(), retry.retryOnStatus());
			if (!retryable || attempt >= retry.times()) {
				if (failure != null) {
					throw failure;
				}
				return response;
			}
			sleep(delay);
			delay = nextDelay(delay, retry);
		}
	}

	private CompletableFuture<HttpResponse<String>> executeAsyncWithRetry(Method method, Class<?> returnType,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<String>>> call) {
		Retry retry = method.getAnnotation(Retry.class);
		if (retry == null) {
			return call.get().thenApply(response -> {
				notifyAfterResponse(context, response, method, returnType);
				return response;
			});
		}
		return attemptAsync(call, method, returnType, context, retry, 1, retry.delayMillis());
	}

	private CompletableFuture<HttpResponse<String>> attemptAsync(Supplier<CompletableFuture<HttpResponse<String>>> call,
			Method method, Class<?> returnType, RequestContext context, Retry retry, int attempt, long delay) {
		CompletableFuture<HttpResponse<String>> result = new CompletableFuture<>();
		call.get().whenComplete((response, failure) -> {
			if (response != null) {
				notifyAfterResponse(context, response, method, returnType);
			}
			boolean retryable = failure != null || isRetryableStatus(response.getStatus(), retry.retryOnStatus());
			if (!retryable || attempt >= retry.times()) {
				if (failure != null) {
					result.completeExceptionally(failure);
				} else {
					result.complete(response);
				}
				return;
			}
			RETRY_SCHEDULER.schedule(
					() -> attemptAsync(call, method, returnType, context, retry, attempt + 1, nextDelay(delay, retry))
							.whenComplete((r, t) -> {
								if (t != null) {
									result.completeExceptionally(t);
								} else {
									result.complete(r);
								}
							}),
					delay, TimeUnit.MILLISECONDS);
		});
		return result;
	}

	private static boolean isRetryableStatus(int status, int[] retryOnStatus) {
		return IntStream.of(retryOnStatus).anyMatch(code -> code == status);
	}

	private static long nextDelay(long delay, Retry retry) {
		return (long) (delay * retry.backoffMultiplier());
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestInPeaceException("Interrupted while waiting to retry.", e);
		}
	}

	private Class<?> resolveFutureInnerType(Method method) {
		Type genericReturnType = method.getGenericReturnType();
		if (!(genericReturnType instanceof ParameterizedType)) {
			throw new RestInPeaceException(
					String.format("The method %s returns a raw CompletableFuture with no type parameter.", method));
		}
		Type innerType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
		if (!(innerType instanceof Class)) {
			throw new RestInPeaceException(String.format(
					"The method %s returns CompletableFuture<%s>, which is not a supported type parameter.", method,
					innerType));
		}
		return (Class<?>) innerType;
	}

	private String getUrlTemplate(Method method, HTTPMethod httpMethod) {
		switch (httpMethod) {
		case GET:
			return method.getAnnotation(GET.class).value();
		case POST:
			return method.getAnnotation(POST.class).value();
		case PUT:
			return method.getAnnotation(PUT.class).value();
		case DELETE:
			return method.getAnnotation(DELETE.class).value();
		case PATCH:
			return method.getAnnotation(PATCH.class).value();
		case HEAD:
			return method.getAnnotation(HEAD.class).value();
		case OPTIONS:
			return method.getAnnotation(OPTIONS.class).value();
		default:
			throw new RestInPeaceException(String.format("Unknown HTTP method %s.", httpMethod));
		}
	}

	private String applyBaseUrl(Method method, String url) {
		if (isAbsoluteUrl(url)) {
			return url;
		}
		String base;
		if (baseUrlOverride != null) {
			base = baseUrlOverride;
		} else {
			base = method.getDeclaringClass().getAnnotation(BaseUrl.class).value();
		}
		if (base.endsWith("/") && url.startsWith("/")) {
			return base + url.substring(1);
		}
		if (!base.endsWith("/") && !url.startsWith("/")) {
			return base + "/" + url;
		}
		return base + url;
	}

	private static boolean isAbsoluteUrl(String url) {
		return url.startsWith("http://") || url.startsWith("https://");
	}

	private HttpRequest<?> createRequest(HTTPMethod httpMethod, String url) {
		switch (httpMethod) {
		case GET:
			return Unirest.get(url);
		case HEAD:
			return Unirest.head(url);
		case OPTIONS:
			return Unirest.options(url);
		case POST:
			return Unirest.post(url);
		case PUT:
			return Unirest.put(url);
		case PATCH:
			return Unirest.patch(url);
		case DELETE:
			return Unirest.delete(url);
		default:
			throw new RestInPeaceException(String.format("Unknown HTTP method %s.", httpMethod));
		}
	}

	private HttpRequest<?> applyParams(HttpRequest<?> request, Method method, Object[] args) {
		Parameter[] parameters = method.getParameters();

		MultipartBody multipartBody = null;
		if (method.getAnnotation(Multipart.class) != null) {
			multipartBody = ((HttpRequestWithBody) request).multiPartContent();
			request = multipartBody;
		}

		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			Object argValue = args == null ? null : args[i];

			QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
			if (queryParam != null) {
				Object value = resolveValue(argValue, queryParam.required(), queryParam.defaultValue(),
						queryParam.value());
				if (value != null) {
					request.queryString(queryParam.value(), value);
				}
			}

			HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
			if (headerParam != null) {
				Object value = resolveValue(argValue, headerParam.required(), headerParam.defaultValue(),
						headerParam.value());
				if (value != null) {
					request.header(headerParam.value(), String.valueOf(value));
				}
			}

			if (parameter.getAnnotation(QueryMap.class) != null && argValue != null) {
				applyQueryMap(request, (Map<?, ?>) argValue);
			}

			if (parameter.getAnnotation(HeaderMap.class) != null && argValue != null) {
				applyHeaderMap(request, (Map<?, ?>) argValue);
			}

			Part part = parameter.getAnnotation(Part.class);
			if (part != null) {
				Object value = resolveValue(argValue, part.required(), RIPConstant.DEFAULT, part.value());
				if (value != null) {
					applyPart(multipartBody, part.value(), value);
				}
			}

			Body body = parameter.getAnnotation(Body.class);
			if (body != null && argValue != null) {
				request = applyBody(request, method, argValue);
			}
		}
		return request;
	}

	private void applyPart(MultipartBody multipartBody, String name, Object value) {
		if (value instanceof String) {
			multipartBody.field(name, (String) value);
		} else {
			multipartBody.field(name, (File) value);
		}
	}

	private void applyQueryMap(HttpRequest<?> request, Map<?, ?> queryMap) {
		queryMap.forEach((name, value) -> {
			if (value != null) {
				request.queryString(String.valueOf(name), value);
			}
		});
	}

	private void applyHeaderMap(HttpRequest<?> request, Map<?, ?> headerMap) {
		headerMap.forEach((name, value) -> {
			if (value != null) {
				request.header(String.valueOf(name), String.valueOf(value));
			}
		});
	}

	private HttpRequest<?> applyBody(HttpRequest<?> request, Method method, Object value) {
		if (!(request instanceof HttpRequestWithBody)) {
			throw new RestInPeaceException(String.format(
					"The method %s is annotated with @Body but its HTTP method does not support a request body.",
					method));
		}
		HttpRequestWithBody bodyRequest = (HttpRequestWithBody) request;
		if (value instanceof String) {
			return bodyRequest.body((String) value);
		}
		return bodyRequest.body(value).contentType("application/json");
	}

	private String resolvePathParams(String urlTemplate, Method method, Object[] args) {
		String url = urlTemplate;
		Parameter[] parameters = method.getParameters();

		for (int i = 0; i < parameters.length; i++) {
			PathParam pathParam = parameters[i].getAnnotation(PathParam.class);
			if (pathParam != null) {
				Object value = args == null ? null : args[i];
				if (value == null) {
					throw new RestInPeaceException(
							String.format("Missing value for path param '%s' in method %s.", pathParam.value(), method));
				}
				url = url.replace("{" + pathParam.value() + "}", String.valueOf(value));
			}
		}
		return url;
	}

	private Object resolveValue(Object argValue, boolean required, String defaultValue, String paramName) {
		if (argValue != null) {
			return argValue;
		}
		if (!RIPConstant.DEFAULT.equals(defaultValue)) {
			return defaultValue;
		}
		if (required) {
			throw new RestInPeaceException(String.format("Missing required value for param '%s'.", paramName));
		}
		return null;
	}

}
