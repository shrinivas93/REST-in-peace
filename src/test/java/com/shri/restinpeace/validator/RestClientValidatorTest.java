package com.shri.restinpeace.validator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.retry.Retry;
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
	void validate_validRetry_passes() {
		assertDoesNotThrow(() -> RestClientValidator.validate(ValidRetry.class));
	}

	@Test
	void validate_retryWithNonPositiveTimes_throwsWithError() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> RestClientValidator.validate(InvalidRetryTimes.class));
		assertTrue(exception.getValidationResult().getAllErrors().contains("times must be at least 1"));
	}

}
