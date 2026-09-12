package com.shri.restinpeace.internal;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestWithBody;

/**
 * Builds a {@code @FormUrlEncoded} method's {@code application/x-www-form-urlencoded}
 * body. Stateless - extracted out of {@link RequestExecutor} since form
 * encoding is a genuinely separate concern from everything else that class
 * does, not because it needed any state of its own.
 */
final class FormEncoder {

	/**
	 * Also used directly by compile-time-generated code for a {@code @FieldMap} parameter.
	 *
	 * @param formFields the accumulator to append encoded {@code name=value}
	 *                   pairs to
	 * @param fieldMap   the {@code @FieldMap} parameter's argument value; a
	 *                   {@code null}-valued entry is skipped
	 */
	void appendFormFieldMap(List<String> formFields, Map<?, ?> fieldMap) {
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
	 * the same convention {@code applyQueryValue} uses for {@code @QueryParam}.
	 * Also used directly by compile-time-generated code for a {@code @Field}
	 * parameter.
	 *
	 * @param formFields the accumulator to append encoded {@code name=value}
	 *                   pairs to
	 * @param name       the field name
	 * @param value      the field's value; a {@code Collection} is repeated
	 *                   once per non-{@code null} element, any other value
	 *                   once via {@code String.valueOf(...)}
	 */
	void appendFormField(List<String> formFields, String name, Object value) {
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
	 * {@code &} and sent as {@code application/x-www-form-urlencoded} -
	 * the generated-code counterpart of the reflective path's own
	 * finalization in {@code RequestExecutor.applyParams}. Unlike
	 * {@code @Multipart}, Unirest has no dedicated url-encoded body builder
	 * to accumulate into directly, so the encoded string is built here
	 * instead and applied as a plain body.
	 *
	 * @param request    the request to apply the encoded body to
	 * @param formFields the accumulated encoded {@code name=value} pairs
	 * @return {@code request}, with the encoded body applied
	 */
	HttpRequest<?> applyFormUrlEncodedBody(HttpRequest<?> request, List<String> formFields) {
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

}
