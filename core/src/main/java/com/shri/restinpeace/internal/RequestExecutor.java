package com.shri.restinpeace.internal;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.shri.restinpeace.annotation.cache.NoCache;
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
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.constant.RIPConstants;
import com.shri.restinpeace.download.DownloadProgressListener;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;
import com.shri.restinpeace.upload.UploadProgressListener;
import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.RipResponse;

import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import kong.unirest.MultipartBody;
import kong.unirest.Unirest;
import kong.unirest.UnirestInstance;

/**
 * Builds and executes the actual HTTP request for a {@code @RestClient}
 * method call, applying path/query/header/body parameters and registered
 * {@link RequestInterceptor}s. Used internally by
 * {@link com.shri.restinpeace.proxy.RestClientInvocationHandler}; not part
 * of the library's public API - use {@link com.shri.restinpeace.RIP}
 * instead.
 *
 * <p>
 * Genuinely separate concerns that grew large enough to earn their own
 * class are delegated out to package-private collaborators instead of
 * living here as private methods: response caching ({@link CacheCoordinator}),
 * {@code @FormUrlEncoded} body building ({@link FormEncoder}), the
 * {@code @Retry} loop ({@link RetryExecutor}), {@code @Multipart} body
 * building ({@link MultipartEncoder}), URL template resolution
 * ({@link UrlResolver}), response decoding ({@link ResponseDecoder}), and
 * interceptor dispatch ({@link InterceptorDispatcher}). Every method
 * generated code calls directly (see
 * {@code com.shri.restinpeace.processor.RestClientProcessor}) stays here
 * regardless, since generated code's field type is fixed as
 * {@code RequestExecutor} - those methods are thin delegates to the
 * collaborator that actually does the work.
 */
public class RequestExecutor {

	private final String baseUrlOverride;
	private final UnirestInstance unirestInstance;
	private final CacheCoordinator cacheCoordinator;
	private final FormEncoder formEncoder = new FormEncoder();
	private final MultipartEncoder multipartEncoder = new MultipartEncoder();
	private final UrlResolver urlResolver;
	private final ResponseDecoder responseDecoder;
	private final InterceptorDispatcher interceptorDispatcher;
	private final RetryExecutor retryExecutor;

