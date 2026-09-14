package com.shri.restinpeace.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.GenericType;

/**
 * Adapts an arbitrary runtime {@link Type} (e.g. one read off
 * {@link java.lang.reflect.Method#getGenericReturnType()}, not known until a
 * method is actually called) into a {@code kong.unirest.GenericType} -
 * Unirest's own extension point for exactly this problem
 * ({@code ObjectMapper.readValue(String, GenericType)}), letting a
 * {@code List<User>} decode into real {@code User} elements instead of the
 * plain {@code List.class} type erasure otherwise leaves.
 *
 * <p>
 * {@code GenericType} only exposes the common
 * {@code new GenericType<List<User>>() {}} anonymous-subclass pattern
 * (capturing {@code T} from the subclass's own compile-time generic
 * superclass signature via reflection) - there's no public constructor or
 * factory accepting a {@code Type} value directly, since that pattern
 * assumes the type is always known at the call site. Since {@code type} is
 * merely {@code protected}, not {@code private}, this instead constructs an
 * ordinary (compile-time-untyped) anonymous subclass and overwrites its
 * {@code type} field via reflection - the same field {@code GenericType}'s
 * own constructor already assigns via reflection, just supplied the actual
 * value directly instead of inferring it from a generic superclass.
 */
final class RuntimeGenericType {

	private static final Field TYPE_FIELD;

	static {
		try {
			TYPE_FIELD = GenericType.class.getDeclaredField("type");
			TYPE_FIELD.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private RuntimeGenericType() {
	}

	/**
	 * Adapts {@code type} into a {@code GenericType} for
	 * {@code ObjectMapper.readValue(String, GenericType)}.
	 *
	 * @param type the runtime type to decode into - typically a
	 *             {@link java.lang.reflect.ParameterizedType} like
	 *             {@code List<User>}, not representable as a single
	 *             {@code Class<?>}
	 * @param <T>  the decoded value's static type, for the caller's own
	 *             convenience - not actually verified against {@code type}
	 * @return a {@code GenericType} wrapping {@code type}
	 */
	static <T> GenericType<T> of(Type type) {
		GenericType<T> genericType = new GenericType<T>() {
		};
		try {
			TYPE_FIELD.set(genericType, type);
		} catch (ReflectiveOperationException e) {
			throw new RestInPeaceException("Could not adapt " + type + " into a kong.unirest.GenericType.", e);
		}
		return genericType;
	}

}
