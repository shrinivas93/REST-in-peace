package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * Direct, same-package unit test for {@link PaginationCoordinator#resolveItemType}'s
 * multi-type-parameter rejection - the one branch of that method this PR
 * actually adds. In ordinary use {@code RIP.getClient()} always validates a
 * {@code @Paginated} method first, so {@code ReflectiveRestClientValidatorTest}'s
 * own coverage of the identical check in {@code validateParameterizedReturnType}
 * is what a real consumer actually hits - this test instead calls
 * {@code resolveItemType} directly (bypassing validation entirely, same as
 * {@code RequestExecutor} itself does at dispatch time), proving the
 * defense-in-depth duplicate here behaves the same way independently.
 */
class PaginationCoordinatorTest {

	private final PaginationCoordinator coordinator = new PaginationCoordinator(null);

	@Test
	void resolveItemType_multipleTypeParametersThrows() throws NoSuchMethodException {
		Method method = MultiTypeArgumentFixture.class.getMethod("foo");

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> coordinator.resolveItemType(method));

		assertTrue(exception.getMessage()
				.contains("but only a single type parameter (the page item type) is supported"));
	}

	private interface MultiTypeArgumentFixture {
		Map<String, String> foo();
	}

}
