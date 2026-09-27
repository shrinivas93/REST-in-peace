package reactor.core.publisher;

/**
 * Minimal test-only stand-in for the real {@code reactor-core} {@code Mono<T>}
 * class, declared in its real package so
 * {@code ReflectiveRestClientValidator}'s by-name denylist check
 * (see {@code KNOWN_UNSUPPORTED_REACTIVE_TYPES}) can be exercised
 * end-to-end without adding a test-scoped dependency on {@code reactor-core}
 * for this chunk - the real {@code rest-in-peace-reactor} module's own
 * tests use the genuine class.
 *
 * @param <T> unused; matches the real class's shape only closely enough for
 *            the by-fully-qualified-name check under test
 */
public class Mono<T> {
}
