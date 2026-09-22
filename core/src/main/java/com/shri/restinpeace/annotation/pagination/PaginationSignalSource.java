package com.shri.restinpeace.annotation.pagination;

/**
 * Where one particular {@code @Paginated} signal comes from - reused across
 * {@code pointerSource}, {@code hasMoreSource}, {@code totalSource}, and
 * {@code totalPagesSource} (one enum, four uses, not four renamed
 * near-duplicates) per {@code docs/design/pagination-helper.md} §6.1.
 *
 * <p>
 * {@link #ITEM_FIELD} is only meaningful for {@code pointerSource} - keyset
 * pagination derives the next pointer from the last fetched item itself
 * (§6.6), a concept that doesn't apply to a {@code hasMore}/total-count
 * signal - {@code RIP.getClient(...)} rejects it for any other attribute.
 */
public enum PaginationSignalSource {

	/** Read from the (possibly nested) response body. */
	RESPONSE_BODY,

	/** Read from a response header. */
	RESPONSE_HEADER,

	/** Derived from the last fetched item, for {@code pointerSource} only. */
	ITEM_FIELD,

	/** No such signal - this attribute isn't used. */
	NONE

}
