package com.shri.restinpeace.validator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.pagination.PaginationAdvance;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.pagination.PointerKind;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.request.Url;
import com.shri.restinpeace.exception.RestInPeaceValidationException;

/**
 * Validation rules for {@code @Paginated}/{@code @PaginationCursor}/
 * {@code Page<T>}/{@code Stream<T>}/{@code Iterator<T>} - the
 * chunk-2/3-supported subset of {@code docs/design/pagination-helper.md} §7
 * (see {@code ReflectiveRestClientValidator.validatePaginated}'s own
 * javadoc).
 */
class ReflectiveRestClientValidatorPaginationTest {

	private static final class Order {
	}

	@RestClient
	public interface ValidFullUrlPointer {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerKind = PointerKind.FULL_URL, pointerField = "next")
		Page<Order> listOrders();
	}

	@RestClient
	public interface ValidValueQueryPointer {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface ValidValuePathPointer {
		@GET("http://example.com/orders/{cursor}")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@PathParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface ValidValueHeaderPointer {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.RESPONSE_HEADER,
				pointerField = "X-Next-Cursor")
		Page<Order> listOrders(@HeaderParam("X-Cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface ValidHasMoreAndTotalSignals {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor",
				hasMoreSource = PaginationSignalSource.RESPONSE_BODY, hasMoreField = "has_more",
				totalSource = PaginationSignalSource.RESPONSE_HEADER, totalField = "X-Total-Count")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface ReturnsPageWithoutAnnotation {
		@GET("http://example.com/orders")
		Page<Order> listOrders();
	}

	@RestClient
	public interface AnnotatedButNotReturningPage {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next")
		String listOrders();
	}

	@SuppressWarnings("rawtypes")
	@RestClient
	public interface RawPageReturn {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerKind = PointerKind.FULL_URL, pointerField = "next")
		Page listOrders();
	}

	@RestClient
	public interface ValidStreamReturn {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Stream<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface ValidIteratorReturn {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Iterator<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface ReturnsStreamWithoutAnnotation {
		@GET("http://example.com/orders")
		Stream<Order> listOrders();
	}

	@SuppressWarnings("rawtypes")
	@RestClient
	public interface RawStreamReturn {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Stream listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface RipResponseWrappingStream {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		RipResponse<Stream<Order>> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface RipResponseWrappingIterator {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		RipResponse<Iterator<Order>> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface RipResponseWrappingNonStreamType {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		RipResponse<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@SuppressWarnings("rawtypes")
	@RestClient
	public interface RawRipResponseReturn {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		RipResponse listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface PaginatedWithUrl {
		@GET
		@Paginated(itemsField = "orders", pointerKind = PointerKind.FULL_URL, pointerField = "next")
		Page<Order> listOrders(@Url String url);
	}

	@RestClient
	public interface FullUrlWithCursorParam {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerKind = PointerKind.FULL_URL, pointerField = "next")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface ValuePointerWithNoCursorParam {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders();
	}

	@RestClient
	public interface ValuePointerWithTwoCursorParams {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@QueryParam("a") @PaginationCursor String a,
				@QueryParam("b") @PaginationCursor String b);
	}

	@RestClient
	public interface PointerSourceNone {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE)
		Page<Order> listOrders(@QueryParam("offset") @PaginationCursor int offset);
	}

	@RestClient
	public interface PointerSourceItemField {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD, pointerField = "id")
		Page<Order> listOrders(@QueryParam("since") @PaginationCursor String since);
	}

	@RestClient
	public interface AdvanceSet {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor", advance = PaginationAdvance.INCREMENT_BY_ONE)
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface CursorOnBody {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@Body @PaginationCursor(bodyField = "cursor") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface BareCursorParam {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@PaginationCursor String cursor);
	}

	@RestClient
	public interface CursorParamWrongType {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor double cursor);
	}

	@RestClient
	public interface HasMoreFieldMissing {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor",
				hasMoreSource = PaginationSignalSource.RESPONSE_BODY)
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface HasMoreSourceItemField {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor",
				hasMoreSource = PaginationSignalSource.ITEM_FIELD, hasMoreField = "done")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface CompositePointerField {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "id,createdAt")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@Test
	void validate_fullUrlPointer_passes() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(ValidFullUrlPointer.class));
	}

	@Test
	void validate_valueQueryPointer_passes() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(ValidValueQueryPointer.class));
	}

	@Test
	void validate_valuePathPointer_passes() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(ValidValuePathPointer.class));
	}

	@Test
	void validate_valueHeaderPointer_passes() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(ValidValueHeaderPointer.class));
	}

	@Test
	void validate_hasMoreAndTotalSignals_passes() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(ValidHasMoreAndTotalSignals.class));
	}

	@Test
	void validate_returnsPageWithoutPaginated_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(ReturnsPageWithoutAnnotation.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("returns Page<T> but is not annotated with @Paginated"));
	}

	@Test
	void validate_paginatedNotReturningPage_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(AnnotatedButNotReturningPage.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("is annotated with @Paginated but does not return Page<T>"));
	}

	@Test
	void validate_rawPageReturn_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RawPageReturn.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("returns a raw Page with no type parameter"));
	}

	@Test
	void validate_streamReturn_passes() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(ValidStreamReturn.class));
	}

	@Test
	void validate_iteratorReturn_passes() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(ValidIteratorReturn.class));
	}

	@Test
	void validate_returnsStreamWithoutPaginated_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(ReturnsStreamWithoutAnnotation.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("returns Stream<T> but is not annotated with @Paginated"));
	}

	@Test
	void validate_rawStreamReturn_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RawStreamReturn.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("returns a raw Stream with no type parameter"));
	}

	@Test
	void validate_ripResponseWrappingStream_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RipResponseWrappingStream.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("returns RipResponse<Stream<T>>, which is not supported"));
	}

	@Test
	void validate_ripResponseWrappingIterator_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RipResponseWrappingIterator.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("returns RipResponse<Iterator<T>>, which is not supported"));
	}

	@Test
	void validate_ripResponseWrappingNonStreamType_throwsWithGenericError() {
		// RipResponse<Order> isn't Stream/Iterator-wrapped, so the dedicated
		// rejection doesn't apply - falls through to the generic "does not
		// return Page/Stream/Iterator" message instead.
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RipResponseWrappingNonStreamType.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("does not return Page<T>, Stream<T>, or Iterator<T>"));
	}

	@Test
	void validate_rawRipResponseReturn_throwsWithTheExistingRawTypeError() {
		// validateReturnType already flags a raw RipResponse regardless of
		// @Paginated - the pagination-specific checks add nothing further for
		// this exact case, avoiding a second, overlapping message.
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RawRipResponseReturn.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("returns a raw RipResponse with no type parameter"));
	}

	@Test
	void validate_paginatedWithUrl_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(PaginatedWithUrl.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("annotated with both @Paginated and @Url"));
	}

	@Test
	void validate_fullUrlWithCursorParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(FullUrlWithCursorParam.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("pointerKind = FULL_URL but also has a @PaginationCursor parameter"));
	}

	@Test
	void validate_valuePointerWithNoCursorParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(ValuePointerWithNoCursorParam.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("found 0"));
	}

	@Test
	void validate_valuePointerWithTwoCursorParams_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(ValuePointerWithTwoCursorParams.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("found 2"));
	}

	@Test
	void validate_pointerSourceNone_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(PointerSourceNone.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("pointerSource = NONE, which needs advance() to be set"));
	}

	@Test
	void validate_pointerSourceItemField_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(PointerSourceItemField.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("pointerSource = ITEM_FIELD (keyset pagination), which is not implemented yet"));
	}

	@Test
	void validate_advanceSet_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(AdvanceSet.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("sets advance() but client-driven advancement is not implemented yet"));
	}

	@Test
	void validate_cursorOnBody_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CursorOnBody.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("@PaginationCursor stacked on @Body, which is not implemented yet"));
	}

	@Test
	void validate_bareCursorParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(BareCursorParam.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("must be stacked on exactly one of @QueryParam/@PathParam/@HeaderParam"));
	}

	@Test
	void validate_cursorParamWrongType_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CursorParamWrongType.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("only String, int, and long are supported"));
	}

	@Test
	void validate_hasMoreFieldMissing_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(HasMoreFieldMissing.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("must set hasMoreField"));
	}

	@Test
	void validate_hasMoreSourceItemField_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(HasMoreSourceItemField.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("hasMoreSource = ITEM_FIELD, which is only meaningful for pointerSource"));
	}

	@Test
	void validate_compositePointerField_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CompositePointerField.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("composite pointer is only supported for pointerSource = ITEM_FIELD"));
	}

}
