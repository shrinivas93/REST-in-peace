package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RipResponseTest {

	@Test
	void getHeader_present_returnsItsFirstValue() {
		Map<String, List<String>> headers = Collections.singletonMap("X-Trace", Collections.singletonList("abc123"));
		RipResponse<String> response = new RipResponse<>(200, headers, "body");

		assertEquals("abc123", response.getHeader("X-Trace"));
	}

	@Test
	void getHeader_absent_returnsNull() {
		RipResponse<String> response = new RipResponse<>(200, Collections.emptyMap(), "body");

		assertNull(response.getHeader("Not-Sent-Header"));
	}

}
