package com.shri.restinpeace.cache;

import java.util.List;
import java.util.Map;

/**
 * A stored {@code GET} response, as handed to and returned from a
 * {@link Cache} implementation. Immutable - a revalidated or newly-stored
 * entry is always a new instance, never a mutation of an existing one.
 */
public final class CachedResponse {

	private final int status;
	private final Map<String, List<String>> headers;
	private final String body;
	private final long freshUntilEpochMillis;

	/**
	 * Creates a cached entry.
	 *
	 * @param status                the original response's HTTP status code
	 * @param headers               the original response's headers, keyed
	 *                              case-insensitively
	 * @param body                  the original response body
	 * @param freshUntilEpochMillis the epoch millisecond after which this
	 *                              entry is stale and must be revalidated (or
	 *                              re-fetched) before being served again -
	 *                              already in the past for an entry that's
	 *                              only cached for revalidation (e.g.
	 *                              {@code Cache-Control: no-cache}, or a
	 *                              response with an {@code ETag}/
	 *                              {@code Last-Modified} but no {@code max-age})
	 */
	public CachedResponse(int status, Map<String, List<String>> headers, String body, long freshUntilEpochMillis) {
		this.status = status;
		this.headers = headers;
		this.body = body;
		this.freshUntilEpochMillis = freshUntilEpochMillis;
	}

	/**
	 * Returns the original response's HTTP status code.
	 *
	 * @return the status code
	 */
	public int getStatus() {
		return status;
	}

	/**
	 * Returns the original response's headers, keyed case-insensitively.
	 *
	 * @return the headers
	 */
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	/**
	 * Returns the first value of a response header, matched case-insensitively.
	 *
	 * @param name the header name
	 * @return the header's first value, or {@code null} if it wasn't sent
	 */
	public String getHeader(String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Returns the original response body.
	 *
	 * @return the response body
	 */
	public String getBody() {
		return body;
	}

	/**
	 * Returns the epoch millisecond after which this entry is stale.
	 *
	 * @return the freshness deadline, in epoch milliseconds
	 */
	public long getFreshUntilEpochMillis() {
		return freshUntilEpochMillis;
	}

	/**
	 * Whether this entry can still be served without revalidating against
	 * the origin server.
	 *
	 * @return {@code true} if this entry is still fresh
	 */
	public boolean isFresh() {
		return System.currentTimeMillis() < freshUntilEpochMillis;
	}

}
