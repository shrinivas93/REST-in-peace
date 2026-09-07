package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * {@code @FormUrlEncoded}/{@code @Field}/{@code @FieldMap} body building -
 * split out of {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipFormUrlEncodedIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void formUrlEncoded_withTwoFields_sendsEncodedBodyAndContentType() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.postFormUrlEncoded(port, "abc", "client_credentials", "abc@def.com");

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("application/x-www-form-urlencoded", request.header("Content-Type"));
		assertEquals("grant_type=client_credentials&client_id=abc%40def.com", request.body);
	}

	@Test
	void formUrlEncoded_withNullOptionalField_skipsThatField() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.postFormUrlEncoded(port, "abc", "client_credentials", null);

		assertEquals("grant_type=client_credentials", LAST_REQUEST.get().body);
	}

	@Test
	void formUrlEncoded_withMissingRequiredField_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.postFormUrlEncodedWithRequiredField(port, "abc", null));
		assertTrue(exception.getMessage().contains("Missing required value"));
	}

	@Test
	void fieldMap_withEntries_sendsEachAsAFormField() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("status", "OPEN");
		fields.put("owner", "shri");

		api.postFormUrlEncodedWithFieldMap(port, "abc", fields);

		assertEquals("status=OPEN&owner=shri", LAST_REQUEST.get().body);
	}

	@Test
	void fieldMap_withNullMap_sendsEmptyBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.postFormUrlEncodedWithFieldMap(port, "abc", null);

		assertEquals("", LAST_REQUEST.get().body);
	}

	@Test
	void fieldMap_withNullValue_skipsThatEntry() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("status", "OPEN");
		fields.put("skip", null);

		api.postFormUrlEncodedWithFieldMap(port, "abc", fields);

		assertEquals("status=OPEN", LAST_REQUEST.get().body);
	}

	@Test
	void field_withCollectionValue_repeatsTheKeyOncePerElement() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.postFormUrlEncodedWithCollectionField(port, "abc", Arrays.asList("a", "b"));

		assertEquals("tag=a&tag=b", LAST_REQUEST.get().body);
	}

}
