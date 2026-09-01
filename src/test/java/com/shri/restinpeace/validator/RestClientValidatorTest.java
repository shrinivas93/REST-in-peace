package com.shri.restinpeace.validator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PartMap;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.exception.RestInPeaceValidationException;

class RestClientValidatorTest {

	public interface MissingRestClientAnnotation {
		@GET("http://example.com")
		String foo();
	}

	@RestClient
	public interface MissingHttpMethodAnnotation {
		String foo();
	}

	@RestClient
	public interface MultipleHttpMethodAnnotations {
		@GET("http://example.com")
		@PUT("http://example.com")
		String foo();
	}

	@RestClient
	public interface InvalidUrl {
		@GET("http://exa mple.com")
		String foo();
	}

	@RestClient
	public interface UnmatchedPathParam {
		@GET("http://example.com/{id}")
		String foo();
	}

	@RestClient
	public interface BodyOnGet {
		@GET("http://example.com")
		String foo(@Body String body);
	}

	@RestClient
	public interface MultipleBodyParams {
		@POST("http://example.com")
		String foo(@Body String a, @Body String b);
	}

	@RestClient
	public interface ValidAllVerbs {
		@GET("http://example.com/{id}")
		String get(@PathParam("id") String id);

		@POST("http://example.com/{id}")
		String post(@PathParam("id") String id, @Body String body);

		@PUT("http://example.com/{id}")
		String put(@PathParam("id") String id, @Body String body);

		@PATCH("http://example.com/{id}")
		String patch(@PathParam("id") String id, @Body String body);

		@DELETE("http://example.com/{id}")
		String delete(@PathParam("id") String id, @Body String body);

		@HEAD("http://example.com/{id}")
		String head(@PathParam("id") String id);

		@OPTIONS("http://example.com/{id}")
		String options(@PathParam("id") String id);
	}

	@RestClient
	public interface ValidAsync {
		@GET("http://example.com/{id}")
		CompletableFuture<String> get(@PathParam("id") String id);
	}

	@RestClient
	@SuppressWarnings("rawtypes")
	public interface RawCompletableFuture {
		@GET("http://example.com")
		CompletableFuture foo();
	}

	@RestClient
	public interface UnsupportedCompletableFutureTypeParam {
		@GET("http://example.com")
		CompletableFuture<List<String>> foo();
	}

	@RestClient
	public interface ValidRipResponse {
		@GET("http://example.com/{id}")
		RipResponse<String> get(@PathParam("id") String id);
	}

	@RestClient
	public interface ValidRipResponseOfCompletableFuture {
		@GET("http://example.com/{id}")
		CompletableFuture<RipResponse<String>> get(@PathParam("id") String id);
	}

	@RestClient
	@SuppressWarnings("rawtypes")
	public interface RawRipResponse {
		@GET("http://example.com")
		RipResponse foo();
	}

	@RestClient
	public interface UnsupportedRipResponseTypeParam {
		@GET("http://example.com")
		RipResponse<List<String>> foo();
	}

	@RestClient
	public interface ValidRetry {
		@GET("http://example.com")
		@Retry(times = 3)
		String foo();
	}

	@RestClient
	public interface InvalidRetryTimes {
		@GET("http://example.com")
		@Retry(times = 0)
		String foo();
	}

	@RestClient
	public interface ValidTimeout {
		@GET("http://example.com")
		@Timeout(connectMillis = 1_000, readMillis = 5_000)
		String foo();
	}

	@RestClient
	public interface InvalidTimeoutConnectMillis {
		@GET("http://example.com")
		@Timeout(connectMillis = -5)
		String foo();
	}

	@RestClient
	public interface InvalidTimeoutReadMillis {
		@GET("http://example.com")
		@Timeout(readMillis = -5)
		String foo();
	}

