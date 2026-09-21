package com.shri.restinpeace.annotation.pagination;

/**
 * Whether a {@code @Paginated} method's extracted next-page pointer is a
 * complete URL to follow verbatim, or a bare value to re-inject into the
 * next request via a {@code @PaginationCursor} carrier. See
 * {@code docs/design/pagination-helper.md} §6.1.
 */
public enum PointerKind {

	/**
	 * The extracted pointer is a complete next-page URL, followed the same
	 * way an {@code @Url} parameter's value would be - no
	 * {@code @PaginationCursor} parameter is used (there's nothing to
	 * inject into).
	 */
	FULL_URL,

	/**
	 * The extracted pointer is a bare cursor/token/page-number value, resent
	 * via whichever parameter carries {@code @PaginationCursor}.
	 */
	VALUE

}
