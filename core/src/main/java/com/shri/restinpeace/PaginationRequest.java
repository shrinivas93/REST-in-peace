package com.shri.restinpeace;

import com.shri.restinpeace.internal.PaginationRequestImpl;

/**
 * What a {@link PaginationStrategy}'s next request should look like - built
 * exclusively via the static factory methods below (this interface isn't
 * meant to be implemented directly), and combinable via {@link #and} when
 * more than one override applies at once (a composite keyset needing two
 * request-body fields set, for instance). See
 * {@code docs/design/pagination-helper.md} §6.8 and its exhaustive §9
 * catalogue for real-world examples of each factory method in use.
 */
public interface PaginationRequest {

	/**
	 * Follows the given URL verbatim for the next page, bypassing the
	 * method's own URL template entirely - the {@code PaginationStrategy}
	 * counterpart of {@code pointerKind = FULL_URL}.
	 *
	 * @param url the next page's absolute URL
	 * @return a request that follows {@code url} verbatim
	 */
	static PaginationRequest toUrl(String url) {
		return PaginationRequestImpl.toUrl(url);
	}

	/**
	 * Sets (or overrides) a query parameter on the next request.
	 *
	 * @param name  the query parameter's name
	 * @param value the query parameter's value
	 * @return a request with {@code name} set to {@code value}
	 */
	static PaginationRequest withQueryParam(String name, Object value) {
		return PaginationRequestImpl.withQueryParam(name, value);
	}

	/**
	 * Sets (or overrides) a {@code {name}} URL path placeholder on the next
	 * request.
	 *
	 * @param name  the path parameter's name, matching a {@code {name}}
	 *              placeholder in the method's URL template
	 * @param value the path parameter's value
	 * @return a request with {@code name} set to {@code value}
	 */
	static PaginationRequest withPathParam(String name, Object value) {
		return PaginationRequestImpl.withPathParam(name, value);
	}

	/**
	 * Sets (or overrides) a request header on the next request.
	 *
	 * @param name  the header's name
	 * @param value the header's value
	 * @return a request with header {@code name} set to {@code value}
	 */
	static PaginationRequest withHeader(String name, Object value) {
		return PaginationRequestImpl.withHeader(name, value);
	}

	/**
	 * Sets (or overrides) a dotted-path field within the next request's JSON
	 * body - the method must declare a {@code @Body Map<String,Object>}
	 * parameter for this to apply to, the same carrier shape §6.7 uses for
	 * the declarative {@code @Paginated} path.
	 *
	 * @param dottedPath the JSON path inside the body to set
	 * @param value      the value to set it to
	 * @return a request with {@code dottedPath} set to {@code value}
	 */
	static PaginationRequest withBodyField(String dottedPath, Object value) {
		return PaginationRequestImpl.withBodyField(dottedPath, value);
	}

	/**
	 * Combines this request's overrides with {@code other}'s - for a
	 * composite key needing more than one override applied to the same next
	 * request (e.g. two {@link #withBodyField} calls for a two-column
	 * keyset).
	 *
	 * @param other the other overrides to combine with this one's
	 * @return a request carrying both this one's and {@code other}'s overrides
	 */
	PaginationRequest and(PaginationRequest other);

}
