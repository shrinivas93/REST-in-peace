package com.shri.restinpeace.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * A single request {@link MockRestServer} actually received, for a test to
 * assert against after making a call through a {@code @RestClient}
 * interface.
 */
public final class RecordedRequest {

	private static final Pattern BOUNDARY_PATTERN = Pattern.compile("boundary=\"?([^\";]+)\"?");
	private static final Pattern NAME_PATTERN = Pattern.compile("name=\"([^\"]*)\"");
	private static final Pattern FILENAME_PATTERN = Pattern.compile("filename=\"([^\"]*)\"");

	private final HTTPMethod httpMethod;
	private final String path;
	private final Map<String, List<String>> queryParams;
	private final Map<String, List<String>> headers;
	private final byte[] rawBody;
	private final Instant receivedAt;

	private RecordedRequest(HTTPMethod httpMethod, String path, Map<String, List<String>> queryParams,
			Map<String, List<String>> headers, byte[] rawBody, Instant receivedAt) {
		this.httpMethod = httpMethod;
		this.path = path;
		this.queryParams = queryParams;
		this.headers = headers;
		this.rawBody = rawBody;
		this.receivedAt = receivedAt;
	}

	static RecordedRequest capture(HttpExchange exchange) throws IOException {
		HTTPMethod httpMethod = HTTPMethod.valueOf(exchange.getRequestMethod().toUpperCase(Locale.ROOT));
		String path = exchange.getRequestURI().getPath();
		Map<String, List<String>> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		headers.putAll(exchange.getRequestHeaders());
		byte[] rawBody = readBody(exchange.getRequestBody());
		return new RecordedRequest(httpMethod, path, queryParams, headers, rawBody, Instant.now());
	}

	/**
	 * Returns the HTTP method of the request.
	 *
	 * @return the HTTP method
	 */
	public HTTPMethod getHttpMethod() {
		return httpMethod;
	}

	/**
	 * Returns the request path, without the query string.
	 *
	 * @return the path
	 */
	public String getPath() {
		return path;
	}

	/**
	 * Returns when this request was fully received - the way to verify
	 * timing-sensitive behavior like {@code @Retry}'s {@code delayMillis}/
	 * {@code backoffMultiplier} actually growing the wait between attempts,
	 * rather than only counting how many attempts were made.
	 *
	 * @return when this request was received
	 */
	public Instant getReceivedAt() {
		return receivedAt;
	}

	/**
	 * Returns every value of a query param, matched case-sensitively (query
	 * param names, unlike headers, are case-sensitive per the URL spec).
	 *
	 * @param name the query param name
	 * @return the query param's values, or an empty list if it wasn't sent
	 */
	public List<String> getQueryParams(String name) {
		return queryParams.getOrDefault(name, java.util.Collections.emptyList());
	}

