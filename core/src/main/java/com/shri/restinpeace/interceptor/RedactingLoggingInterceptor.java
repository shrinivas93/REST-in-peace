package com.shri.restinpeace.interceptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link LoggingInterceptor}-style pre-built interceptor that also logs the
 * request and response bodies, masking a configured set of field names
 * (case-insensitively) instead of printing them verbatim - so logging bodies
 * is safe by default instead of a footgun. Sends to {@code System.out::println}
 * by default; pass a {@link Consumer} to route lines to a real logger
 * instead, without pulling a logging framework into this library's own
 * dependencies.
 *
 * <p>
 * Masking is a regex match over {@code "fieldName": value}-shaped text (a
 * quoted key, a colon, then a quoted string or a bare token up to the next
 * {@code ,}/{@code }}/{@code ]}/whitespace) - a best-effort scan for
 * JSON-shaped bodies, not a real JSON parser, so a sensitive field whose
 * value is itself a nested object or array is only masked up to that value's
 * first {@code ,}/{@code }}/{@code ]}. The request body comes from
 * {@link RequestContext#getBody()} (a {@code String}/POJO {@code @Body}
 * only - {@code null} for {@code @FormUrlEncoded}/{@code @Multipart}, same
 * limitation as that method); the response body is whatever
 * {@link #afterResponse} receives, converted with {@code String.valueOf(...)}
 * first - reliable for a {@code String} response, best-effort for a decoded
 * POJO (masking then depends on its own {@code toString()} happening to
 * render {@code "fieldName": value} pairs).
 */
public class RedactingLoggingInterceptor implements RequestInterceptor {

	/**
	 * The field names masked by default: {@code password}, {@code token},
	 * {@code secret}, {@code apiKey}, {@code ssn}, and {@code authorization}.
	 */
	public static final Set<String> DEFAULT_SENSITIVE_FIELD_NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(
			Arrays.asList("password", "token", "secret", "apiKey", "ssn", "authorization")));

	private static final String START_TIME_ATTRIBUTE = "com.shri.restinpeace.interceptor.RedactingLoggingInterceptor.startTime";
	private static final String MASK = "***";

	private final Consumer<String> sink;
	private final Pattern sensitiveFieldPattern;

	/** Logs to {@code System.out}, masking {@link #DEFAULT_SENSITIVE_FIELD_NAMES}. */
	public RedactingLoggingInterceptor() {
		this(DEFAULT_SENSITIVE_FIELD_NAMES);
	}

	/**
	 * Logs to {@code System.out}, masking a custom set of field names instead
	 * of {@link #DEFAULT_SENSITIVE_FIELD_NAMES}.
	 *
	 * @param sensitiveFieldNames the field names to mask, matched
	 *                            case-insensitively; replaces the default set
	 *                            entirely rather than adding to it
	 */
	public RedactingLoggingInterceptor(Set<String> sensitiveFieldNames) {
		this(sensitiveFieldNames, System.out::println);
	}

	/**
	 * Logs to a custom sink instead of {@code System.out}, masking a custom
	 * set of field names.
	 *
	 * @param sensitiveFieldNames the field names to mask, matched
	 *                            case-insensitively; replaces the default set
	 *                            entirely rather than adding to it
	 * @param sink                receives each log line, e.g. a logger method
	 *                            reference
	 */
	public RedactingLoggingInterceptor(Set<String> sensitiveFieldNames, Consumer<String> sink) {
		this.sink = sink;
		this.sensitiveFieldPattern = buildPattern(sensitiveFieldNames);
	}

	private static Pattern buildPattern(Set<String> fieldNames) {
		StringBuilder alternation = new StringBuilder();
		for (String fieldName : fieldNames) {
			if (alternation.length() > 0) {
				alternation.append('|');
			}
			alternation.append(Pattern.quote(fieldName));
		}
		return Pattern.compile("\"(" + alternation + ")\"\\s*:\\s*(\"[^\"]*\"|[^,}\\]\\s]+)",
				Pattern.CASE_INSENSITIVE);
	}

	private String redact(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = sensitiveFieldPattern.matcher(text);
		StringBuffer redacted = new StringBuffer();
		while (matcher.find()) {
			matcher.appendReplacement(redacted,
					Matcher.quoteReplacement("\"" + matcher.group(1) + "\": \"" + MASK + "\""));
		}
		matcher.appendTail(redacted);
		return redacted.toString();
	}

	@Override
	public void beforeRequest(RequestContext context) {
		context.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
		String body = redact(context.getBody());
		sink.accept(
				String.format("--> %s %s%s", context.getHttpMethod(), context.getUrl(), body != null ? " " + body : ""));
	}

	@Override
	public void afterResponse(RequestContext context, int status, Object body) {
		Object startTime = context.getAttribute(START_TIME_ATTRIBUTE);
		String duration = startTime instanceof Long ? (System.currentTimeMillis() - (Long) startTime) + "ms" : "?";
		String redactedBody = body == null ? null : redact(String.valueOf(body));
		sink.accept(String.format("<-- %s %s %d (%s)%s", context.getHttpMethod(), context.getUrl(), status, duration,
				redactedBody != null ? " " + redactedBody : ""));
	}

}
