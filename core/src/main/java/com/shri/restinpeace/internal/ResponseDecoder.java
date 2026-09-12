package com.shri.restinpeace.internal;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.error.ErrorType;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

import kong.unirest.HttpResponse;
import kong.unirest.ObjectMapper;
import kong.unirest.Unirest;
import kong.unirest.UnirestConfigException;
import kong.unirest.UnirestInstance;

/**
 * Turns a settled {@link HttpResponse} into a method's actual return value -
 * a success body decoded into the declared return type, or a non-2xx status
 * turned into a thrown {@link RestInPeaceHttpException}. Extracted out of
 * {@link RequestExecutor} since response decoding is a genuinely separate
 * concern; holds {@code unirestInstance} since looking up the right
 * {@link ObjectMapper} to deserialize with is the one piece of per-client
 * state this cluster needs.
 */
final class ResponseDecoder {

	private final UnirestInstance unirestInstance;

	ResponseDecoder(UnirestInstance unirestInstance) {
		this.unirestInstance = unirestInstance;
	}

	/**
	 * Decodes a settled response, throwing {@link RestInPeaceHttpException}
	 * for a non-2xx status instead of returning a value.
	 */
	Object decodeOrThrow(HttpResponse<?> response, Class<?> errorType, Class<?> returnType) {
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
	Object decodeBody(HttpResponse<?> response, Class<?> errorType, Class<?> returnType) {
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

	/** Package-private so {@link CacheCoordinator} can share this instead of duplicating it. */
	static boolean isSuccessStatus(int status) {
		return status >= 200 && status < 300;
	}

	/** Package-private so {@link RetryExecutor} can share this instead of duplicating it. */
	static Class<?> errorTypeOf(Method method) {
		if (method == null) {
			return null;
		}
		ErrorType errorType = method.getAnnotation(ErrorType.class);
		return errorType == null ? null : errorType.value();
	}

	static Object wrapResponse(HttpResponse<?> response, Object decodedBody) {
		return new RipResponse<>(response.getStatus(), toHeaderMap(response.getHeaders()), decodedBody);
	}

	/** Package-private so {@link CacheCoordinator} can share this instead of duplicating it. */
	static Map<String, List<String>> toHeaderMap(kong.unirest.Headers headers) {
		Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		headers.all().forEach(header -> result.computeIfAbsent(header.getName(), key -> new ArrayList<>())
				.add(header.getValue()));
		result.replaceAll((name, values) -> Collections.unmodifiableList(values));
		return Collections.unmodifiableMap(result);
	}

}