	/**
	 * Returns the first value of a query param, since most are only ever sent
	 * once.
	 *
	 * @param name the query param name
	 * @return the query param's first value, or {@code null} if it wasn't sent
	 */
	public String getQueryParam(String name) {
		List<String> values = getQueryParams(name);
		return values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Returns the request headers, keyed by name (case-insensitively).
	 *
	 * @return the headers
	 */
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	/**
	 * Returns the first value of a request header, matched case-insensitively.
	 *
	 * @param name the header name
	 * @return the header's first value, or {@code null} if it wasn't sent
	 */
	public String getHeader(String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Returns the raw request body, decoded as UTF-8 - for a binary body (a
	 * {@code @Multipart} request in particular, whose parts may not be text
	 * at all), decoding as a single UTF-8 string is lossy; use
	 * {@link #getRawBody()} or {@link #getParts()} instead.
	 *
	 * @return the request body, or an empty string if none was sent
	 */
	public String getBody() {
		return new String(rawBody, StandardCharsets.UTF_8);
	}

	/**
	 * Returns the raw request body bytes, exactly as received - unlike
	 * {@link #getBody()}, safe for a binary body.
	 *
	 * @return the raw request body bytes, or an empty array if none was sent
	 */
	public byte[] getRawBody() {
		return rawBody.clone();
	}

	/**
	 * Decodes a {@code multipart/form-data} body into its individual parts,
	 * for asserting on what a {@code @Multipart} method actually sent - each
	 * {@code @Part}/{@code @PartMap} entry becomes one {@link Part}, with its
	 * name, optional file name, optional content type, and content.
	 *
	 * @return the decoded parts, in the order they were sent
	 * @throws IllegalStateException if this request's {@code Content-Type}
	 *                                isn't {@code multipart/*}, or has no
	 *                                {@code boundary}
	 */
	public List<Part> getParts() {
		String contentType = getHeader("Content-Type");
		if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
			throw new IllegalStateException(
					"getParts() requires a multipart Content-Type, but this request's was: " + contentType);
		}
		Matcher boundaryMatcher = BOUNDARY_PATTERN.matcher(contentType);
		if (!boundaryMatcher.find()) {
			throw new IllegalStateException("No boundary found in Content-Type: " + contentType);
		}
		byte[] delimiter = ("--" + boundaryMatcher.group(1)).getBytes(StandardCharsets.US_ASCII);

		List<Part> parts = new ArrayList<>();
		int boundaryIndex = indexOf(rawBody, delimiter, 0);
		while (boundaryIndex >= 0) {
			int afterDelimiter = boundaryIndex + delimiter.length;
			if (isTerminalBoundary(afterDelimiter)) {
				break;
			}
			int partStart = afterDelimiter + 2; // skip the CRLF ending the boundary line
			int nextBoundaryIndex = indexOf(rawBody, delimiter, partStart);
			if (nextBoundaryIndex < 0) {
				break;
			}
			int partEnd = nextBoundaryIndex - 2; // exclude the CRLF preceding the next boundary
			parts.add(parsePart(Arrays.copyOfRange(rawBody, partStart, partEnd)));
			boundaryIndex = nextBoundaryIndex;
		}
		return parts;
	}

	/**
	 * Decodes an {@code application/x-www-form-urlencoded} body into its
	 * field name/value pairs, for asserting on what a
	 * {@code @FormUrlEncoded} method actually sent - a repeated key (from a
	 * {@code @FieldMap} entry, or a {@code Collection}-valued {@code @Field})
	 * collects every value, in the order sent.
	 *
	 * @return the decoded form fields, keyed by name in the order first seen
	 * @throws IllegalStateException if this request's {@code Content-Type}
	 *                                isn't {@code application/x-www-form-urlencoded}
	 */
	public Map<String, List<String>> getFormFields() {
		String contentType = getHeader("Content-Type");
		if (contentType == null
				|| !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
			throw new IllegalStateException(
					"getFormFields() requires an application/x-www-form-urlencoded Content-Type, but this request's was: "
							+ contentType);
		}
		return parseQuery(getBody());
	}

	private boolean isTerminalBoundary(int afterDelimiterIndex) {
		return afterDelimiterIndex + 1 < rawBody.length && rawBody[afterDelimiterIndex] == '-'
				&& rawBody[afterDelimiterIndex + 1] == '-';
	}

	private static Part parsePart(byte[] chunk) {
		byte[] headerBodySeparator = { '\r', '\n', '\r', '\n' };
		int separatorIndex = indexOf(chunk, headerBodySeparator, 0);
		String headerText = new String(chunk, 0, separatorIndex, StandardCharsets.US_ASCII);
		byte[] content = Arrays.copyOfRange(chunk, separatorIndex + headerBodySeparator.length, chunk.length);

		String name = null;
		String fileName = null;
		String contentType = null;
		for (String line : headerText.split("\r\n")) {
			String lowerCaseLine = line.toLowerCase(Locale.ROOT);
			if (lowerCaseLine.startsWith("content-disposition:")) {
				Matcher nameMatcher = NAME_PATTERN.matcher(line);
				if (nameMatcher.find()) {
					name = nameMatcher.group(1);
				}
				Matcher fileNameMatcher = FILENAME_PATTERN.matcher(line);
				if (fileNameMatcher.find()) {
					fileName = fileNameMatcher.group(1);
				}
			} else if (lowerCaseLine.startsWith("content-type:")) {
				contentType = line.substring(line.indexOf(':') + 1).trim();
			}
		}
		return new Part(name, fileName, contentType, content);
	}

	private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
		outer: for (int i = fromIndex; i <= data.length - pattern.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static Map<String, List<String>> parseQuery(String rawQuery) {
		Map<String, List<String>> params = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
			return params;
		}
		for (String pair : rawQuery.split("&")) {
			int equals = pair.indexOf('=');
			String name = equals < 0 ? pair : pair.substring(0, equals);
			String value = equals < 0 ? "" : pair.substring(equals + 1);
			params.computeIfAbsent(decode(name), key -> new ArrayList<>()).add(decode(value));
		}
		return params;
	}

	private static String decode(String value) {
		try {
			return java.net.URLDecoder.decode(value, "UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new AssertionError("UTF-8 is always supported.", e);
		}
	}

	private static byte[] readBody(InputStream inputStream) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[1024];
		int read;
		while ((read = inputStream.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	/**
	 * One part of a decoded {@code multipart/form-data} request body, as
	 * returned by {@link RecordedRequest#getParts()}.
	 */
	public static final class Part {

		private final String name;
		private final String fileName;
		private final String contentType;
		private final byte[] content;

		private Part(String name, String fileName, String contentType, byte[] content) {
			this.name = name;
			this.fileName = fileName;
			this.contentType = contentType;
			this.content = content;
		}

		/**
		 * Returns this part's name - the {@code @Part}'s {@code value}, or
		 * the entry's key for a {@code @PartMap} part.
		 *
		 * @return the part name
		 */
		public String getName() {
			return name;
		}

		/**
		 * Returns this part's file name, for a {@code File}/{@code byte[]}/
		 * {@code InputStream} part sent as a file.
		 *
		 * @return the file name, or {@code null} for a plain {@code String}
		 *         form field
		 */
		public String getFileName() {
			return fileName;
		}

		/**
		 * Returns this part's {@code Content-Type}, if one was sent.
		 *
		 * @return the content type, or {@code null} if none was sent
		 */
		public String getContentType() {
			return contentType;
		}

		/**
		 * Returns this part's raw content bytes.
		 *
		 * @return the content bytes
		 */
		public byte[] getContent() {
			return content.clone();
		}

		/**
		 * Returns this part's content decoded as UTF-8, for a text part (a
		 * plain {@code String} form field, or a text file).
		 *
		 * @return the content as a UTF-8 string
		 */
		public String getContentAsString() {
			return new String(content, StandardCharsets.UTF_8);
		}

	}

}
