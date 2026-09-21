package com.shri.restinpeace.annotation.pagination;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a parameter - already annotated {@code @QueryParam}/{@code @PathParam}/
 * {@code @HeaderParam}/{@code @Body} - as the carrier a {@code @Paginated}
 * fetch re-populates with the freshly extracted next-page pointer before
 * every subsequent call. On the first call, the consumer's own argument is
 * used verbatim. See {@code docs/design/pagination-helper.md} §6.3 - reuses
 * RIP's existing carrier vocabulary instead of inventing four new parameter
 * annotations.
 *
 * <pre>
 * {@literal @}GET("/orders")
 * {@literal @}Paginated(itemsField = "orders", pointerField = "next_cursor")
 * Page{@literal <}Order{@literal >} listOrders({@literal @}QueryParam("cursor") {@literal @}PaginationCursor String cursor);
 * </pre>
 *
 * <p>
 * As of this chunk, only stacking on {@code @QueryParam}/{@code @PathParam}/
 * {@code @HeaderParam} is supported, with a {@code String}, {@code int}, or
 * {@code long} parameter type. Stacking on {@code @Body} (and this
 * annotation's own {@link #bodyField()}) lands in a later rollout chunk
 * (§12) - {@code RIP.getClient(...)} rejects it for now.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaginationCursor {

	/**
	 * Dotted path inside a {@code @Body Map<String,Object>} to set the
	 * extracted pointer value into - only meaningful when stacked on
	 * {@code @Body}, not yet implemented.
	 *
	 * @return the dotted path to set within the request body map
	 */
	String bodyField() default "";

}