	@RestClient
	public interface RelativeUrlWithoutBaseUrl {
		@GET("/items/{id}")
		String foo(@PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://example.com")
	public interface RelativeUrlWithBaseUrl {
		@GET("/items/{id}")
		String foo(@PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://example.com")
	public interface AbsoluteUrlOverridesBaseUrl {
		@GET("http://override.example.com/items")
		String foo();
	}

	@RestClient
	@BaseUrl("http://localhost:{port}")
	public interface BaseUrlWithPlaceholder {
		@GET("/items/{id}")
		String foo(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	public interface ValidQueryAndHeaderMap {
		@GET("http://example.com")
		String foo(@QueryParam("fixed") String fixed, @QueryMap Map<String, String> filters,
				@HeaderMap Map<String, Object> headers);
	}

	@RestClient
	public interface MultipleQueryMaps {
		@GET("http://example.com")
		String foo(@QueryMap Map<String, String> a, @QueryMap Map<String, String> b);
	}

	@RestClient
	public interface MultipleHeaderMaps {
		@GET("http://example.com")
		String foo(@HeaderMap Map<String, String> a, @HeaderMap Map<String, String> b);
	}

	@RestClient
	public interface QueryMapNotAMap {
		@GET("http://example.com")
		String foo(@QueryMap String notAMap);
	}

	@RestClient
	public interface HeaderMapNotAMap {
		@GET("http://example.com")
		String foo(@HeaderMap String notAMap);
	}

	@RestClient
	public interface ValidMultipart {
		@POST("http://example.com")
		@Multipart
		String foo(@Part("caption") String caption, @Part("file") File file);
	}

	@RestClient
	public interface MultipartOnGet {
		@GET("http://example.com")
		@Multipart
		String foo(@Part("caption") String caption);
	}

	@RestClient
	public interface MultipartWithoutParts {
		@POST("http://example.com")
		@Multipart
		String foo();
	}

	@RestClient
	public interface MultipartAndBody {
		@POST("http://example.com")
		@Multipart
		String foo(@Part("caption") String caption, @Body String body);
	}

	@RestClient
	public interface PartWithoutMultipart {
		@POST("http://example.com")
		String foo(@Part("caption") String caption);
	}

	@RestClient
	public interface PartWrongType {
		@POST("http://example.com")
		@Multipart
		String foo(@Part("count") int count);
	}

	@RestClient
	public interface ValidPartBytesAndStream {
		@POST("http://example.com")
		@Multipart
		String foo(@Part("data") byte[] data, @Part("stream") InputStream stream);
	}

	@RestClient
	public interface ValidPartMapOnly {
		@POST("http://example.com")
		@Multipart
		String foo(@PartMap Map<String, Object> parts);
	}

	@RestClient
	public interface PartMapWithoutMultipart {
		@POST("http://example.com")
		String foo(@PartMap Map<String, Object> parts);
	}

	@RestClient
	public interface PartMapNotAMap {
		@POST("http://example.com")
		@Multipart
		String foo(@PartMap String notAMap);
	}

	@RestClient
	public interface MultiplePartMaps {
		@POST("http://example.com")
		@Multipart
		String foo(@PartMap Map<String, Object> first, @PartMap Map<String, Object> second);
	}

	@Test
	void validate_nullRestClient_throws() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(null));
		assertTrue(exception.getValidationResult().getAllErrors().contains("Rest Client cannot be null"));
	}

	@Test
	void validate_missingRestClientAnnotation_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MissingRestClientAnnotation.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("not annotated with @RestClient"));
	}

	@Test
	void validate_missingHttpMethodAnnotation_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MissingHttpMethodAnnotation.class));
		assertTrue(
				exception.getValidationResult().getAllErrors().contains("not annotated with any of the HTTP method"));
	}

	@Test
	void validate_multipleHttpMethodAnnotations_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultipleHttpMethodAnnotations.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("more than one HTTP method annotations"));
	}

	@Test
	void validate_invalidUrl_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(InvalidUrl.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("invalid URL"));
	}

	@Test
	void validate_unmatchedPathParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(UnmatchedPathParam.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("path param 'id' in its URL that is not annotated"));
	}

	@Test
	void validate_bodyOnGet_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(BodyOnGet.class));
		assertTrue(
				exception.getValidationResult().getAllErrors().contains("does not support a request body"));
	}

	@Test
	void validate_multipleBodyParams_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultipleBodyParams.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("more than one parameter annotated with @Body"));
	}

	@Test
	void validate_validInterfaceCoveringAllVerbs_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidAllVerbs.class));
	}

	@Test
	void validate_validCompletableFuture_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidAsync.class));
	}

	@Test
	void validate_rawCompletableFuture_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(RawCompletableFuture.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("raw CompletableFuture"));
	}

	@Test
	void validate_unsupportedCompletableFutureTypeParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(UnsupportedCompletableFutureTypeParam.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("not a supported type parameter"));
	}

	@Test
	void validate_validRipResponse_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidRipResponse.class));
	}

	@Test
	void validate_validRipResponseOfCompletableFuture_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidRipResponseOfCompletableFuture.class));
	}

	@Test
	void validate_rawRipResponse_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(RawRipResponse.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("raw RipResponse"));
	}

	@Test
	void validate_unsupportedRipResponseTypeParam_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(UnsupportedRipResponseTypeParam.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("not a supported type parameter"));
	}

	@Test
	void validate_validRetry_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidRetry.class));
	}

	@Test
	void validate_retryWithNonPositiveTimes_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(InvalidRetryTimes.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("times must be at least 1"));
	}

	@Test
	void validate_validTimeout_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidTimeout.class));
	}

	@Test
	void validate_timeoutWithInvalidConnectMillis_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(InvalidTimeoutConnectMillis.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("connectMillis must be -1"));
	}

	@Test
	void validate_timeoutWithInvalidReadMillis_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(InvalidTimeoutReadMillis.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("readMillis must be -1"));
	}

	@Test
	void validate_relativeUrlWithoutBaseUrl_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(RelativeUrlWithoutBaseUrl.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("relative URL '/items/{id}' but the interface is not annotated with @BaseUrl"));
	}

	@Test
	void validate_relativeUrlWithBaseUrl_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(RelativeUrlWithBaseUrl.class));
	}

	@Test
	void validate_absoluteUrlIgnoresBaseUrl_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(AbsoluteUrlOverridesBaseUrl.class));
	}

	@Test
	void validate_baseUrlWithPlaceholder_matchedAcrossBaseAndPath_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(BaseUrlWithPlaceholder.class));
	}

	@Test
	void validate_relativeUrlWithRuntimeBaseUrlOverride_passesEvenWithoutBaseUrlAnnotation() {
		assertDoesNotThrow(
				() -> RestClientValidator.validate(RelativeUrlWithoutBaseUrl.class, "http://example.com"));
	}

	@Test
	void validate_validQueryAndHeaderMap_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidQueryAndHeaderMap.class));
	}

	@Test
	void validate_multipleQueryMaps_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultipleQueryMaps.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("more than one parameter annotated with @QueryMap"));
	}

	@Test
	void validate_multipleHeaderMaps_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultipleHeaderMaps.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("more than one parameter annotated with @HeaderMap"));
	}

	@Test
	void validate_queryMapNotAMap_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(QueryMapNotAMap.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("parameter annotated with @QueryMap that is not a Map"));
	}

	@Test
	void validate_headerMapNotAMap_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(HeaderMapNotAMap.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("parameter annotated with @HeaderMap that is not a Map"));
	}

	@Test
	void validate_validMultipart_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidMultipart.class));
	}

	@Test
	void validate_multipartOnGet_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultipartOnGet.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("@Multipart but HTTP method GET does not support a request body"));
	}

	@Test
	void validate_multipartWithoutParts_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultipartWithoutParts.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("@Multipart but has no @Part or @PartMap parameters"));
	}

	@Test
	void validate_multipartAndBody_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultipartAndBody.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("@Multipart and also has a @Body parameter"));
	}

	@Test
	void validate_partWithoutMultipart_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(PartWithoutMultipart.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("has a @Part parameter but is not annotated with @Multipart"));
	}

	@Test
	void validate_partWrongType_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(PartWrongType.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("only String, File, byte[], and InputStream are supported"));
	}

	@Test
	void validate_partBytesAndStream_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidPartBytesAndStream.class));
	}

	@Test
	void validate_partMapOnly_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidPartMapOnly.class));
	}

	@Test
	void validate_partMapWithoutMultipart_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(PartMapWithoutMultipart.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("has a @PartMap parameter but is not annotated with @Multipart"));
	}

	@Test
	void validate_partMapNotAMap_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(PartMapNotAMap.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("parameter annotated with @PartMap that is not a Map"));
	}

	@Test
	void validate_multiplePartMaps_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(MultiplePartMaps.class));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("more than one parameter annotated with @PartMap"));
	}

}
