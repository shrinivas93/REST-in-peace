package com.shri.restinpeace.interceptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * Per-call state passed to a {@link RequestInterceptor}'s
 * {@code beforeRequest}/{@code afterResponse}, sharing one instance across
 * both so an interceptor can correlate state between them (e.g. a start
 * timestamp for measuring request duration, via {@link #setAttribute}).
 */
public final class RequestContext {

	private final HTTPMethod httpMethod;
	private final String url;
	private final Map<String, String> headers = new LinkedHashMap<>();
	private final Map<String, Object> attributes = new HashMap<>();
	private String body;

	/**
	 * Creates the context for a single call.
	 *
	 * @param httpMethod the HTTP method of the request being made
	 * @param url        the fully resolved URL of the request being made
	 */
	public RequestContext(HTTPMethod httpMethod, String url) {
		this.httpMethod = httpMethod;
		this.url = url;
	}

	/**
	 * Returns the HTTP method of the request being made.
	 *
	 * @return the HTTP method
	 */
	public HTTPMethod getHttpMethod() {
		return httpMethod;
	}

	/**
	 * Returns the fully resolved URL of the request being made.
	 *
	 * @return the URL
	 */
	public String getUrl() {
		return url;
	}

	/**
	 * Adds a header to be sent with the request. Only effective when called
	 * from {@code beforeRequest} - the request has already been sent by the
	 * time {@code afterResponse} runs.
	 *
	 * @param name  the header name
	 * @param value the header value
	 */
	public void addHeader(String name, String value) {
		headers.put(name, value);
	}

	/**
	 * Returns the headers added so far via {@link #addHeader(String, String)}.
	 *
	 * @return the headers
	 */
	public Map<String, String> getHeaders() {
		return headers;
	}

	/**
	 * Sets this call's request body - called internally by RIP itself,
	 * before {@code beforeRequest} runs, for a {@code @Body} value only
	 * ({@code @FormUrlEncoded}/{@code @Multipart} bodies aren't captured
	 * here - neither has one single serialized string representation the
	 * way a JSON/raw-string {@code @Body} does). A {@code String} value is
	 * used verbatim; a POJO value is the exact JSON RIP itself sends,
	 * produced by the same configured {@code ObjectMapper}. Calling this
	 * from an interceptor has no effect on the request actually sent - by
	 * the time {@code beforeRequest} runs, the body this call sends is
	 * already fixed - so treat {@link #getBody()} as read-only in practice,
	 * the same way {@link #getUrl()} already is.
	 *
	 * @param body this call's request body, or {@code null} for a method
	 *             with no {@code @Body} parameter (or one that received a
	 *             {@code null} argument)
	 */
	public void setBody(String body) {
		this.body = body;
	}

	/**
	 * Returns this call's request body - the exact bytes RIP itself is
	 * about to send for a {@code @Body} parameter (a raw {@code String}
	 * verbatim, a POJO serialized through the same configured
	 * {@code ObjectMapper}), for an interceptor that needs the literal
	 * outgoing body - e.g. a request-signing interceptor (AWS SigV4, an
	 * HMAC webhook signature, OAuth1) computing a signature over it before
	 * adding the result as a header via {@link #addHeader}.
	 *
	 * @return this call's request body, or {@code null} for a method with
	 *         no {@code @Body} parameter, one that received a {@code null}
	 *         argument, or one using {@code @FormUrlEncoded}/{@code @Multipart}
	 *         instead (neither is captured here)
	 */
	public String getBody() {
		return body;
	}

	/**
	 * Lets an interceptor stash arbitrary per-call state in
	 * {@code beforeRequest} and read it back in {@code afterResponse} (e.g. a
	 * start timestamp to compute request duration).
	 *
	 * @param key   the attribute key
	 * @param value the attribute value
	 */
	public void setAttribute(String key, Object value) {
		attributes.put(key, value);
	}

	/**
	 * Reads back an attribute previously stashed via {@link #setAttribute}.
	 *
	 * @param key the attribute key
	 * @return the attribute value, or {@code null} if none was set
	 */
	public Object getAttribute(String key) {
		return attributes.get(key);
	}

	/**
	 * Renders this call as a {@code curl} command reproducing it as closely
	 * as possible - the exact method, URL, headers, and body (if any) RIP is
	 * about to send - for pasting into a bug report or a terminal to
	 * reproduce a failure outside the JVM. Equivalent to
	 * {@code toCurlCommand(CurlVerbosity.NONE)}.
	 *
	 * <p>
	 * Most useful from {@code afterResponse} on an error status, or from a
	 * {@code catch} block for {@code RestInPeaceHttpException} - by then the
	 * body (if any) is already fixed, so the rendered command always matches
	 * what was actually sent.
	 *
	 * @return the equivalent {@code curl} command
	 */
	public String toCurlCommand() {
		return toCurlCommand(CurlVerbosity.NONE);
	}

	/**
	 * Same as {@link #toCurlCommand()}, with an extra {@code curl} flag for
	 * the requested verbosity level - useful when the plain reproduction
	 * doesn't explain a failure and the wire-level detail {@code curl}
	 * itself can print (request/response headers, or a full trace including
	 * bodies) is what's actually needed.
	 *
	 * @param verbosity how much extra diagnostic detail the rendered
	 *                  command asks {@code curl} to print - {@link
	 *                  CurlVerbosity#NONE} for none
	 * @return the equivalent {@code curl} command
	 */
	public String toCurlCommand(CurlVerbosity verbosity) {
		StringBuilder curl = new StringBuilder("curl -X ").append(httpMethod);
		if (verbosity.flag != null) {
			curl.append(' ').append(verbosity.flag);
		}
		curl.append(" '").append(url).append('\'');
		for (Map.Entry<String, String> header : headers.entrySet()) {
			curl.append(" -H '").append(header.getKey()).append(": ").append(header.getValue()).append('\'');
		}
		if (body != null) {
			curl.append(" -d '").append(body.replace("'", "'\\''")).append('\'');
		}
		return curl.toString();
	}

	/**
	 * How much extra diagnostic detail {@link #toCurlCommand(CurlVerbosity)}
	 * asks {@code curl} itself to print, via {@code curl}'s own flags - RIP
	 * doesn't invent its own verbosity scheme, it just picks which standard
	 * {@code curl} flag to include.
	 */
	public enum CurlVerbosity {

		/** No extra flag - just the method, URL, headers, and body. */
		NONE(null),

		/**
		 * {@code -v} - prints the request/response headers and connection
		 * info {@code curl} itself sees (to {@code curl}'s own stderr),
		 * without touching either body.
		 */
		VERBOSE("-v"),

		/**
		 * {@code --trace-ascii -} - the most detailed level: a full,
		 * human-readable trace of everything sent and received on the wire,
		 * headers and bodies both (to {@code curl}'s own stdout) - useful
		 * when {@link #VERBOSE}'s headers-only view isn't enough to explain
		 * a failure.
		 */
		TRACE("--trace-ascii -");

		private final String flag;

		CurlVerbosity(String flag) {
			this.flag = flag;
		}
	}

}