	/** Creates a processor with no runtime base URL override. Cheap and stateless beyond the shared interceptor registry. */
	public RequestExecutor() {
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
	public RequestExecutor(String baseUrlOverride) {
		this.baseUrlOverride = baseUrlOverride;
		this.unirestInstance = null;
		this.cacheCoordinator = new CacheCoordinator(null);
		this.urlResolver = new UrlResolver(baseUrlOverride);
		this.responseDecoder = new ResponseDecoder(null);
		this.interceptorDispatcher = new InterceptorDispatcher(Collections.emptyList(), responseDecoder);
		this.retryExecutor = new RetryExecutor(interceptorDispatcher);
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
	public RequestExecutor(RipClientConfig config) {
		this.baseUrlOverride = config.getBaseUrl();
		boolean needsOwnInstance = config.getConnectTimeoutMillis() != null || config.getReadTimeoutMillis() != null
				|| config.getProxyHost() != null || config.getObjectMapper() != null;
		this.unirestInstance = needsOwnInstance ? buildInstance(config) : null;
		this.cacheCoordinator = new CacheCoordinator(config.getCache());
		this.urlResolver = new UrlResolver(baseUrlOverride);
		this.responseDecoder = new ResponseDecoder(unirestInstance);
		this.interceptorDispatcher = new InterceptorDispatcher(config.getInterceptors(), responseDecoder);
		this.retryExecutor = new RetryExecutor(interceptorDispatcher);
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
		InterceptorDispatcher.addInterceptor(interceptor);
	}

	/** Removes all registered interceptors. */
	public static void clearInterceptors() {
		InterceptorDispatcher.clearInterceptors();
	}

	/**
	 * Sets the shared default cache. See
	 * {@link com.shri.restinpeace.RIP#setCache(Cache)}.
	 *
	 * @param cache the shared default cache, or {@code null} to disable it
	 */
	public static void setDefaultCache(Cache cache) {
		CacheCoordinator.setDefaultCache(cache);
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
		context.setAttribute(CacheCoordinator.NO_CACHE_ATTRIBUTE, Boolean.TRUE);
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
		String url = urlResolver.resolveUrl(method, httpMethod, args);
		RequestContext context = new RequestContext(httpMethod, url);
		if (method.getAnnotation(NoCache.class) != null) {
			markNoCache(context);
		}

		HttpRequest<?> request = createRequest(httpMethod, url);
		applyTimeout(request, method);
		applyFixedHeaders(request, method);
		applyIdempotencyKeyIfNeeded(request, method);
		request = applyParams(request, method, args);
		request = interceptorDispatcher.applyInterceptors(request, context);
		applyDownloadMonitor(request, resolveDownloadProgressListener(method, args));

		Class<?> errorType = ResponseDecoder.errorTypeOf(method);
		Class<?> returnType = method.getReturnType();
		if (returnType == CompletableFuture.class) {
			return processAsync(request, method, args, context);
		}
		if (returnType == RipResponse.class) {
			Class<?> innerType = resolveWrappedType(method.getGenericReturnType(), method);
			if (innerType == byte[].class) {
				HttpResponse<byte[]> response = retryExecutor.executeSyncWithRetry(method, innerType, context, request::asBytes);
				return ResponseDecoder.wrapResponse(response, responseDecoder.decodeOrThrow(response, errorType, innerType));
			}
			HttpResponse<String> response = retryExecutor.executeSyncWithRetry(method, innerType, context,
					cacheCoordinator.wrapWithCache(request, context, request::asString));
			return ResponseDecoder.wrapResponse(response, responseDecoder.decodeOrThrow(response, errorType, innerType));
		}
		if (returnType == byte[].class) {
			HttpResponse<byte[]> response = retryExecutor.executeSyncWithRetry(method, returnType, context, request::asBytes);
			return responseDecoder.decodeOrThrow(response, errorType, returnType);
		}
		if (returnType == File.class) {
			File destination = resolveDestinationFile(method, args);
			HttpResponse<byte[]> response = retryExecutor.executeSyncWithRetry(method, byte[].class, context, request::asBytes);
			byte[] bytes = (byte[]) responseDecoder.decodeOrThrow(response, errorType, byte[].class);
			return writeToFile(destination, bytes);
		}
		HttpResponse<String> response = retryExecutor.executeSyncWithRetry(method, returnType, context,
				cacheCoordinator.wrapWithCache(request, context, request::asString));
		return responseDecoder.decodeOrThrow(response, errorType, returnType);
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
	 * counterpart of {@code UrlResolver}'s own {@code resolveUrl}, used when the method has no
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
		return urlResolver.resolveGeneratedUrl(urlTemplate, interfaceBaseUrl, pathParamNames, pathParamValues);
	}

	/**
	 * The generated-code counterpart of a method annotated {@code @Url}:
	 * {@code value} used verbatim as the request URL, bypassing
	 * {@link #resolveGeneratedUrl} (and everything it would otherwise
	 * resolve - {@code @BaseUrl}, a runtime base URL, {@code @PathParam})
	 * entirely, mirroring {@code UrlResolver}'s own {@code resolveUrlParam}.
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
		if (!RIPConstants.DEFAULT.equals(defaultValue)) {
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
		request = interceptorDispatcher.applyInterceptors(request, context);
		HttpResponse<String> response = retryExecutor.executeSyncWithRetry(errorType, returnType, context,
				cacheCoordinator.wrapWithCache(request, context, request::asString), hasRetry, retryTimes, retryDelayMillis,
				retryBackoffMultiplier, retryOnStatus);
		return responseDecoder.decodeOrThrow(response, errorType, returnType);
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
		request = interceptorDispatcher.applyInterceptors(request, context);
		HttpResponse<byte[]> response = retryExecutor.executeSyncWithRetry(errorType, byte[].class, context, request::asBytes,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		return (byte[]) responseDecoder.decodeOrThrow(response, errorType, byte[].class);
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
		request = interceptorDispatcher.applyInterceptors(request, context);
		HttpResponse<byte[]> response = retryExecutor.executeSyncWithRetry(errorType, byte[].class, context, request::asBytes,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		byte[] bytes = (byte[]) responseDecoder.decodeOrThrow(response, errorType, byte[].class);
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
		request = interceptorDispatcher.applyInterceptors(request, context);
		HttpResponse<String> response = retryExecutor.executeSyncWithRetry(errorType, innerType, context,
				cacheCoordinator.wrapWithCache(request, context, request::asString), hasRetry, retryTimes, retryDelayMillis,
				retryBackoffMultiplier, retryOnStatus);
		return (RipResponse<?>) ResponseDecoder.wrapResponse(response,
				responseDecoder.decodeOrThrow(response, errorType, innerType));
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
		request = interceptorDispatcher.applyInterceptors(request, context);
		HttpResponse<byte[]> response = retryExecutor.executeSyncWithRetry(errorType, byte[].class, context, request::asBytes,
				hasRetry, retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus);
		@SuppressWarnings("unchecked")
		RipResponse<byte[]> result = (RipResponse<byte[]>) ResponseDecoder.wrapResponse(response,
				responseDecoder.decodeOrThrow(response, errorType, byte[].class));
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
		HttpRequest<?> interceptedRequest = interceptorDispatcher.applyInterceptors(request, context);
		return retryExecutor.executeAsyncWithRetry(errorType, returnType, context,
				cacheCoordinator.wrapWithCacheAsync(interceptedRequest, context, interceptedRequest::asStringAsync), hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> responseDecoder.decodeOrThrow(response, errorType, returnType));
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
		HttpRequest<?> interceptedRequest = interceptorDispatcher.applyInterceptors(request, context);
		return retryExecutor.executeAsyncWithRetry(errorType, byte[].class, context, interceptedRequest::asBytesAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> (byte[]) responseDecoder.decodeOrThrow(response, errorType, byte[].class));
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
		HttpRequest<?> interceptedRequest = interceptorDispatcher.applyInterceptors(request, context);
		return retryExecutor.executeAsyncWithRetry(errorType, byte[].class, context, interceptedRequest::asBytesAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> writeToFile(destination, (byte[]) responseDecoder.decodeOrThrow(response, errorType, byte[].class)));
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
		HttpRequest<?> interceptedRequest = interceptorDispatcher.applyInterceptors(request, context);
		return retryExecutor.executeAsyncWithRetry(errorType, innerType, context,
				cacheCoordinator.wrapWithCacheAsync(interceptedRequest, context, interceptedRequest::asStringAsync), hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus)
				.thenApply(response -> (RipResponse<?>) ResponseDecoder.wrapResponse(response,
						responseDecoder.decodeOrThrow(response, errorType, innerType)));
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
		HttpRequest<?> interceptedRequest = interceptorDispatcher.applyInterceptors(request, context);
		return retryExecutor.executeAsyncWithRetry(errorType, byte[].class, context, interceptedRequest::asBytesAsync, hasRetry,
				retryTimes, retryDelayMillis, retryBackoffMultiplier, retryOnStatus).thenApply(response -> {
					@SuppressWarnings("unchecked")
					RipResponse<byte[]> result = (RipResponse<byte[]>) ResponseDecoder.wrapResponse(response,
							responseDecoder.decodeOrThrow(response, errorType, byte[].class));
					return result;
				});
	}

	private CompletableFuture<?> processAsync(HttpRequest<?> request, Method method, Object[] args,
			RequestContext context) {
		Class<?> errorType = ResponseDecoder.errorTypeOf(method);
		Type futureInnerType = resolveFutureInnerType(method);
		if (isRipResponseType(futureInnerType)) {
			Class<?> innerType = resolveWrappedType(futureInnerType, method);
			if (innerType == byte[].class) {
				return retryExecutor.executeAsyncWithRetry(method, innerType, context, request::asBytesAsync)
						.thenApply(response -> ResponseDecoder.wrapResponse(response,
								responseDecoder.decodeOrThrow(response, errorType, innerType)));
			}
			return retryExecutor.executeAsyncWithRetry(method, innerType, context,
					cacheCoordinator.wrapWithCacheAsync(request, context, request::asStringAsync))
					.thenApply(response -> ResponseDecoder.wrapResponse(response,
							responseDecoder.decodeOrThrow(response, errorType, innerType)));
		}
		Class<?> innerType = requireClass(futureInnerType, method);
		if (innerType == byte[].class) {
			return retryExecutor.executeAsyncWithRetry(method, innerType, context, request::asBytesAsync)
					.thenApply(response -> responseDecoder.decodeOrThrow(response, errorType, innerType));
		}
		if (innerType == File.class) {
			File destination = resolveDestinationFile(method, args);
			return retryExecutor.executeAsyncWithRetry(method, byte[].class, context, request::asBytesAsync).thenApply(
					response -> writeToFile(destination, (byte[]) responseDecoder.decodeOrThrow(response, errorType, byte[].class)));
		}
		return retryExecutor.executeAsyncWithRetry(method, innerType, context,
				cacheCoordinator.wrapWithCacheAsync(request, context, request::asStringAsync))
				.thenApply(response -> responseDecoder.decodeOrThrow(response, errorType, innerType));
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
	 * for a compile-time-generated call (see {@link #createGeneratedRequest}) -
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

	private void applyIdempotencyKeyIfNeeded(HttpRequest<?> request, Method method) {
		Retry retry = method.getAnnotation(Retry.class);
		applyIdempotencyKeyIfNeeded(request, retry != null && retry.idempotent());
	}

	/**
	 * Sends a freshly generated {@code Idempotency-Key} header when
	 * {@code idempotent} is true - called once per call, before any
	 * attempt is made, so every retry of that call (which re-sends the
	 * same {@code request} object) carries the identical value. Also used
	 * directly by compile-time-generated code for a
	 * {@code @Retry(idempotent = true)} method.
	 *
	 * @param request    the request to add the header to
	 * @param idempotent {@code @Retry}'s {@code idempotent}
	 */
	public void applyIdempotencyKeyIfNeeded(HttpRequest<?> request, boolean idempotent) {
		if (idempotent) {
			request.headerReplace("Idempotency-Key", UUID.randomUUID().toString());
		}
	}

	private static void applyHeaderEntries(HttpRequest<?> request, String[] entries) {
		for (String entry : entries) {
			int colon = entry.indexOf(':');
			String name = entry.substring(0, colon).trim();
			String value = entry.substring(colon + 1).trim();
			request.header(name, value);
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
		multipartEncoder.applyUploadMonitor(multipartBody, listener);
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
		return multipartEncoder.beginMultipart(request);
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
			multipartBody = multipartEncoder.beginMultipart(request);
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
				Object value = resolveValue(argValue, part.required(), RIPConstants.DEFAULT, part.value());
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
				Object value = resolveValue(argValue, field.required(), RIPConstants.DEFAULT, field.value());
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

	/**
	 * Also used directly by compile-time-generated code for a {@code @PartMap} parameter.
	 *
	 * @param multipartBody the multipart body to add parts to
	 * @param partMap       the {@code @PartMap} parameter's argument value; a
	 *                      {@code null}-valued entry is skipped
	 */
	public void applyPartMap(MultipartBody multipartBody, Map<?, ?> partMap) {
		multipartEncoder.applyPartMap(multipartBody, partMap);
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
	 *                      {@code PartValue} wrapping one of those with its own
	 *                      file name
	 */
	public void applyPartValue(MultipartBody multipartBody, String name, String fileName, Object value) {
		multipartEncoder.applyPartValue(multipartBody, name, fileName, value);
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
		formEncoder.appendFormFieldMap(formFields, fieldMap);
	}

	/**
	 * Appends one {@code @Field}/{@code @FieldMap} entry to a
	 * {@code @FormUrlEncoded} method's accumulated body. Also used directly
	 * by compile-time-generated code for a {@code @Field} parameter.
	 *
	 * @param formFields the accumulator to append encoded {@code name=value}
	 *                   pairs to
	 * @param name       the field name
	 * @param value      the field's value; a {@code Collection} is repeated
	 *                   once per non-{@code null} element, any other value
	 *                   once via {@code String.valueOf(...)}
	 */
	public void appendFormField(List<String> formFields, String name, Object value) {
		formEncoder.appendFormField(formFields, name, value);
	}

	/**
	 * Finalizes a {@code @FormUrlEncoded} method's accumulated
	 * {@code name=value} pairs into the request's body - the generated-code
	 * counterpart of the reflective path's own finalization in
	 * {@link #applyParams}.
	 *
	 * @param request    the request to apply the encoded body to
	 * @param formFields the accumulated encoded {@code name=value} pairs
	 * @return {@code request}, with the encoded body applied
	 */
	public HttpRequest<?> applyFormUrlEncodedBody(HttpRequest<?> request, List<String> formFields) {
		return formEncoder.applyFormUrlEncodedBody(request, formFields);
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

}
