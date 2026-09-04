package com.shri.restinpeace.annotation.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import com.shri.restinpeace.annotation.request.Destination;
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
import com.shri.restinpeace.constant.RIPConstant;
import com.shri.restinpeace.download.DownloadProgressListener;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;
import com.shri.restinpeace.multipart.PartValue;
import com.shri.restinpeace.multipart.UploadProgressListener;
import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.RipResponse;

import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import kong.unirest.MultipartBody;
import kong.unirest.ObjectMapper;
import kong.unirest.Unirest;
import kong.unirest.UnirestConfigException;
import kong.unirest.UnirestInstance;

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
	private static final int[] EMPTY_STATUS_CODES = new int[0];

	private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "rip-retry-scheduler");
		thread.setDaemon(true);
		return thread;
	});

	private final String baseUrlOverride;
	private final UnirestInstance unirestInstance;

	/** Creates a processor with no runtime base URL override. Cheap and stateless beyond the shared interceptor registry. */
	public RestRequestProcessor() {
		this((String) null);
	}

	/**
	 * Creates a processor that resolves every relative method URL against
	 * {@code baseUrlOverride} instead of the interface's {@code @BaseUrl},
	 * for a base URL that's only known at runtime (e.g. per deployment
	 * environment). An absolute method URL still ignores this, same as it
	 * ignores {@code @BaseUrl}. Requests still go through the shared static
	 * {@code Unirest} client.
	 *
	 * @param baseUrlOverride the runtime base URL, or {@code null} to fall
	 *                        back to the interface's {@code @BaseUrl}
	 */
	public RestRequestProcessor(String baseUrlOverride) {
		this.baseUrlOverride = baseUrlOverride;
		this.unirestInstance = null;
	}

	/**
	 * Creates a processor from a {@link RipClientConfig}. Requests go through
	 * a dedicated {@code UnirestInstance} - instead of the shared static
	 * {@code Unirest} client - whenever {@code config} sets a connect/read
	 * timeout, a proxy, or an {@code objectMapper}, since those settings live
	 * on a client instance, not per request.
	 *
	 * @param config the per-client settings
	 */
	public RestRequestProcessor(RipClientConfig config) {
		this.baseUrlOverride = config.getBaseUrl();
		boolean needsOwnInstance = config.getConnectTimeoutMillis() != null || config.getReadTimeoutMillis() != null
				|| config.getProxyHost() != null || config.getObjectMapper() != null;
		this.unirestInstance = needsOwnInstance ? buildInstance(config) : null;
	}

	private static UnirestInstance buildInstance(RipClientConfig config) {
		UnirestInstance instance = Unirest.spawnInstance();
		if (config.getConnectTimeoutMillis() != null) {
			instance.config().connectTimeout(config.getConnectTimeoutMillis());
		}
		if (config.getReadTimeoutMillis() != null) {
			instance.config().socketTimeout(config.getReadTimeoutMillis());
		}
		if (config.getProxyHost() != null) {
			if (config.getProxyUsername() != null) {
				instance.config().proxy(config.getProxyHost(), config.getProxyPort(), config.getProxyUsername(),
						config.getProxyPassword());
			} else {
				instance.config().proxy(config.getProxyHost(), config.getProxyPort());
			}
		}
		if (config.getObjectMapper() != null) {
			instance.config().setObjectMapper(config.getObjectMapper());
		}
		return instance;
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
	 * @return the call's result: the raw body, a deserialized object, either
	 *         wrapped in a {@link RipResponse} for its status/headers, a
	 *         {@code CompletableFuture} of any of those, or {@code null} for
	 *         {@code void} methods
	 */
	public Object processRestRequest(Method method, HTTPMethod httpMethod, Object[] args) {
		String url = resolveUrl(method, httpMethod, args);
		RequestContext context = new RequestContext(httpMethod, url);

		HttpRequest<?> request = createRequest(httpMethod, url);
		applyTimeout(request, method);
		applyFixedHeaders(request, method);
		request = applyParams(request, method, args);
		request = applyInterceptors(request, context);
		applyDownloadMonitor(request, resolveDownloadProgressListener(method, args));

		Class<?> errorType = errorTypeOf(method);
		Class<?> returnType = method.getReturnType();
		if (returnType == CompletableFuture.class) {
			return processAsync(request, method, args, context);
		}
		if (returnType == RipResponse.class) {
			Class<?> innerType = resolveWrappedType(method.getGenericReturnType(), method);
			if (innerType == byte[].class) {
				HttpResponse<byte[]> response = executeSyncWithRetry(method, innerType, context, request::asBytes);
				return wrapResponse(response, decodeOrThrow(response, errorType, innerType));
			}
			HttpResponse<String> response = executeSyncWithRetry(method, innerType, context, request::asString);
			return wrapResponse(response, decodeOrThrow(response, errorType, innerType));
		}
		if (returnType == byte[].class) {
			HttpResponse<byte[]> response = executeSyncWithRetry(method, returnType, context, request::asBytes);
			return decodeOrThrow(response, errorType, returnType);
		}
		if (returnType == File.class) {
			File destination = resolveDestinationFile(method, args);
			HttpResponse<byte[]> response = executeSyncWithRetry(method, byte[].class, context, request::asBytes);
			byte[] bytes = (byte[]) decodeOrThrow(response, errorType, byte[].class);
			return writeToFile(destination, bytes);
		}
		HttpResponse<String> response = executeSyncWithRetry(method, returnType, context, request::asString);
		return decodeOrThrow(response, errorType, returnType);
	}

	// ---------------------------------------------------------------------
	// Non-reflective entry points below this line are used exclusively by a
	// compile-time-generated @RestClient implementation (see
	// com.shri.restinpeace.processor.RestClientProcessor) - never called
	// directly by application code. Public only because generated code lives
	// in an arbitrary consumer package, not because these are part of RIP's
	// application-facing API; see docs/design/compile-time-proxy-generation.md.
	// Generated code assembles a request by calling a sequence of these
	// (mirroring RestClientProcessor's own per-feature dispatch), then calls
	// one "finishGenerated*" method matching its return type to execute and
	// decode the response - the same request-building/execution machinery
	// the reflective path above uses, minus the reflection.
	// ---------------------------------------------------------------------

	/**
	 * Resolves a generated method's request URL from its (possibly relative)
	 * URL template, substituting {@code @PathParam}s - the generated-code
	 * counterpart of {@link #resolveUrl}, used when the method has no
	 * {@code @Url} parameter (which bypasses this entirely; see
	 * {@link #requireUrlParam}).
	 */
	public String resolveGeneratedUrl(String urlTemplate, String interfaceBaseUrl, String[] pathParamNames,
			Object[] pathParamValues) {
		return substitutePathParamsLiteral(applyBaseUrlLiteral(urlTemplate, interfaceBaseUrl), pathParamNames,
				pathParamValues);
	}

	/**
	 * The generated-code counterpart of a method annotated {@code @Url}:
	 * {@code value} used verbatim as the request URL, bypassing
	 * {@link #resolveGeneratedUrl} (and everything it would otherwise
	 * resolve - {@code @BaseUrl}, a runtime base URL, {@code @PathParam})
	 * entirely, mirroring {@link #resolveUrlParam}.
	 */
	public static String requireUrlParam(Object value, String methodDescription) {
		if (value == null) {
			throw new RestInPeaceException(String.format("Missing value for @Url parameter in method %s.",
					methodDescription));
		}
		return String.valueOf(value);
	}

	/**
	 * Builds the request and applies its {@code @Timeout} (if any) - the
	 * generated-code counterpart of {@link #createRequest(HTTPMethod, String)}
	 * plus {@link #applyTimeout(HttpRequest, Method)}, taking
	 * {@code @Timeout}'s values as literals (or {@code -1}, its own "unset"
	 * default, for a method with none) instead of a reflective lookup.
	 */
	public HttpRequest<?> createGeneratedRequest(HTTPMethod httpMethod, String url, int connectMillis,
			int readMillis) {
		HttpRequest<?> request = createRequest(httpMethod, url);
		applyTimeout(request, connectMillis, readMillis);
		return request;
	}

	/** The generated-code counterpart of {@link #applyFixedHeaders}, taking {@code @Headers}' entries as a literal. */
	public void applyGeneratedHeaders(HttpRequest<?> request, String[] headerEntries) {
		applyHeaderEntries(request, headerEntries);
	}

	/**
	 * Applies a query param or header value that has {@code @QueryParam}/
	 * {@code @HeaderParam}'s {@code required}/{@code defaultValue} semantics -
	 * shared by the reflective path's {@link #applyParams} and generated
	 * code, which calls this directly then applies the result itself
	 * ({@link #applyQueryValue} for a query param; {@code
	 * request.headerReplace(name, String.valueOf(value))} - a plain public
	 * Unirest call - for a header param, so no separate "generated header
	 * param" method is needed).
	 *
	 * @return {@code argValue} if non-{@code null}, else {@code defaultValue}
	 *         if set, else {@code null} (or a thrown exception if
	 *         {@code required})
	 */
	public Object resolveValue(Object argValue, boolean required, String defaultValue, String paramName) {
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

	/** The generated-code counterpart of {@link #applyBody}, applying a {@code @Body} value only if it's non-{@code null}. */
	public HttpRequest<?> applyGeneratedBodyIfPresent(HttpRequest<?> request, Object value) {
		if (value == null) {
			return request;
		}
		return applyBody(request, value,
				"A @Body request was attempted on an HTTP method that does not support a request body.");
	}

	/**
	 * Executes and decodes a sync request whose return type is {@code void},
	 * {@code String}, or a POJO - the generated-code counterpart of the
	 * plain (non-{@code byte[]}/{@code File}/{@code RipResponse}/
	 * {@code CompletableFuture}) branch of {@link #processRestRequest},
	 * applying registered interceptors first.
	 */
	public Object finishGeneratedSync(HttpRequest<?> request, RequestContext context, Class<?> returnType,
			Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<String> response = executeSyncWithRetry(errorType, returnType, context, request::asString,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		return decodeOrThrow(response, errorType, returnType);
	}

	/** The generated-code counterpart of {@link #processRestRequest}'s {@code byte[]}-return branch. */
	public byte[] finishGeneratedSyncBytes(HttpRequest<?> request, RequestContext context, Class<?> errorType,
			boolean hasRetry, int retryTimes, long retryDelayMillis, double retryBackoffMultiplier,
			int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<byte[]> response = executeSyncWithRetry(errorType, byte[].class, context, request::asBytes,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		return (byte[]) decodeOrThrow(response, errorType, byte[].class);
	}

	/** The generated-code counterpart of {@link #processRestRequest}'s {@code File}-return branch. */
	public File finishGeneratedSyncFile(HttpRequest<?> request, RequestContext context, File destination,
			Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<byte[]> response = executeSyncWithRetry(errorType, byte[].class, context, request::asBytes,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		byte[] bytes = (byte[]) decodeOrThrow(response, errorType, byte[].class);
		return writeToFile(destination, bytes);
	}

	/**
	 * The generated-code counterpart of {@link #processRestRequest}'s
	 * {@code RipResponse<T>}-return branch for a {@code String}/POJO
	 * {@code T} - {@code innerType} is {@code T}. Erased to
	 * {@code RipResponse<?>}; generated code casts the result to its own
	 * exact {@code RipResponse<T>} return type.
	 */
	public RipResponse<?> finishGeneratedSyncRipResponse(HttpRequest<?> request, RequestContext context,
			Class<?> innerType, Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<String> response = executeSyncWithRetry(errorType, innerType, context, request::asString,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		return (RipResponse<?>) wrapResponse(response, decodeOrThrow(response, errorType, innerType));
	}

	/** The {@code byte[]}-wrapped counterpart of {@link #finishGeneratedSyncRipResponse}, for a {@code RipResponse<byte[]>} return type. */
	public RipResponse<byte[]> finishGeneratedSyncRipResponseBytes(HttpRequest<?> request, RequestContext context,
			Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<byte[]> response = executeSyncWithRetry(errorType, byte[].class, context, request::asBytes,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		@SuppressWarnings("unchecked")
		RipResponse<byte[]> result = (RipResponse<byte[]>) wrapResponse(response,
				decodeOrThrow(response, errorType, byte[].class));
		return result;
	}

	/** The generated-code counterpart of {@link #processAsync}'s plain (non-{@code byte[]}/{@code File}/{@code RipResponse}) branch. */
	public CompletableFuture<?> finishGeneratedAsync(HttpRequest<?> request, RequestContext context,
			Class<?> returnType, Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, returnType, context, interceptedRequest::asStringAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> decodeOrThrow(response, errorType, returnType));
	}

	/** The generated-code counterpart of {@link #processAsync}'s {@code byte[]}-inner-type branch. */
	public CompletableFuture<byte[]> finishGeneratedAsyncBytes(HttpRequest<?> request, RequestContext context,
			Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, byte[].class, context, interceptedRequest::asBytesAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> (byte[]) decodeOrThrow(response, errorType, byte[].class));
	}

	/** The generated-code counterpart of {@link #processAsync}'s {@code File}-inner-type branch. */
	public CompletableFuture<File> finishGeneratedAsyncFile(HttpRequest<?> request, RequestContext context,
			File destination, Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, byte[].class, context, interceptedRequest::asBytesAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> writeToFile(destination, (byte[]) decodeOrThrow(response, errorType, byte[].class)));
	}

	/**
	 * The generated-code counterpart of {@link #processAsync}'s
	 * {@code RipResponse<T>}-inner-type branch for a {@code String}/POJO
	 * {@code T}. Erased to {@code CompletableFuture<RipResponse<?>>};
	 * generated code casts the result to its own exact
	 * {@code CompletableFuture<RipResponse<T>>} return type.
	 */
	public CompletableFuture<RipResponse<?>> finishGeneratedAsyncRipResponse(HttpRequest<?> request,
			RequestContext context, Class<?> innerType, Class<?> errorType, boolean hasRetry, int retryTimes,
			long retryDelayMillis, double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, innerType, context, interceptedRequest::asStringAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> (RipResponse<?>) wrapResponse(response,
						decodeOrThrow(response, errorType, innerType)));
	}

	/** The {@code byte[]}-wrapped counterpart of {@link #finishGeneratedAsyncRipResponse}, for a {@code CompletableFuture<RipResponse<byte[]>>} return type. */
	public CompletableFuture<RipResponse<byte[]>> finishGeneratedAsyncRipResponseBytes(HttpRequest<?> request,
			RequestContext context, Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, byte[].class, context, interceptedRequest::asBytesAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus).thenApply(response -> {
					@SuppressWarnings("unchecked")
					RipResponse<byte[]> result = (RipResponse<byte[]>) wrapResponse(response,
							decodeOrThrow(response, errorType, byte[].class));
					return result;
				});
	}

	private String applyBaseUrlLiteral(String url, String interfaceBaseUrl) {
		if (isAbsoluteUrl(url)) {
			return url;
		}
		String base = baseUrlOverride != null ? baseUrlOverride : interfaceBaseUrl;
		return joinBaseUrl(base, url);
	}

	private String substitutePathParamsLiteral(String urlTemplate, String[] names, Object[] values) {
		String url = urlTemplate;
		for (int i = 0; i < names.length; i++) {
			if (values[i] == null) {
				throw new RestInPeaceException(String.format("Missing value for path param '%s'.", names[i]));
			}
			url = url.replace("{" + names[i] + "}", encodePathValue(values[i]));
		}
		return url;
	}

	private HttpRequest<?> applyInterceptors(HttpRequest<?> request, RequestContext context) {
		if (INTERCEPTORS.isEmpty()) {
			return request;
		}
		INTERCEPTORS.forEach(interceptor -> interceptor.beforeRequest(context));
		context.getHeaders().forEach(request::header);
		return request;
	}

	private <B> void notifyAfterResponse(RequestContext context, HttpResponse<B> response, Class<?> errorType,
			Class<?> returnType) {
		if (INTERCEPTORS.isEmpty()) {
			return;
		}
		Object body = decodeBody(response, errorType, returnType);
		// LIFO, mirroring beforeRequest: the first interceptor registered wraps every
		// other one and is notified last, symmetric with it running beforeRequest first.
		List<RequestInterceptor> reversed = new ArrayList<>(INTERCEPTORS);
		Collections.reverse(reversed);
		reversed.forEach(interceptor -> interceptor.afterResponse(context, response.getStatus(), body));
	}

	private CompletableFuture<?> processAsync(HttpRequest<?> request, Method method, Object[] args,
			RequestContext context) {
		Class<?> errorType = errorTypeOf(method);
		Type futureInnerType = resolveFutureInnerType(method);
		if (isRipResponseType(futureInnerType)) {
			Class<?> innerType = resolveWrappedType(futureInnerType, method);
			if (innerType == byte[].class) {
				return executeAsyncWithRetry(method, innerType, context, request::asBytesAsync)
						.thenApply(response -> wrapResponse(response, decodeOrThrow(response, errorType, innerType)));
			}
			return executeAsyncWithRetry(method, innerType, context, request::asStringAsync)
					.thenApply(response -> wrapResponse(response, decodeOrThrow(response, errorType, innerType)));
		}
		Class<?> innerType = requireClass(futureInnerType, method);
		if (innerType == byte[].class) {
			return executeAsyncWithRetry(method, innerType, context, request::asBytesAsync)
					.thenApply(response -> decodeOrThrow(response, errorType, innerType));
		}
		if (innerType == File.class) {
			File destination = resolveDestinationFile(method, args);
			return executeAsyncWithRetry(method, byte[].class, context, request::asBytesAsync).thenApply(
					response -> writeToFile(destination, (byte[]) decodeOrThrow(response, errorType, byte[].class)));
		}
		return executeAsyncWithRetry(method, innerType, context, request::asStringAsync)
				.thenApply(response -> decodeOrThrow(response, errorType, innerType));
	}

	/**
	 * Decodes a settled response, throwing {@link RestInPeaceHttpException}
	 * for a non-2xx status instead of returning a value.
	 */
	private Object decodeOrThrow(HttpResponse<?> response, Class<?> errorType, Class<?> returnType) {
		if (!isSuccessStatus(response.getStatus())) {
			throw new RestInPeaceHttpException(response.getStatus(), toRawBodyString(response.getBody()),
					decodeBody(response, errorType, returnType));
		}
		return decodeBody(response, errorType, returnType);
	}

	/**
	 * Decodes a response's body for a success status (into {@code returnType},
	 * the same as {@link #decodeOrThrow}'s success case) or a non-2xx one
	 * (into {@code errorType}, or left as the raw body if it's {@code null})
	 * - without throwing either way, for reporting to interceptors mid-retry
	 * as well as for the final settled response. The reflective path derives
	 * {@code errorType} from {@code method.getAnnotation(ErrorType.class)};
	 * a compile-time-generated call passes its {@code @ErrorType}'s value as
	 * a literal, or {@code null} if it has none.
	 */
	private Object decodeBody(HttpResponse<?> response, Class<?> errorType, Class<?> returnType) {
		Object rawBody = response.getBody();
		if (!isSuccessStatus(response.getStatus())) {
			String rawBodyString = toRawBodyString(rawBody);
			if (errorType != null && rawBodyString != null && !rawBodyString.isEmpty()) {
				return getObjectMapper().readValue(rawBodyString, errorType);
			}
			return rawBodyString;
		}
		if (returnType == byte[].class) {
			return rawBody;
		}
		if (returnType == String.class) {
			return rawBody;
		}
		if (returnType == void.class || returnType == Void.class) {
			return null;
		}
		return getObjectMapper().readValue((String) rawBody, returnType);
	}

	/**
	 * Renders a decoded body as a {@code String} for error reporting,
	 * regardless of whether the wire representation was text or bytes - a
	 * {@code byte[]}/{@code File} method's error body is still very likely
	 * to be a text payload (a JSON or plain-text error page) even though its
	 * success body is binary.
	 */
	private static String toRawBodyString(Object rawBody) {
		if (rawBody instanceof byte[]) {
			return new String((byte[]) rawBody, StandardCharsets.UTF_8);
		}
		return (String) rawBody;
	}

	private static boolean isSuccessStatus(int status) {
		return status >= 200 && status < 300;
	}

	private <B> HttpResponse<B> executeSyncWithRetry(Method method, Class<?> returnType, RequestContext context,
			Supplier<HttpResponse<B>> call) {
		Class<?> errorType = errorTypeOf(method);
		Retry retry = method == null ? null : method.getAnnotation(Retry.class);
		if (retry == null) {
			return executeSyncWithRetry(errorType, returnType, context, call, false, 0, 0L, 1.0, EMPTY_STATUS_CODES);
		}
		return executeSyncWithRetry(errorType, returnType, context, call, true, retry.times(), retry.delayMillis(),
				retry.backoffMultiplier(), retry.retryOnStatus());
	}

	private static Class<?> errorTypeOf(Method method) {
		if (method == null) {
			return null;
		}
		ErrorType errorType = method.getAnnotation(ErrorType.class);
		return errorType == null ? null : errorType.value();
	}

	/**
	 * Non-reflective counterpart taking {@code @Retry}'s values and
	 * {@code @ErrorType}'s value as literal arguments instead of annotation
	 * lookups, shared by the reflective path above (which derives both from
	 * {@code method}) and every {@code processGenerated*} entry point used
	 * by compile-time-generated code, which has both as compile-time
	 * literals (or {@code null}/{@code false} if the method has neither).
	 */
	private <B> HttpResponse<B> executeSyncWithRetry(Class<?> errorType, Class<?> returnType, RequestContext context,
			Supplier<HttpResponse<B>> call, boolean hasRetry, int times, long delayMillis, double backoffMultiplier,
			int[] retryOnStatus) {
		if (!hasRetry) {
			HttpResponse<B> response = call.get();
			notifyAfterResponse(context, response, errorType, returnType);
			return response;
		}
		long delay = delayMillis;
		for (int attempt = 1;; attempt++) {
			HttpResponse<B> response = null;
			RuntimeException failure = null;
			try {
				response = call.get();
				notifyAfterResponse(context, response, errorType, returnType);
			} catch (RuntimeException e) {
				failure = e;
			}
			boolean retryable = failure != null || isRetryableStatus(response.getStatus(), retryOnStatus);
			if (!retryable || attempt >= times) {
				if (failure != null) {
					throw failure;
				}
				return response;
			}
			sleep(delay);
			delay = nextDelay(delay, backoffMultiplier);
		}
	}

	private <B> CompletableFuture<HttpResponse<B>> executeAsyncWithRetry(Method method, Class<?> returnType,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<B>>> call) {
		Class<?> errorType = errorTypeOf(method);
		Retry retry = method.getAnnotation(Retry.class);
		if (retry == null) {
			return executeAsyncWithRetry(errorType, returnType, context, call, false, 0, 0L, 1.0, EMPTY_STATUS_CODES);
		}
		return executeAsyncWithRetry(errorType, returnType, context, call, true, retry.times(), retry.delayMillis(),
				retry.backoffMultiplier(), retry.retryOnStatus());
	}

	/**
	 * Non-reflective counterpart taking {@code @Retry}'s values and
	 * {@code @ErrorType}'s value as literal arguments, mirroring
	 * {@link #executeSyncWithRetry(Class, Class, RequestContext, Supplier, boolean, int, long, double, int[])}
	 * for the async path.
	 */
	private <B> CompletableFuture<HttpResponse<B>> executeAsyncWithRetry(Class<?> errorType, Class<?> returnType,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<B>>> call, boolean hasRetry, int times,
			long delayMillis, double backoffMultiplier, int[] retryOnStatus) {
		if (!hasRetry) {
			return call.get().thenApply(response -> {
				notifyAfterResponse(context, response, errorType, returnType);
				return response;
			});
		}
		return attemptAsync(call, errorType, returnType, context, times, backoffMultiplier, retryOnStatus, 1,
				delayMillis);
	}

	private <B> CompletableFuture<HttpResponse<B>> attemptAsync(Supplier<CompletableFuture<HttpResponse<B>>> call,
			Class<?> errorType, Class<?> returnType, RequestContext context, int times, double backoffMultiplier,
			int[] retryOnStatus, int attempt, long delay) {
		CompletableFuture<HttpResponse<B>> result = new CompletableFuture<>();
		call.get().whenComplete((response, failure) -> {
			if (response != null) {
				notifyAfterResponse(context, response, errorType, returnType);
			}
			boolean retryable = failure != null || isRetryableStatus(response.getStatus(), retryOnStatus);
			if (!retryable || attempt >= times) {
				if (failure != null) {
					result.completeExceptionally(failure);
				} else {
					result.complete(response);
				}
				return;
			}
			RETRY_SCHEDULER.schedule(
					() -> attemptAsync(call, errorType, returnType, context, times, backoffMultiplier, retryOnStatus,
							attempt + 1, nextDelay(delay, backoffMultiplier))
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

	private static long nextDelay(long delay, double backoffMultiplier) {
		return (long) (delay * backoffMultiplier);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestInPeaceException("Interrupted while waiting to retry.", e);
		}
	}

	private Type resolveFutureInnerType(Method method) {
		Type genericReturnType = method.getGenericReturnType();
		if (!(genericReturnType instanceof ParameterizedType)) {
			throw new RestInPeaceException(
					String.format("The method %s returns a raw CompletableFuture with no type parameter.", method));
		}
		return ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
	}

	private static boolean isRipResponseType(Type type) {
		return type instanceof ParameterizedType && ((ParameterizedType) type).getRawType() == RipResponse.class;
	}

	/**
	 * Extracts a {@code RipResponse<T>}'s {@code T}, given either a method's
	 * {@code RipResponse<T>} return type or a {@code CompletableFuture<T>}'s
	 * inner {@code RipResponse<T>} type argument.
	 */
	private Class<?> resolveWrappedType(Type ripResponseType, Method method) {
		if (!(ripResponseType instanceof ParameterizedType)) {
			throw new RestInPeaceException(
					String.format("The method %s returns a raw RipResponse with no type parameter.", method));
		}
		Type innerType = ((ParameterizedType) ripResponseType).getActualTypeArguments()[0];
		return requireClass(innerType, method);
	}

	private Class<?> requireClass(Type type, Method method) {
		if (!(type instanceof Class)) {
			throw new RestInPeaceException(String.format(
					"The method %s returns CompletableFuture<%s>, which is not a supported type parameter.", method,
					type));
		}
		return (Class<?>) type;
	}

	private static Object wrapResponse(HttpResponse<?> response, Object decodedBody) {
		return new RipResponse<>(response.getStatus(), toHeaderMap(response.getHeaders()), decodedBody);
	}

	private static Map<String, List<String>> toHeaderMap(kong.unirest.Headers headers) {
		Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		headers.all().forEach(header -> result.computeIfAbsent(header.getName(), key -> new ArrayList<>())
				.add(header.getValue()));
		result.replaceAll((name, values) -> Collections.unmodifiableList(values));
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Resolves the method's request URL: a {@code @Url} parameter's runtime
	 * value verbatim if present, bypassing {@code @BaseUrl}/a runtime base
	 * URL/{@code @PathParam} entirely since there's no template for them to
	 * apply to - otherwise the usual template resolution.
	 */
	private String resolveUrl(Method method, HTTPMethod httpMethod, Object[] args) {
		String urlParamValue = resolveUrlParam(method, args);
		if (urlParamValue != null) {
			return urlParamValue;
		}
		return resolvePathParams(applyBaseUrl(method, getUrlTemplate(method, httpMethod)), method, args);
	}

	private String resolveUrlParam(Method method, Object[] args) {
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].getAnnotation(Url.class) != null) {
				Object value = args == null ? null : args[i];
				if (value == null) {
					throw new RestInPeaceException(String.format("Missing value for @Url parameter in method %s.", method));
				}
				return String.valueOf(value);
			}
		}
		return null;
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
		String base = baseUrlOverride != null ? baseUrlOverride
				: method.getDeclaringClass().getAnnotation(BaseUrl.class).value();
		return joinBaseUrl(base, url);
	}

	private static String joinBaseUrl(String base, String url) {
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
		if (unirestInstance != null) {
			return createRequest(unirestInstance, httpMethod, url);
		}
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

	private HttpRequest<?> createRequest(UnirestInstance instance, HTTPMethod httpMethod, String url) {
		switch (httpMethod) {
		case GET:
			return instance.get(url);
		case HEAD:
			return instance.head(url);
		case OPTIONS:
			return instance.options(url);
		case POST:
			return instance.post(url);
		case PUT:
			return instance.put(url);
		case PATCH:
			return instance.patch(url);
		case DELETE:
			return instance.delete(url);
		default:
			throw new RestInPeaceException(String.format("Unknown HTTP method %s.", httpMethod));
		}
	}

	private void applyTimeout(HttpRequest<?> request, Method method) {
		Timeout timeout = method.getAnnotation(Timeout.class);
		if (timeout == null) {
			return;
		}
		applyTimeout(request, timeout.connectMillis(), timeout.readMillis());
	}

	/**
	 * Non-reflective counterpart of {@link #applyTimeout(HttpRequest, Method)}
	 * for a compile-time-generated call (see {@link #processGeneratedRequest}) -
	 * {@code connectMillis}/{@code readMillis} are the generated method's
	 * {@code @Timeout} values baked in as literals, or {@code -1} (matching
	 * {@link Timeout}'s own "unset" default) for a method with no
	 * {@code @Timeout} at all.
	 */
	private void applyTimeout(HttpRequest<?> request, int connectMillis, int readMillis) {
		if (connectMillis >= 0) {
			request.connectTimeout(connectMillis);
		}
		if (readMillis >= 0) {
			request.socketTimeout(readMillis);
		}
	}

	/**
	 * Sets every {@code @Headers} entry on the request, before
	 * {@link #applyParams} runs so a {@code @HeaderParam}/{@code @HeaderMap}
	 * value for the same header name - applied via {@code headerReplace} -
	 * overrides it, since the per-call value is more specific than the
	 * always-on method annotation.
	 */
	private void applyFixedHeaders(HttpRequest<?> request, Method method) {
		Headers headers = method.getAnnotation(Headers.class);
		if (headers == null) {
			return;
		}
		applyHeaderEntries(request, headers.value());
	}

	private static void applyHeaderEntries(HttpRequest<?> request, String[] entries) {
		for (String entry : entries) {
			int colon = entry.indexOf(':');
			String name = entry.substring(0, colon).trim();
			String value = entry.substring(colon + 1).trim();
			request.header(name, value);
		}
	}

	private ObjectMapper getObjectMapper() {
		try {
			return unirestInstance != null ? unirestInstance.config().getObjectMapper()
					: Unirest.config().getObjectMapper();
		} catch (UnirestConfigException e) {
			throw new RestInPeaceException(
					"No JSON ObjectMapper is configured. RIP delegates JSON (de)serialization to the underlying "
							+ "Unirest client's ObjectMapper - call RIP.setObjectMapper(...) (or "
							+ "RipClientConfig.builder().objectMapper(...) for a per-client one) before making requests.",
					e);
		}
	}

	/**
	 * Finds the method's {@code @Destination File} parameter's value, for a
	 * method returning {@code File} (or {@code CompletableFuture<File>}).
	 * Validated to exist and be of type {@code File} at
	 * {@link com.shri.restinpeace.RIP#getClient(Class)} time; the {@code null}
	 * check here is only for a {@code null} argument at call time.
	 */
	private File resolveDestinationFile(Method method, Object[] args) {
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].getAnnotation(Destination.class) != null) {
				Object value = args == null ? null : args[i];
				if (value == null) {
					throw new RestInPeaceException(
							String.format("Missing value for @Destination parameter in method %s.", method));
				}
				return (File) value;
			}
		}
		throw new RestInPeaceException(String.format(
				"The method %s returns File but has no @Destination parameter to write the response to.", method));
	}

	private DownloadProgressListener resolveDownloadProgressListener(Method method, Object[] args) {
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].getType() == DownloadProgressListener.class) {
				return args == null ? null : (DownloadProgressListener) args[i];
			}
		}
		return null;
	}

	/** Also used directly by compile-time-generated code for a {@code DownloadProgressListener} parameter. */
	public void applyDownloadMonitor(HttpRequest<?> request, DownloadProgressListener listener) {
		if (listener == null) {
			return;
		}
		request.downloadMonitor((field, fileName, bytesWritten, totalBytes) -> listener
				.onProgress(bytesWritten == null ? 0L : bytesWritten, totalBytes == null ? -1L : totalBytes));
	}

	/** Also used directly by compile-time-generated code for an {@code UploadProgressListener} parameter. */
	public void applyUploadMonitor(MultipartBody multipartBody, UploadProgressListener listener) {
		multipartBody.uploadMonitor((field, fileName, bytesWritten, totalBytes) -> listener.onProgress(field,
				bytesWritten == null ? 0L : bytesWritten, totalBytes == null ? -1L : totalBytes));
	}

	/**
	 * The generated-code counterpart of the reflective path's own
	 * {@code ((HttpRequestWithBody) request).multiPartContent()} call for a
	 * {@code @Multipart} method - see {@link #applyParams}.
	 */
	public MultipartBody beginGeneratedMultipart(HttpRequest<?> request) {
		return ((HttpRequestWithBody) request).multiPartContent();
	}

	private static File writeToFile(File destination, byte[] bytes) {
		try {
			Files.write(destination.toPath(), bytes);
		} catch (IOException e) {
			throw new RestInPeaceException(
					String.format("Failed to write downloaded response to '%s'.", destination.getPath()), e);
		}
		return destination;
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
					applyQueryValue(request, queryParam.value(), value);
				}
			}

			HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
			if (headerParam != null) {
				Object value = resolveValue(argValue, headerParam.required(), headerParam.defaultValue(),
						headerParam.value());
				if (value != null) {
					request.headerReplace(headerParam.value(), String.valueOf(value));
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
					applyPartValue(multipartBody, part.value(), part.fileName(), value);
				}
			}

			if (parameter.getAnnotation(PartMap.class) != null && argValue != null) {
				applyPartMap(multipartBody, (Map<?, ?>) argValue);
			}

			if (parameter.getType() == UploadProgressListener.class && argValue != null) {
				applyUploadMonitor(multipartBody, (UploadProgressListener) argValue);
			}

			Body body = parameter.getAnnotation(Body.class);
			if (body != null && argValue != null) {
				request = applyBody(request, method, argValue);
			}
		}
		return request;
	}

	private static InputStream openFile(File file) {
		try {
			return new FileInputStream(file);
		} catch (FileNotFoundException e) {
			throw new RestInPeaceException(String.format("The file '%s' does not exist.", file.getPath()), e);
		}
	}

	/** Also used directly by compile-time-generated code for a {@code @PartMap} parameter. */
	public void applyPartMap(MultipartBody multipartBody, Map<?, ?> partMap) {
		partMap.forEach((name, value) -> {
			if (value != null) {
				applyPartValue(multipartBody, String.valueOf(name), "", value);
			}
		});
	}

	/** Also used directly by compile-time-generated code for a {@code @Part} parameter. */
	public void applyPartValue(MultipartBody multipartBody, String name, String fileName, Object value) {
		Object effectiveValue = value;
		String effectiveFileName = fileName;
		if (value instanceof PartValue) {
			effectiveValue = ((PartValue) value).getValue();
			effectiveFileName = ((PartValue) value).getFileName();
		}
		boolean hasFileName = effectiveFileName != null && !effectiveFileName.isEmpty();
		String resolvedFileName = hasFileName ? effectiveFileName : name;
		if (effectiveValue instanceof String) {
			multipartBody.field(name, (String) effectiveValue);
		} else if (effectiveValue instanceof File) {
			if (hasFileName) {
				// MultipartBody's (name, File, String) overload sets the part's content
				// type, not its file name - there's no direct File+fileName overload, so
				// the file is streamed instead to reach the (name, InputStream, String)
				// overload that does set the file name.
				multipartBody.field(name, openFile((File) effectiveValue), resolvedFileName);
			} else {
				multipartBody.field(name, (File) effectiveValue);
			}
		} else if (effectiveValue instanceof byte[]) {
			multipartBody.field(name, (byte[]) effectiveValue, resolvedFileName);
		} else if (effectiveValue instanceof InputStream) {
			multipartBody.field(name, (InputStream) effectiveValue, resolvedFileName);
		} else {
			throw new RestInPeaceException(String.format(
					"Unsupported @Part/@PartMap value type %s for part '%s' - only String, File, byte[], and InputStream are supported.",
					effectiveValue == null ? "null" : effectiveValue.getClass().getName(), name));
		}
	}

	/** Also used directly by compile-time-generated code for a {@code @QueryMap} parameter. */
	public void applyQueryMap(HttpRequest<?> request, Map<?, ?> queryMap) {
		queryMap.forEach((name, value) -> {
			if (value != null) {
				applyQueryValue(request, String.valueOf(name), value);
			}
		});
	}

	/**
	 * Adds a query param, repeating it once per element - instead of once
	 * with a single mangled {@code toString()} value - when {@code value} is
	 * a {@code Collection} (e.g. a {@code List<String>} of tags producing
	 * {@code ?tag=a&tag=b}), matching Unirest's own
	 * {@code queryString(String, Collection)} overload that {@code Object}-typed
	 * dispatch would otherwise never reach. Also used directly by
	 * compile-time-generated code for a {@code @QueryParam}.
	 */
	public static void applyQueryValue(HttpRequest<?> request, String name, Object value) {
		if (value instanceof Collection) {
			request.queryString(name, (Collection<?>) value);
		} else {
			request.queryString(name, value);
		}
	}

	/** Also used directly by compile-time-generated code for a {@code @HeaderMap} parameter. */
	public void applyHeaderMap(HttpRequest<?> request, Map<?, ?> headerMap) {
		headerMap.forEach((name, value) -> {
			if (value != null) {
				request.headerReplace(String.valueOf(name), String.valueOf(value));
			}
		});
	}

	private HttpRequest<?> applyBody(HttpRequest<?> request, Method method, Object value) {
		return applyBody(request, value, String.format(
				"The method %s is annotated with @Body but its HTTP method does not support a request body.",
				method));
	}

	private HttpRequest<?> applyBody(HttpRequest<?> request, Object value, String unsupportedMessage) {
		if (!(request instanceof HttpRequestWithBody)) {
			throw new RestInPeaceException(unsupportedMessage);
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
				url = url.replace("{" + pathParam.value() + "}", encodePathValue(value));
			}
		}
		return url;
	}

	/**
	 * Percent-encodes a path param value for safe substitution into a URL
	 * path segment - {@code /}, {@code ?}, {@code #}, and a space all
	 * otherwise produce a broken or subtly wrong URL (silently routing to a
	 * different path, or introducing an unintended query string). Mirrors
	 * Unirest's own path-segment encoding ({@code URLEncoder} then turning
	 * its {@code +} for space into {@code %20}, since form encoding and
	 * path/query encoding disagree on that one character).
	 */
	private static String encodePathValue(Object value) {
		try {
			return URLEncoder.encode(String.valueOf(value), "UTF-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			throw new RestInPeaceException("UTF-8 encoding is not supported by this JVM.", e);
		}
	}

}
