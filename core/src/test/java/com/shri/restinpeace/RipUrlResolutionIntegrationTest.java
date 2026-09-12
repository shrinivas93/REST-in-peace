package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * Request URL resolution - {@code @BaseUrl} (relative/absolute/trailing-slash
 * combinations), a runtime base URL passed to {@code RIP.getClient}, and a
 * {@code @Url} parameter overriding both - split out of
 * {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipUrlResolutionIntegrationTest extends AbstractRipIntegrationTest {

	@RestClient
	@BaseUrl("http://localhost:{port}")
	private interface LocalApiWithBaseUrl {
		@GET("/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id);

		@GET("items/{id}")
		String getNoLeadingSlash(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://localhost:{port}/")
	private interface LocalApiWithTrailingSlashBaseUrl {
		@GET("/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://localhost:1")
	private interface AbsoluteUrlOverridesBaseUrlApi {
		@GET("http://localhost:{port}/items/{id}")
		String get(@PathParam("port") int port, @PathParam("id") String id);
	}

	@RestClient
	private interface RuntimeBaseUrlApi {
		@GET("/items/{id}")
		String get(@PathParam("id") String id);
	}

	@RestClient
	@BaseUrl("http://localhost:1")
	private interface RuntimeBaseUrlOverridesAnnotationApi {
		@GET("/items/{id}")
		String get(@PathParam("id") String id);
	}

	@Test
	void baseUrl_withRelativeMethodUrl_resolvesAgainstBase() {
		LocalApiWithBaseUrl api = RIP.getClient(LocalApiWithBaseUrl.class);

		String result = api.get(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void baseUrl_withRelativeMethodUrlMissingLeadingSlash_resolvesAgainstBase() {
		LocalApiWithBaseUrl api = RIP.getClient(LocalApiWithBaseUrl.class);

		String result = api.getNoLeadingSlash(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void baseUrl_withTrailingSlashAndLeadingSlashPath_doesNotDoubleSlash() {
		LocalApiWithTrailingSlashBaseUrl api = RIP.getClient(LocalApiWithTrailingSlashBaseUrl.class);

		String result = api.get(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void baseUrl_withAbsoluteMethodUrl_ignoresBaseUrl() {
		AbsoluteUrlOverridesBaseUrlApi api = RIP.getClient(AbsoluteUrlOverridesBaseUrlApi.class);

		String result = api.get(port, "abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void getClient_withRuntimeBaseUrl_resolvesRelativeUrlWithNoBaseUrlAnnotation() {
		RuntimeBaseUrlApi api = RIP.getClient(RuntimeBaseUrlApi.class, "http://localhost:" + port);

		String result = api.get("abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void getClient_withRuntimeBaseUrl_takesPriorityOverInterfaceBaseUrlAnnotation() {
		RuntimeBaseUrlOverridesAnnotationApi api = RIP.getClient(RuntimeBaseUrlOverridesAnnotationApi.class,
				"http://localhost:" + port);

		String result = api.get("abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void get_withUrlParam_callsGivenUrlVerbatimIgnoringBaseUrl() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getWithUrlParam("http://localhost:" + port + "/items/abc");

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
	}

	@Test
	void get_withUrlParamAndQueryParam_appendsQueryToGivenUrl() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getWithUrlParamAndQueryParam("http://localhost:" + port + "/items/abc", 7);

		assertEquals("ok", result);
		assertEquals("/items/abc", LAST_REQUEST.get().path);
		assertEquals("q=7", LAST_REQUEST.get().query);
	}

	@Test
	void get_withNullUrlParam_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class, () -> api.getWithUrlParam(null));
		assertTrue(exception.getMessage().contains("Missing value for @Url parameter"));
	}

}
