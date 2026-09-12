package com.shri.restinpeace.internal;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLEncoder;

import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.Url;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * Resolves a {@code @RestClient} method's request URL from its {@code @GET}/
 * {@code @POST}/etc. template (or a {@code @Url} parameter, which bypasses
 * everything else), applying {@code @BaseUrl}/a runtime base URL override and
 * substituting {@code @PathParam}s. Extracted out of {@link RequestExecutor}
 * since URL resolution is a genuinely separate concern - it holds
 * {@code baseUrlOverride} since that's the one piece of per-client state this
 * cluster needs.
 */
final class UrlResolver {

	private final String baseUrlOverride;

	UrlResolver(String baseUrlOverride) {
		this.baseUrlOverride = baseUrlOverride;
	}

	/**
	 * Resolves the method's request URL: a {@code @Url} parameter's runtime
	 * value verbatim if present, bypassing {@code @BaseUrl}/a runtime base
	 * URL/{@code @PathParam} entirely since there's no template for them to
	 * apply to - otherwise the usual template resolution.
	 */
	String resolveUrl(Method method, HTTPMethod httpMethod, Object[] args) {
		String urlParamValue = resolveUrlParam(method, args);
		if (urlParamValue != null) {
			return urlParamValue;
		}
		return resolvePathParams(applyBaseUrl(method, getUrlTemplate(method, httpMethod)), method, args);
	}

	/**
	 * Resolves a generated method's request URL from its (possibly relative)
	 * URL template, substituting {@code @PathParam}s - the generated-code
	 * counterpart of {@link #resolveUrl}, used when the method has no
	 * {@code @Url} parameter (which bypasses this entirely; see
	 * {@link RequestExecutor#requireUrlParam}).
	 *
	 * @param urlTemplate      the method's declared URL, possibly relative and/or
	 *                         containing {@code {name}} path param placeholders
	 * @param interfaceBaseUrl the interface's {@code @BaseUrl}, or {@code null}
	 *                         if it has none
	 * @param pathParamNames   the names of every {@code @PathParam} on the method
	 * @param pathParamValues  the corresponding argument values, in the same order
	 * @return the fully-resolved request URL
	 */
	String resolveGeneratedUrl(String urlTemplate, String interfaceBaseUrl, String[] pathParamNames,
			Object[] pathParamValues) {
		return substitutePathParamsLiteral(applyBaseUrlLiteral(urlTemplate, interfaceBaseUrl), pathParamNames,
				pathParamValues);
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

	private String applyBaseUrlLiteral(String url, String interfaceBaseUrl) {
		if (isAbsoluteUrl(url)) {
			return url;
		}
		String base = baseUrlOverride != null ? baseUrlOverride : interfaceBaseUrl;
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
