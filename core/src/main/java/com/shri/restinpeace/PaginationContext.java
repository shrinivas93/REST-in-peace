package com.shri.restinpeace;

import java.util.List;

import com.google.gson.JsonElement;

/**
 * The single page's items and metadata a {@link PaginationStrategy} is
 * consulted with after every fetch - see
 * {@code docs/design/pagination-helper.md} §6.8.
 *
 * @param <T> the decoded item type
 */
public interface PaginationContext<T> {

	/**
	 * Returns this page's decoded items, in the order the server returned
	 * them.
	 *
	 * @return this page's decoded items
	 */
	List<T> items();

	/**
	 * Returns this page's parsed response body, for arbitrary field access
	 * the closed {@code @Paginated} attribute vocabulary can't reach (a
	 * cursor needing decoding before reuse, combined termination logic
	 * across multiple signals, and the other cases catalogued in §10).
	 *
	 * @return the parsed response body tree
	 */
	JsonElement rawBody();

	/**
	 * Returns the named response header's value, or {@code null} if the
	 * response didn't carry it.
	 *
	 * @param name the header name
	 * @return the header's value, or {@code null} if absent
	 */
	String header(String name);

	/**
	 * Returns how many pages have been fetched so far, including this one.
	 *
	 * @return the number of pages fetched so far, including this one
	 */
	int pagesFetchedSoFar();

	/**
	 * Returns how many items have been fetched so far, including this
	 * page's.
	 *
	 * @return the number of items fetched so far, including this page's
	 */
	int itemsFetchedSoFar();

}
