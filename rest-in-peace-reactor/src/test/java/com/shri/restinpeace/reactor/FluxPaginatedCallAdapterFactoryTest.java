package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * Unit-level tests against {@link FluxPaginatedCallAdapterFactory} directly
 * - no MockRestServer/HTTP dispatch involved - for
 * {@link FluxPaginatedCallAdapterFactory#get(Method)}'s claiming logic,
 * which {@link FluxPaginatedCallAdapterIntegrationTest} can't exercise on
 * its own: every method {@code RequestExecutor} ever actually offers this
 * factory is already {@code @Paginated}-annotated and {@code Flux}-returning
 * (see the factory's own class javadoc), so a non-{@code Flux} return type
 * never reaches it through ordinary dispatch/validation.
 */
class FluxPaginatedCallAdapterFactoryTest {

	private final FluxPaginatedCallAdapterFactory factory = new FluxPaginatedCallAdapterFactory();

	@Test
	void get_declinesANonFluxReturnType() throws NoSuchMethodException {
		Method method = String.class.getMethod("trim");
		assertFalse(factory.get(method).isPresent());
	}

}
