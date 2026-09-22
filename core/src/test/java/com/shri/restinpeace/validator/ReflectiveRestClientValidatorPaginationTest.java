package com.shri.restinpeace.validator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.POST;
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
 * chunk-2/3/4/5/7-supported subset of {@code docs/design/pagination-helper.md}
 * §7 (see {@code ReflectiveRestClientValidator.validatePaginated}'s own
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

	@RestClient
	public interface RipResponseWrappingParameterizedNonStreamType {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		RipResponse<List<Order>> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@SuppressWarnings("rawtypes")
	@RestClient
	public interface RawRipResponseReturn {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		RipResponse listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@SuppressWarnings("rawtypes")
	@RestClient
	public interface RawCompletableFutureReturn {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		CompletableFuture listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
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
	public interface ValuePointerFieldEmpty {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
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
	public interface AdvanceIncrementByPageSize {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
				advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE, pageSize = 50, totalField = "total")
		Page<Order> listOrders(@QueryParam("offset") @PaginationCursor int offset);
	}

	@RestClient
	public interface AdvanceIncrementByOne {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
				advance = PaginationAdvance.INCREMENT_BY_ONE)
		Page<Order> listOrders(@QueryParam("page") @PaginationCursor int page);
	}

	@RestClient
	public interface AdvanceIntoBody {
		@POST("http://example.com/orders/search")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
				advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE, pageSize = 50)
		Page<Order> listOrders(@Body @PaginationCursor(bodyField = "offset") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface AdvanceIncrementByPageSizeMissingPageSize {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
				advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE)
		Page<Order> listOrders(@QueryParam("offset") @PaginationCursor int offset);
	}

	@RestClient
	public interface AdvanceWithTwoCursorParams {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
				advance = PaginationAdvance.INCREMENT_BY_ONE)
		Page<Order> listOrders(@QueryParam("page") @PaginationCursor int page,
				@HeaderParam("X-Page") @PaginationCursor int pageHeader);
	}

	@RestClient
	public interface AdvanceWithNoCursorParams {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
				advance = PaginationAdvance.INCREMENT_BY_ONE)
		Page<Order> listOrders();
	}

	@RestClient
	public interface PointerSourceItemField {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD, pointerField = "id")
		Page<Order> listOrders(@QueryParam("since") @PaginationCursor String since);
	}

	@RestClient
	public interface CompositeItemFieldTwoParams {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD,
				pointerField = "id,createdAt")
		Page<Order> listOrders(@QueryParam("lastId") @PaginationCursor String lastId,
				@QueryParam("lastTs") @PaginationCursor String lastTimestamp);
	}

	@RestClient
	public interface CompositeItemFieldWrongParamCount {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD,
				pointerField = "id,createdAt")
		Page<Order> listOrders(@QueryParam("lastId") @PaginationCursor String lastId);
	}

	@RestClient
	public interface CompositeItemFieldIntoBody {
		@POST("http://example.com/orders/search")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD,
				pointerField = "id,createdAt")
		Page<Order> listOrders(
				@Body @PaginationCursor(bodyField = "lastId,lastTimestamp") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface CompositeItemFieldIntoBodyWrongCount {
		@POST("http://example.com/orders/search")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD,
				pointerField = "id,createdAt,tenantId")
		Page<Order> listOrders(
				@Body @PaginationCursor(bodyField = "lastId,lastTimestamp") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface BodyCursorAlongsideAnotherCursorParam {
		@POST("http://example.com/orders/search")
		@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD,
				pointerField = "id,createdAt")
		Page<Order> listOrders(@QueryParam("lastId") @PaginationCursor String lastId,
				@Body @PaginationCursor(bodyField = "lastTimestamp") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface AdvanceSet {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor", advance = PaginationAdvance.INCREMENT_BY_ONE)
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

	@RestClient
	public interface CursorOnBody {
		@POST("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@Body @PaginationCursor(bodyField = "cursor") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface CursorOnBodyNestedField {
		@POST("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(
				@Body @PaginationCursor(bodyField = "meta.cursor") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface CursorOnBodyMissingBodyField {
		@POST("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@Body @PaginationCursor java.util.Map<String, Object> body);
	}

	@RestClient
	public interface CursorOnBodyCompositeBodyField {
		@POST("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(
				@Body @PaginationCursor(bodyField = "lastId,lastTimestamp") java.util.Map<String, Object> body);
	}

	@RestClient
	public interface CursorOnBodyWrongMapType {
		@POST("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(
				@Body @PaginationCursor(bodyField = "cursor") java.util.Map<String, String> body);
	}

	@SuppressWarnings("rawtypes")
	@RestClient
	public interface CursorOnBodyRawMapType {
		@POST("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@Body @PaginationCursor(bodyField = "cursor") java.util.Map body);
	}

	@RestClient
	public interface CursorOnBodyNonMapType {
		@POST("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@Body @PaginationCursor(bodyField = "cursor") String body);
	}

	@RestClient
	public interface BodyFieldWithoutBodyCarrier {
		@GET("http://example.com/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		Page<Order> listOrders(@QueryParam("cursor") @PaginationCursor(bodyField = "cursor") String cursor);
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
	void validate_ripResponseWrappingParameterizedNonStreamType_throwsWithGenericError() {
		// RipResponse<List<Order>>'s inner type IS parameterized, but its raw type
		// is neither Stream nor Iterator - exercises the branch of
		// isStreamOrIteratorInner's final check that RipResponse<Order> (a
		// non-parameterized inner type) can't reach.
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RipResponseWrappingParameterizedNonStreamType.class));
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
	void validate_rawCompletableFutureReturn_throwsWithTheExistingRawTypeError() {
		// Same overlap-avoidance as the raw RipResponse case above, but for the
		// other raw-generic return type the early-return guard covers.
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RawCompletableFutureReturn.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("returns a raw CompletableFuture with no type parameter"));
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
	void validate_valuePointerFieldEmpty_throwsWithOnlyTheMissingFieldError() {
		// An empty pointerField already gets its own "must set pointerField" error -
		// must not cascade into the unrelated cursor-param-count check too.
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(ValuePointerFieldEmpty.class));
		String errors = exception.getValidationResult().getAllErrors();
		assertTrue(errors.contains("must set pointerField"));
		assertFalse(errors.contains("@PaginationCursor parameter(s)"));
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
	void validate_pointerSourceItemField_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(PointerSourceItemField.class));
	}

	@Test
	void validate_compositeItemFieldTwoParams_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(CompositeItemFieldTwoParams.class));
	}

	@Test
	void validate_compositeItemFieldWrongParamCount_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CompositeItemFieldWrongParamCount.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("needs 2 @PaginationCursor parameter(s) (matching pointerField's 2 comma-separated "
						+ "entries) - found 1"));
	}

	@Test
	void validate_compositeItemFieldIntoBody_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(CompositeItemFieldIntoBody.class));
	}

	@Test
	void validate_compositeItemFieldIntoBodyWrongCount_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CompositeItemFieldIntoBodyWrongCount.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("pointerField naming 3 value(s) but its @Body @PaginationCursor's bodyField names 2"));
	}

	@Test
	void validate_bodyCursorAlongsideAnotherCursorParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(BodyCursorAlongsideAnotherCursorParam.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("must be the method's only @PaginationCursor parameter"));
	}

	@Test
	void validate_advanceSetWithPointerSourceNotNone_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(AdvanceSet.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("sets advance() but pointerSource is not NONE"));
	}

	@Test
	void validate_advanceIncrementByPageSize_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(AdvanceIncrementByPageSize.class));
	}

	@Test
	void validate_advanceIncrementByOne_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(AdvanceIncrementByOne.class));
	}

	@Test
	void validate_advanceIntoBody_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(AdvanceIntoBody.class));
	}

	@Test
	void validate_advanceIncrementByPageSizeMissingPageSize_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(AdvanceIncrementByPageSizeMissingPageSize.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("needs pageSize() to be a positive number"));
	}

	@Test
	void validate_advanceWithTwoCursorParams_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(AdvanceWithTwoCursorParams.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("needs exactly one @PaginationCursor parameter to carry the client-computed "
						+ "offset/page value - found 2"));
	}

	@Test
	void validate_advanceWithNoCursorParams_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(AdvanceWithNoCursorParams.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("needs exactly one @PaginationCursor parameter to carry the client-computed "
						+ "offset/page value - found 0"));
	}

	@Test
	void validate_cursorOnBody_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(CursorOnBody.class));
	}

	@Test
	void validate_cursorOnBodyNestedField_doesNotThrow() {
		assertDoesNotThrow(() -> ReflectiveRestClientValidator.validate(CursorOnBodyNestedField.class));
	}

	@Test
	void validate_cursorOnBodyMissingBodyField_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CursorOnBodyMissingBodyField.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("stacked on @Body but bodyField is empty"));
	}

	@Test
	void validate_cursorOnBodyCompositeBodyField_throwsWithError() {
		// pointerField has only 1 entry (default pointerSource, not ITEM_FIELD) but
		// bodyField names 2 - a count mismatch, regardless of the ITEM_FIELD question.
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CursorOnBodyCompositeBodyField.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("pointerField naming 1 value(s) but its @Body @PaginationCursor's bodyField names 2"));
	}

	@Test
	void validate_cursorOnBodyWrongMapType_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CursorOnBodyWrongMapType.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("declared type is not Map<String,Object>"));
	}

	@Test
	void validate_cursorOnBodyRawMapType_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CursorOnBodyRawMapType.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("declared type is not Map<String,Object>"));
	}

	@Test
	void validate_cursorOnBodyNonMapType_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(CursorOnBodyNonMapType.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("declared type is not Map<String,Object>"));
	}

	@Test
	void validate_bodyFieldWithoutBodyCarrier_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(BodyFieldWithoutBodyCarrier.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("non-empty bodyField but is not stacked on @Body"));
	}

	@Test
	void validate_bareCursorParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(BareCursorParam.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("must be stacked on exactly one of @QueryParam/@PathParam/@HeaderParam/@Body"));
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
