package com.shri.restinpeace.annotation.pagination;

/**
 * How the client advances to the next page on its own, when
 * {@code pointerSource = NONE} (no server-given pointer at all) - offset or
 * page-number arithmetic instead of following an extracted value. See
 * {@code docs/design/pagination-helper.md} §6.1 and §6.5 (rollout chunk 7,
 * §12).
 */
public enum PaginationAdvance {

	/** No client-driven advancement - the default, used whenever {@code pointerSource != NONE}. */
	NONE,

	/** Advance the offset by {@code pageSize} each fetch. */
	INCREMENT_BY_PAGE_SIZE,

	/** Advance the page number by one each fetch. */
	INCREMENT_BY_ONE

}
