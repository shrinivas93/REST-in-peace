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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.cache.NoCache;
import com.shri.restinpeace.annotation.error.ErrorType;
import com.shri.restinpeace.annotation.marker.BaseUrl;
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
import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.cache.CachedResponse;
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

import kong.unirest.Cookies;
import kong.unirest.HttpMethod;
import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestSummary;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import kong.unirest.MultipartBody;
import kong.unirest.ObjectMapper;
import kong.unirest.Unirest;
import kong.unirest.UnirestConfigException;
import kong.unirest.UnirestInstance;
import kong.unirest.UnirestParsingException;

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
	private static final String NO_CACHE_ATTRIBUTE = "__ripNoCache";

	private static volatile Cache DEFAULT_CACHE;

	private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "rip-retry-scheduler");
		thread.setDaemon(true);
		return thread;
	});

	private final String baseUrlOverride;
	private final UnirestInstance unirestInstance;
	private final Cache configuredCache;

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
		this.configuredCache = null;
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
		this.configuredCache = config.getCache();
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
	 * Sets the shared default cache. See
	 * {@link com.shri.restinpeace.RIP#setCache(Cache)}.
	 *
	 * @param cache the shared default cache, or {@code null} to disable it
	 */
	public static void setDefaultCache(Cache cache) {
		DEFAULT_CACHE = cache;
	}

	/**
	 * Returns the cache this instance's calls should use - its own, from a
	 * {@link RipClientConfig}, if one was set, otherwise the shared default
	 * (read dynamically, the same way {@link #getObjectMapper()} falls back
	 * to the shared {@code Unirest} config), so a later
	 * {@link com.shri.restinpeace.RIP#setCache(Cache)} call still takes
	 * effect for an already-built client that never set its own.
	 */
	private Cache getCache() {
		return configuredCache != null ? configuredCache : DEFAULT_CACHE;
	}

	/**
	 * Marks the current call as never cacheable, regardless of any
	 * {@link Cache} configured for its client - the generated-code
	 * counterpart of the reflective path's own {@code @NoCache} check. Also
	 * used directly by compile-time-generated code for a {@code @NoCache}
	 * method.
	 *
	 * @param context the call's context, as passed to every other
	 *                {@code finishGenerated*}/{@code applyGenerated*} method
	 */
	public void markNoCache(RequestContext context) {
		context.setAttribute(NO_CACHE_ATTRIBUTE, Boolean.TRUE);
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
		if (method.getAnnotation(NoCache.class) != null) {
			markNoCache(context);
		}

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
			HttpResponse<String> response = executeSyncWithRetry(method, innerType, context,
					wrapWithCache(request, context, request::asString));
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
		HttpResponse<String> response = executeSyncWithRetry(method, returnType, context,
				wrapWithCache(request, context, request::asString));
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
	 *
	 * @param urlTemplate      the method's declared URL, possibly relative and/or
	 *                         containing {@code {name}} path param placeholders
	 * @param interfaceBaseUrl the interface's {@code @BaseUrl}, or {@code null}
	 *                         if it has none
	 * @param pathParamNames   the names of every {@code @PathParam} on the method
	 * @param pathParamValues  the corresponding argument values, in the same order
	 * @return the fully-resolved request URL
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
	 *
	 * @param value             the {@code @Url} parameter's argument value
	 * @param methodDescription the interface and method name, for the exception
	 *                          message if {@code value} is missing
	 * @return {@code value} as a {@code String}
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
	 *
	 * @param httpMethod    the HTTP method to issue
	 * @param url           the fully-resolved request URL
	 * @param connectMillis {@code @Timeout}'s {@code connectMillis}, or {@code -1} if unset
	 * @param readMillis    {@code @Timeout}'s {@code readMillis}, or {@code -1} if unset
	 * @return the built, not-yet-executed request
	 */
	public HttpRequest<?> createGeneratedRequest(HTTPMethod httpMethod, String url, int connectMillis,
			int readMillis) {
		HttpRequest<?> request = createRequest(httpMethod, url);
		applyTimeout(request, connectMillis, readMillis);
		return request;
	}

	/**
	 * The generated-code counterpart of {@link #applyFixedHeaders}, taking {@code @Headers}' entries as a literal.
	 *
	 * @param request       the request to apply the headers to
	 * @param headerEntries {@code @Headers}' {@code "Name: Value"} entries
	 */
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
	 * @param argValue     the parameter's argument value, possibly {@code null}
	 * @param required     {@code @QueryParam}/{@code @HeaderParam}'s {@code required}
	 * @param defaultValue {@code @QueryParam}/{@code @HeaderParam}'s {@code defaultValue}
	 * @param paramName    the query/header name, for the exception message if
	 *                     {@code required} and no value or default is available
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

	/**
	 * The generated-code counterpart of {@link #applyBody}, applying a {@code @Body} value only if it's non-{@code null}.
	 *
	 * @param request the request to apply the body to
	 * @param value   the {@code @Body} parameter's argument value, or {@code null}
	 * @return {@code request}, with the body applied if {@code value} was non-{@code null}
	 */
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
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param returnType            the method's declared return type to decode into
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return the decoded body, or {@code null} for a {@code void} method
	 */
	public Object finishGeneratedSync(HttpRequest<?> request, RequestContext context, Class<?> returnType,
			Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<String> response = executeSyncWithRetry(errorType, returnType, context,
				wrapWithCache(request, context, request::asString), hasRetry, retryTimes, retryDelayMillis,
				retryBackoffMultiplier, retryOnStatus);
		return decodeOrThrow(response, errorType, returnType);
	}

	/**
	 * The generated-code counterpart of {@link #processRestRequest}'s {@code byte[]}-return branch.
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return the response body's raw bytes
	 */
	public byte[] finishGeneratedSyncBytes(HttpRequest<?> request, RequestContext context, Class<?> errorType,
			boolean hasRetry, int retryTimes, long retryDelayMillis, double retryBackoffMultiplier,
			int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<byte[]> response = executeSyncWithRetry(errorType, byte[].class, context, request::asBytes,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		return (byte[]) decodeOrThrow(response, errorType, byte[].class);
	}

	/**
	 * The generated-code counterpart of {@link #processRestRequest}'s {@code File}-return branch.
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param destination           the file to write the response body into
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return {@code destination}, with the response body written into it
	 */
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
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param innerType             the class to decode {@code T} into
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return the response wrapped with its status code and headers
	 */
	public RipResponse<?> finishGeneratedSyncRipResponse(HttpRequest<?> request, RequestContext context,
			Class<?> innerType, Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		request = applyInterceptors(request, context);
		HttpResponse<String> response = executeSyncWithRetry(errorType, innerType, context,
				wrapWithCache(request, context, request::asString), hasRetry, retryTimes, retryDelayMillis,
				retryBackoffMultiplier, retryOnStatus);
		return (RipResponse<?>) wrapResponse(response, decodeOrThrow(response, errorType, innerType));
	}

	/**
	 * The {@code byte[]}-wrapped counterpart of {@link #finishGeneratedSyncRipResponse}, for a {@code RipResponse<byte[]>} return type.
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return the response's raw bytes, wrapped with its status code and headers
	 */
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

	/**
	 * The generated-code counterpart of {@link #processAsync}'s plain (non-{@code byte[]}/{@code File}/{@code RipResponse}) branch.
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param returnType            the class to decode into, once the future
	 *                              completes
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return a future of the decoded body, or of {@code null} for a {@code void} method
	 */
	public CompletableFuture<?> finishGeneratedAsync(HttpRequest<?> request, RequestContext context,
			Class<?> returnType, Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, returnType, context,
				wrapWithCacheAsync(interceptedRequest, context, interceptedRequest::asStringAsync), hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> decodeOrThrow(response, errorType, returnType));
	}

	/**
	 * The generated-code counterpart of {@link #processAsync}'s {@code byte[]}-inner-type branch.
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return a future of the response body's raw bytes
	 */
	public CompletableFuture<byte[]> finishGeneratedAsyncBytes(HttpRequest<?> request, RequestContext context,
			Class<?> errorType, boolean hasRetry, int retryTimes, long retryDelayMillis,
			double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, byte[].class, context, interceptedRequest::asBytesAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> (byte[]) decodeOrThrow(response, errorType, byte[].class));
	}

	/**
	 * The generated-code counterpart of {@link #processAsync}'s {@code File}-inner-type branch.
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param destination           the file to write the response body into
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return a future of {@code destination}, with the response body written into it
	 */
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
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param innerType             the class to decode {@code T} into
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return a future of the response wrapped with its status code and headers
	 */
	public CompletableFuture<RipResponse<?>> finishGeneratedAsyncRipResponse(HttpRequest<?> request,
			RequestContext context, Class<?> innerType, Class<?> errorType, boolean hasRetry, int retryTimes,
			long retryDelayMillis, double retryBackoffMultiplier, int[] retryOnStatus) {
		HttpRequest<?> interceptedRequest = applyInterceptors(request, context);
		return executeAsyncWithRetry(errorType, innerType, context,
				wrapWithCacheAsync(interceptedRequest, context, interceptedRequest::asStringAsync), hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> (RipResponse<?>) wrapResponse(response,
						decodeOrThrow(response, errorType, innerType)));
	}

	/**
	 * The {@code byte[]}-wrapped counterpart of {@link #finishGeneratedAsyncRipResponse}, for a {@code CompletableFuture<RipResponse<byte[]>>} return type.
	 *
	 * @param request               the request to execute
	 * @param context               the interceptor/logging context for this call
	 * @param errorType             the class to decode a non-2xx response's body
	 *                              into, or {@code null} for none
	 * @param hasRetry              whether the method is annotated {@code @Retry}
	 * @param retryTimes            {@code @Retry}'s {@code times}, meaningless if
	 *                              {@code hasRetry} is {@code false}
	 * @param retryDelayMillis      {@code @Retry}'s {@code delayMillis}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @param retryBackoffMultiplier {@code @Retry}'s {@code backoffMultiplier},
	 *                              meaningless if {@code hasRetry} is {@code false}
	 * @param retryOnStatus         {@code @Retry}'s {@code retryOnStatus}, meaningless
	 *                              if {@code hasRetry} is {@code false}
	 * @return a future of the response's raw bytes, wrapped with its status code and headers
	 */
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

	/**
	 * Wraps a {@code String}-decoding network call with response caching -
	 * only ever engaged for a {@code GET} whose client has a {@link Cache}
	 * configured and isn't {@code @NoCache}, in which case {@code call}
	 * itself may never run at all (a fresh cache hit). Not applicable to a
	 * {@code byte[]}/{@code File} response - see {@link Cache}'s javadoc.
	 *
	 * @param request the request about to be sent - mutated with
	 *                {@code If-None-Match}/{@code If-Modified-Since} when a
	 *                stale, revalidatable entry exists
	 * @param context this call's context, used for its HTTP method, URL,
	 *                and {@code @NoCache} marker
	 * @param call    the real network call
	 * @return {@code call} unchanged if caching doesn't apply here,
	 *         otherwise a wrapping supplier that may serve a cached response
	 *         instead of invoking {@code call} at all
	 */
	private Supplier<HttpResponse<String>> wrapWithCache(HttpRequest<?> request, RequestContext context,
			Supplier<HttpResponse<String>> call) {
		Cache cache = getCache();
		if (!isCacheable(cache, context)) {
			return call;
		}
		String key = cacheKey(context);
		return () -> {
			CachedResponse cached = cache.get(key);
			boolean sameVariant = cached != null && matchesVary(cached, request);
			boolean differentVariantCached = cached != null && !sameVariant;
			if (sameVariant && cached.isFresh()) {
				return toSyntheticResponse(cached);
			}
			if (sameVariant) {
				applyRevalidationHeaders(request, cached);
			}
			return reconcileCache(cache, key, sameVariant ? cached : null, differentVariantCached, call.get(),
					request);
		};
	}

	/**
	 * The async counterpart of {@link #wrapWithCache}, for a
	 * {@code CompletableFuture}-returning call.
	 *
	 * @param request the request about to be sent
	 * @param context this call's context
	 * @param call    the real, asynchronous network call
	 * @return {@code call} unchanged if caching doesn't apply here,
	 *         otherwise a wrapping supplier that may complete immediately
	 *         with a cached response instead of invoking {@code call} at all
	 */
	private Supplier<CompletableFuture<HttpResponse<String>>> wrapWithCacheAsync(HttpRequest<?> request,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<String>>> call) {
		Cache cache = getCache();
		if (!isCacheable(cache, context)) {
			return call;
		}
		String key = cacheKey(context);
		return () -> {
			CachedResponse cached = cache.get(key);
			boolean sameVariant = cached != null && matchesVary(cached, request);
			boolean differentVariantCached = cached != null && !sameVariant;
			if (sameVariant && cached.isFresh()) {
				return CompletableFuture.completedFuture(toSyntheticResponse(cached));
			}
			if (sameVariant) {
				applyRevalidationHeaders(request, cached);
			}
			CachedResponse staleEntry = sameVariant ? cached : null;
			return call.get().thenApply(
					response -> reconcileCache(cache, key, staleEntry, differentVariantCached, response, request));
		};
	}

	private static boolean isCacheable(Cache cache, RequestContext context) {
		return cache != null && context.getHttpMethod() == HTTPMethod.GET
				&& !Boolean.TRUE.equals(context.getAttribute(NO_CACHE_ATTRIBUTE));
	}

	private static String cacheKey(RequestContext context) {
		return context.getHttpMethod() + " " + context.getUrl();
	}

	private static void applyRevalidationHeaders(HttpRequest<?> request, CachedResponse cached) {
		String etag = cached.getHeader("ETag");
		if (etag != null) {
			request.headerReplace("If-None-Match", etag);
		}
		String lastModified = cached.getHeader("Last-Modified");
		if (lastModified != null) {
			request.headerReplace("If-Modified-Since", lastModified);
		}
	}

	/**
	 * Reconciles a real network response against {@code staleEntry} (the
	 * previously-cached entry for this exact request's {@code Vary}
	 * variant, if any) once a call has actually gone out - either because
	 * there was nothing cached, a different variant was cached, or a stale
	 * entry needed revalidating. A {@code 304 Not Modified} against a known
	 * stale entry refreshes its freshness window and hands back its stored
	 * body unchanged; any other outcome stores {@code key} per the
	 * response's own {@code Cache-Control}/{@code ETag}/{@code Last-Modified}
	 * (snapshotting this request's values for whatever its {@code Vary}
	 * header names), or evicts it - unless {@code leaveExistingEntryAlone}
	 * is set, since evicting then would wrongly discard a still-valid,
	 * different variant this call has nothing to do with.
	 */
	private static HttpResponse<String> reconcileCache(Cache cache, String key, CachedResponse staleEntry,
			boolean leaveExistingEntryAlone, HttpResponse<String> response, HttpRequest<?> request) {
		Map<String, List<String>> responseHeaders = toHeaderMap(response.getHeaders());
		if (response.getStatus() == 304 && staleEntry != null) {
			CachedResponse refreshed = new CachedResponse(staleEntry.getStatus(), staleEntry.getHeaders(),
					staleEntry.getBody(), freshUntil(responseHeaders), staleEntry.getVaryRequestHeaders());
			cache.put(key, refreshed);
			return toSyntheticResponse(refreshed);
		}
		if (isSuccessStatus(response.getStatus()) && isStorable(responseHeaders)) {
			Map<String, String> varySnapshot = captureVaryValues(request, varyHeaderNames(responseHeaders));
			cache.put(key, new CachedResponse(response.getStatus(), responseHeaders, response.getBody(),
					freshUntil(responseHeaders), varySnapshot));
		} else if (!leaveExistingEntryAlone) {
			cache.evict(key);
		}
		return response;
	}

	private static boolean isStorable(Map<String, List<String>> headers) {
		CacheDirectives directives = CacheDirectives.parse(firstHeader(headers, "Cache-Control"));
		if (directives.noStore || isWildcardVary(headers)) {
			return false;
		}
		return directives.maxAgeSeconds != null || firstHeader(headers, "ETag") != null
				|| firstHeader(headers, "Last-Modified") != null;
	}

	/**
	 * Whether {@code cached} is usable at all for the current request - its
	 * response had no {@code Vary} header (matches every request), or this
	 * request's current values for every header {@code Vary} named are
	 * identical to the ones snapshotted when {@code cached} was stored.
	 */
	private static boolean matchesVary(CachedResponse cached, HttpRequest<?> request) {
		for (Map.Entry<String, String> varyHeader : cached.getVaryRequestHeaders().entrySet()) {
			String currentValue = request.getHeaders().getFirst(varyHeader.getKey());
			if (!Objects.equals(varyHeader.getValue(), currentValue)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A {@code Vary: *} response varies unpredictably (by definition,
	 * un-cacheable via header comparison) and must never be stored - the
	 * one {@code Vary} value that means "don't cache this at all" rather
	 * than "cache one variant per combination of these header values".
	 */
	private static boolean isWildcardVary(Map<String, List<String>> headers) {
		for (String name : varyHeaderNames(headers)) {
			if (name.equals("*")) {
				return true;
			}
		}
		return false;
	}

	private static List<String> varyHeaderNames(Map<String, List<String>> headers) {
		String vary = firstHeader(headers, "Vary");
		if (vary == null) {
			return Collections.emptyList();
		}
		List<String> names = new ArrayList<>();
		for (String name : vary.split(",")) {
			String trimmed = name.trim();
			if (!trimmed.isEmpty()) {
				names.add(trimmed);
			}
		}
		return names;
	}

	private static Map<String, String> captureVaryValues(HttpRequest<?> request, List<String> varyHeaderNames) {
		if (varyHeaderNames.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : varyHeaderNames) {
			values.put(name, request.getHeaders().getFirst(name));
		}
		return values;
	}

	private static long freshUntil(Map<String, List<String>> headers) {
		CacheDirectives directives = CacheDirectives.parse(firstHeader(headers, "Cache-Control"));
		if (!directives.noCache && directives.maxAgeSeconds != null) {
			return System.currentTimeMillis() + directives.maxAgeSeconds * 1000L;
		}
		return System.currentTimeMillis(); // no (usable) freshness window - always revalidate
	}

	private static String firstHeader(Map<String, List<String>> headers, String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/** Parsed {@code Cache-Control} response directives relevant to caching a {@code GET}. */
	private static final class CacheDirectives {
		final boolean noStore;
		final boolean noCache;
		final Long maxAgeSeconds;

		private CacheDirectives(boolean noStore, boolean noCache, Long maxAgeSeconds) {
			this.noStore = noStore;
			this.noCache = noCache;
			this.maxAgeSeconds = maxAgeSeconds;
		}

		static CacheDirectives parse(String headerValue) {
			if (headerValue == null) {
				return new CacheDirectives(false, false, null);
			}
			boolean noStore = false;
			boolean noCache = false;
			Long maxAgeSeconds = null;
			for (String directive : headerValue.split(",")) {
				String trimmed = directive.trim().toLowerCase(Locale.ROOT);
				if (trimmed.equals("no-store")) {
					noStore = true;
				} else if (trimmed.equals("no-cache")) {
					noCache = true;
				} else if (trimmed.startsWith("max-age=")) {
					maxAgeSeconds = parseMaxAge(trimmed.substring("max-age=".length()).trim());
				}
			}
			return new CacheDirectives(noStore, noCache, maxAgeSeconds);
		}

		private static Long parseMaxAge(String value) {
			try {
				return Math.max(0L, Long.parseLong(value));
			} catch (NumberFormatException e) {
				return null; // malformed - fail open, same as no max-age at all
			}
		}
	}

	private static HttpResponse<String> toSyntheticResponse(CachedResponse cached) {
		kong.unirest.Headers headers = new kong.unirest.Headers();
		cached.getHeaders().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
		return new CachedHttpResponse<>(cached.getStatus(), headers, cached.getBody());
	}

	/**
	 * A {@code kong.unirest.HttpResponse} backed by a {@link CachedResponse}
	 * instead of an actual network round trip - handed to the same
	 * {@code decodeOrThrow}/{@code notifyAfterResponse}/{@code wrapResponse}
	 * machinery a real response would go through, so a cache hit is
	 * decoded, reported to interceptors, and wrapped in a
	 * {@code RipResponse} exactly like any other response. Only
	 * {@link #getStatus()}/{@link #getBody()}/{@link #getHeaders()} are ever
	 * actually exercised by that machinery; the rest of this interface is
	 * implemented plainly (a cached entry is never itself a failure status,
	 * since only a successful response is ever stored).
	 */
	private static final class CachedHttpResponse<T> implements HttpResponse<T> {
		private final int status;
		private final kong.unirest.Headers headers;
		private final T body;

		CachedHttpResponse(int status, kong.unirest.Headers headers, T body) {
			this.status = status;
			this.headers = headers;
			this.body = body;
		}

		@Override
		public int getStatus() {
			return status;
		}

		@Override
		public String getStatusText() {
			return "";
		}

		@Override
		public kong.unirest.Headers getHeaders() {
			return headers;
		}

		@Override
		public T getBody() {
			return body;
		}

		@Override
		public Optional<UnirestParsingException> getParsingError() {
			return Optional.empty();
		}

		@Override
		public <V> V mapBody(Function<T, V> func) {
			return func.apply(body);
		}

		@Override
		public <V> HttpResponse<V> map(Function<T, V> func) {
			return new CachedHttpResponse<>(status, headers, func.apply(body));
		}

		@Override
		public HttpResponse<T> ifSuccess(Consumer<HttpResponse<T>> consumer) {
			if (isSuccess()) {
				consumer.accept(this);
			}
			return this;
		}

		@Override
		public HttpResponse<T> ifFailure(Consumer<HttpResponse<T>> consumer) {
			if (!isSuccess()) {
				consumer.accept(this);
			}
			return this;
		}

		@Override
		public <E> HttpResponse<T> ifFailure(Class<? extends E> type, Consumer<HttpResponse<E>> consumer) {
			return this;
		}

		@Override
		public boolean isSuccess() {
			return status >= 200 && status < 300;
		}

		@Override
		public <E> E mapError(Class<? extends E> type) {
			return null;
		}

		@Override
		public Cookies getCookies() {
			return new Cookies();
		}

		@Override
		public HttpRequestSummary getRequestSummary() {
			return new HttpRequestSummary() {
				@Override
				public HttpMethod getHttpMethod() {
					return HttpMethod.GET;
				}

				@Override
				public String getUrl() {
					return "";
				}

				@Override
				public String getRawPath() {
					return "";
				}

				@Override
				public String asString() {
					return "GET";
				}
			};
		}
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
			return executeAsyncWithRetry(method, innerType, context,
					wrapWithCacheAsync(request, context, request::asStringAsync))
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
		return executeAsyncWithRetry(method, innerType, context,
				wrapWithCacheAsync(request, context, request::asStringAsync))
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

	/**
	 * Also used directly by compile-time-generated code for a {@code DownloadProgressListener} parameter.
	 *
	 * @param request  the request to monitor
	 * @param listener the listener to notify, or {@code null} for none
	 */
	public void applyDownloadMonitor(HttpRequest<?> request, DownloadProgressListener listener) {
		if (listener == null) {
			return;
		}
		request.downloadMonitor((field, fileName, bytesWritten, totalBytes) -> listener
				.onProgress(bytesWritten == null ? 0L : bytesWritten, totalBytes == null ? -1L : totalBytes));
	}

	/**
	 * Also used directly by compile-time-generated code for an {@code UploadProgressListener} parameter.
	 *
	 * @param multipartBody the multipart body to monitor
	 * @param listener      the listener to notify (callers only invoke this once
	 *                      a non-{@code null} listener argument is confirmed)
	 */
	public void applyUploadMonitor(MultipartBody multipartBody, UploadProgressListener listener) {
		multipartBody.uploadMonitor((field, fileName, bytesWritten, totalBytes) -> listener.onProgress(field,
				bytesWritten == null ? 0L : bytesWritten, totalBytes == null ? -1L : totalBytes));
	}

	/**
	 * The generated-code counterpart of the reflective path's own
	 * {@code ((HttpRequestWithBody) request).multiPartContent()} call for a
	 * {@code @Multipart} method - see {@link #applyParams}.
	 *
	 * @param request the request to convert to a multipart body
	 * @return {@code request}, as a {@code MultipartBody}
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

		List<String> formFields = null;
		if (method.getAnnotation(FormUrlEncoded.class) != null) {
			formFields = new ArrayList<>();
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

			Field field = parameter.getAnnotation(Field.class);
			if (field != null) {
				Object value = resolveValue(argValue, field.required(), RIPConstant.DEFAULT, field.value());
				if (value != null) {
					appendFormField(formFields, field.value(), value);
				}
			}

			if (parameter.getAnnotation(FieldMap.class) != null && argValue != null) {
				appendFormFieldMap(formFields, (Map<?, ?>) argValue);
			}

			Body body = parameter.getAnnotation(Body.class);
			if (body != null && argValue != null) {
				request = applyBody(request, method, argValue);
			}
		}
		if (formFields != null) {
			request = applyFormUrlEncodedBody(request, formFields);
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

	/**
	 * Also used directly by compile-time-generated code for a {@code @PartMap} parameter.
	 *
	 * @param multipartBody the multipart body to add parts to
	 * @param partMap       the {@code @PartMap} parameter's argument value; a
	 *                      {@code null}-valued entry is skipped
	 */
	public void applyPartMap(MultipartBody multipartBody, Map<?, ?> partMap) {
		partMap.forEach((name, value) -> {
			if (value != null) {
				applyPartValue(multipartBody, String.valueOf(name), "", value);
			}
		});
	}

	/**
	 * Also used directly by compile-time-generated code for a {@code @Part} parameter.
	 *
	 * @param multipartBody the multipart body to add the part to
	 * @param name          the part's field name
	 * @param fileName      the file name to send a {@code File}/{@code byte[]}/
	 *                      {@code InputStream} part under, or empty to use
	 *                      {@code name} (or, for a {@code File}, its own name)
	 * @param value         the part's value - a {@code String}, {@code File},
	 *                      {@code byte[]}, {@code InputStream}, or a
	 *                      {@link PartValue} wrapping one of those with its own
	 *                      file name
	 */
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

	/**
	 * Also used directly by compile-time-generated code for a {@code @FieldMap} parameter.
	 *
	 * @param formFields the accumulator to append encoded {@code name=value}
	 *                   pairs to
	 * @param fieldMap   the {@code @FieldMap} parameter's argument value; a
	 *                   {@code null}-valued entry is skipped
	 */
	public void appendFormFieldMap(List<String> formFields, Map<?, ?> fieldMap) {
		fieldMap.forEach((name, value) -> {
			if (value != null) {
				appendFormField(formFields, String.valueOf(name), value);
			}
		});
	}

	/**
	 * Appends one {@code @Field}/{@code @FieldMap} entry to a
	 * {@code @FormUrlEncoded} method's accumulated body, repeating {@code name}
	 * once per element - instead of once with a single mangled
	 * {@code toString()} value - when {@code value} is a {@code Collection}
	 * (e.g. a {@code List<String>} of tags producing {@code tag=a&tag=b}),
	 * the same convention {@link #applyQueryValue} uses for
	 * {@code @QueryParam}. Also used directly by compile-time-generated code
	 * for a {@code @Field} parameter.
	 *
	 * @param formFields the accumulator to append encoded {@code name=value}
	 *                   pairs to
	 * @param name       the field name
	 * @param value      the field's value; a {@code Collection} is repeated
	 *                   once per non-{@code null} element, any other value
	 *                   once via {@code String.valueOf(...)}
	 */
	public void appendFormField(List<String> formFields, String name, Object value) {
		if (value instanceof Collection) {
			for (Object element : (Collection<?>) value) {
				if (element != null) {
					formFields.add(encodeFormPair(name, element));
				}
			}
		} else {
			formFields.add(encodeFormPair(name, value));
		}
	}

	/**
	 * Finalizes a {@code @FormUrlEncoded} method's accumulated
	 * {@code name=value} pairs into the request's body, joined with
	 * {@code &} and sent as {@code application/x-www-form-urlencoded} - the
	 * generated-code counterpart of the reflective path's own finalization in
	 * {@link #applyParams}. Unlike {@code @Multipart}, Unirest has no
	 * dedicated url-encoded body builder to accumulate into directly, so the
	 * encoded string is built here instead and applied as a plain body.
	 *
	 * @param request    the request to apply the encoded body to
	 * @param formFields the accumulated encoded {@code name=value} pairs
	 * @return {@code request}, with the encoded body applied
	 */
	public HttpRequest<?> applyFormUrlEncodedBody(HttpRequest<?> request, List<String> formFields) {
		if (!(request instanceof HttpRequestWithBody)) {
			throw new RestInPeaceException(
					"A @FormUrlEncoded request was attempted on an HTTP method that does not support a request body.");
		}
		String body = String.join("&", formFields);
		return ((HttpRequestWithBody) request).body(body).contentType("application/x-www-form-urlencoded");
	}

	private static String encodeFormPair(String name, Object value) {
		return encodeFormValue(name) + "=" + encodeFormValue(value);
	}

	private static String encodeFormValue(Object value) {
		try {
			return URLEncoder.encode(String.valueOf(value), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RestInPeaceException("UTF-8 encoding is not supported by this JVM.", e);
		}
	}

	/**
	 * Also used directly by compile-time-generated code for a {@code @QueryMap} parameter.
	 *
	 * @param request  the request to add query params to
	 * @param queryMap the {@code @QueryMap} parameter's argument value; a
	 *                 {@code null}-valued entry is skipped
	 */
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
	 *
	 * @param request the request to add the query param to
	 * @param name    the query param's name
	 * @param value   the query param's value; a {@code Collection} is repeated
	 *                once per element, any other value once via {@code toString()}
	 */
	public static void applyQueryValue(HttpRequest<?> request, String name, Object value) {
		if (value instanceof Collection) {
			request.queryString(name, (Collection<?>) value);
		} else {
			request.queryString(name, value);
		}
	}

	/**
	 * Also used directly by compile-time-generated code for a {@code @HeaderMap} parameter.
	 *
	 * @param request   the request to add headers to
	 * @param headerMap the {@code @HeaderMap} parameter's argument value; a
	 *                  {@code null}-valued entry is skipped
	 */
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
