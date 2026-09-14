package com.shri.restinpeace.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class CachedResponseTest {

	@Test
	void threeArgVaryConstructor_hasNoStaleWhileRevalidateWindowAtAll() {
		long freshUntil = System.currentTimeMillis() + 60_000;
		CachedResponse cached = new CachedResponse(200, Collections.emptyMap(), "{}", freshUntil,
				Collections.emptyMap());

		assertEquals(freshUntil, cached.getStaleWhileRevalidateUntilEpochMillis());
	}

	@Test
	void isWithinStaleWhileRevalidateWindow_falseWhileStillFresh() {
		long freshUntil = System.currentTimeMillis() + 60_000;
		CachedResponse cached = new CachedResponse(200, Collections.emptyMap(), "{}", freshUntil,
				Collections.emptyMap(), freshUntil + 60_000);

		assertFalse(cached.isWithinStaleWhileRevalidateWindow());
	}

	@Test
	void isWithinStaleWhileRevalidateWindow_trueOnceStaleButBeforeTheDeadline() {
		long freshUntil = System.currentTimeMillis() - 1_000;
		CachedResponse cached = new CachedResponse(200, Collections.emptyMap(), "{}", freshUntil,
				Collections.emptyMap(), freshUntil + 60_000);

		assertTrue(cached.isWithinStaleWhileRevalidateWindow());
	}

	@Test
	void isWithinStaleWhileRevalidateWindow_falseOnceThePastTheDeadlineToo() {
		long freshUntil = System.currentTimeMillis() - 60_000;
		CachedResponse cached = new CachedResponse(200, Collections.emptyMap(), "{}", freshUntil,
				Collections.emptyMap(), freshUntil + 1_000);

		assertFalse(cached.isWithinStaleWhileRevalidateWindow());
	}

}
