package com.shri.restinpeace.annotation.pagination;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a method's response is paginated - following a next-page
 * pointer automatically, extracting each page's items into a
 * {@code Page<T>}, instead of leaving the fetch-extract-repeat loop to
 * hand-written code. See {@code docs/design/pagination-helper.md} for the
 * full design, every default, and the exhaustive catalogue of real-world
 * shapes this is built to cover (§9).
 *
 * <pre>
 * {@literal @}GET("/orders")
 * {@literal @}Paginated(itemsField = "orders", pointerField = "next")
 * Page{@literal <}Order{@literal >} listOrders({@literal @}QueryParam("cursor") {@literal @}PaginationCursor String cursor);
 * </pre>
 *
 * <p>
 * Landing incrementally per the design doc's rollout plan (§12) - chunks
 * 2-5 support {@link PointerKind#FULL_URL}/{@link PointerKind#VALUE}
 * pointers sourced from {@link PaginationSignalSource#RESPONSE_BODY}/
 * {@link PaginationSignalSource#RESPONSE_HEADER}/
 * {@link PaginationSignalSource#ITEM_FIELD} (keyset pagination, including
 * an N-way composite key), resent via {@code @QueryParam}/
 * {@code @PathParam}/{@code @HeaderParam}/{@code @Body}, with
 * {@code hasMoreSource}/{@code totalSource}/{@code totalPagesSource}
 * termination signals, and a synchronous {@code Page<T>}/{@code Stream<T>}/
 * {@code Iterator<T>} return type. {@link PaginationAdvance} client-driven
 * advancement, {@code PaginationStrategy<T>}, and an async first fetch are
 * not implemented yet - {@code RIP.getClient(...)} rejects a method using
 * one of those shapes, naming what's missing, rather than silently
 * misbehaving at call time.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Paginated {

	/**
	 * Dotted path to the items array within the response body; empty means
	 * the response body itself is the array (no wrapper object).
	 *
	 * @return the dotted path to the items array, or {@code ""} for the response body itself
	 */
	String itemsField() default "";

	/**
	 * Whether the extracted pointer is a complete URL or a bare value to
	 * resend via a {@code @PaginationCursor} carrier.
	 *
	 * @return the pointer's kind
	 */
	PointerKind pointerKind() default PointerKind.VALUE;

	/**
	 * Where the next-page pointer is read from.
	 *
	 * @return the pointer's source
	 */
	PaginationSignalSource pointerSource() default PaginationSignalSource.RESPONSE_BODY;

	/**
	 * Where the pointer is read from within that source: a dotted body path,
	 * a header name, or - for {@link PaginationSignalSource#ITEM_FIELD}
	 * keyset pagination - a comma-separated list of item field names for an
	 * N-way composite key.
	 *
	 * @return the pointer's field/header name, or comma-separated names for a composite keyset
	 */
	String pointerField() default "";

	/**
	 * How the client advances when there's no server-given pointer at all
	 * ({@code pointerSource = NONE}) - not yet implemented.
	 *
	 * @return the client-driven advancement style
	 */
	PaginationAdvance advance() default PaginationAdvance.NONE;

	/**
	 * The page size {@link PaginationAdvance#INCREMENT_BY_PAGE_SIZE} advances
	 * the offset by - not yet implemented.
	 *
	 * @return the page size, meaningless unless {@link #advance()} is {@code INCREMENT_BY_PAGE_SIZE}
	 */
	int pageSize() default 0;

	/**
	 * Where a "more pages exist" boolean signal is read from, if any -
	 * authoritative over pointer presence when set (§6.5).
	 *
	 * @return the {@code hasMore} signal's source, or {@code NONE} if the API gives no such signal
	 */
	PaginationSignalSource hasMoreSource() default PaginationSignalSource.NONE;

	/**
	 * Where the {@link #hasMoreSource()} boolean is read from within that source.
	 *
	 * @return the {@code hasMore} field's dotted path or header name
	 */
	String hasMoreField() default "";

	/**
	 * Where a total-record-count signal is read from, if any - compared
	 * against a running fetched-items counter for termination (§6.5).
	 *
	 * @return the total-record-count signal's source, or {@code NONE} if the API gives no such signal
	 */
	PaginationSignalSource totalSource() default PaginationSignalSource.NONE;

	/**
	 * Where the {@link #totalSource()} record count is read from within that source.
	 *
	 * @return the total-record-count field's dotted path or header name
	 */
	String totalField() default "";

	/**
	 * Where a total-page-count signal is read from, if any - compared
	 * against a running fetched-pages counter for termination (§6.5).
	 *
	 * @return the total-page-count signal's source, or {@code NONE} if the API gives no such signal
	 */
	PaginationSignalSource totalPagesSource() default PaginationSignalSource.NONE;

	/**
	 * Where the {@link #totalPagesSource()} page count is read from within that source.
	 *
	 * @return the total-page-count field's dotted path or header name
	 */
	String totalPagesField() default "";

}
