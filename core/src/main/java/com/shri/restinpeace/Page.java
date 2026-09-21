package com.shri.restinpeace;

import java.util.List;

/**
 * One page of a {@code @Paginated} method's results, for manual,
 * page-at-a-time iteration - see {@code docs/design/pagination-helper.md}
 * §6.4. {@code Stream<T>}/{@code Iterator<T>} auto-flattened iteration
 * across every page is planned for a later rollout chunk (§12).
 *
 * <p>
 * {@link #rawResponse()} returns a {@link RipResponse}{@code <Void>} rather
 * than a {@code kong.unirest.HttpResponse<?>} - RIP's own status/headers
 * vocabulary, consistent with every other feature's public API, instead of
 * leaking the underlying HTTP client's type. Its body is always {@code null}
 * since {@link #items()} already carries this page's decoded content.
 *
 * @param <T> the decoded item type
 */
public interface Page<T> {

	/**
	 * Returns this page's decoded items, in the order the server returned them.
	 *
	 * @return this page's decoded items
	 */
	List<T> items();

	/**
	 * Returns whether {@link #next()} would fetch another page - per the
	 * termination precedence in {@code docs/design/pagination-helper.md} §6.5
	 * (a {@code hasMore} signal, then a total-record/total-page comparison,
	 * then pointer presence, always layered under an empty-page safety net).
	 *
	 * @return whether there is a next page to fetch
	 */
	boolean hasNext();

	/**
	 * Blocking-fetches the next page by re-invoking the exact same annotated
	 * method through the client's entire existing call pipeline - retry,
	 * cache, circuit breaker, bulkhead, and every registered interceptor -
	 * exactly as if the method were called again by hand (§6.9).
	 *
	 * @return the next page
	 * @throws com.shri.restinpeace.exception.RestInPeaceException if there is no next page
	 */
	Page<T> next();

	/**
	 * Returns the status code and headers of the call that produced this
	 * page - correct for whichever page is currently held, unlike a
	 * {@code RipResponse<Page<T>>} would be after {@link #next()} advances
	 * (see §6.4.1).
	 *
	 * @return this page's own response metadata, with a {@code null} body
	 */
	RipResponse<Void> rawResponse();

}
