package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kong.unirest.GenericType;
import kong.unirest.JsonObjectMapper;

class RuntimeGenericTypeTest {

	private static Type listOfStringType() {
		class Holder {
			@SuppressWarnings("unused")
			List<String> field;
		}
		try {
			return Holder.class.getDeclaredField("field").getGenericType();
		} catch (NoSuchFieldException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void of_wrapsTheGivenTypeInsteadOfObject() {
		Type listOfString = listOfStringType();

		GenericType<Object> genericType = RuntimeGenericType.of(listOfString);

		assertEquals(listOfString, genericType.getType());
	}

	@Test
	void of_decodesViaTheRealObjectMapper_intoTypedElements() {
		JsonObjectMapper mapper = new JsonObjectMapper();

		Object decoded = mapper.readValue("[\"a\",\"b\",\"c\"]", RuntimeGenericType.<List<String>>of(listOfStringType()));

		assertInstanceOf(List.class, decoded);
		List<?> list = (List<?>) decoded;
		assertEquals(3, list.size());
		for (Object element : list) {
			assertInstanceOf(String.class, element);
		}
	}

	@Test
	void of_supportsNestedParameterizedTypes() {
		class Holder {
			@SuppressWarnings("unused")
			Map<String, List<Integer>> field;
		}
		Type mapType;
		try {
			mapType = Holder.class.getDeclaredField("field").getGenericType();
		} catch (NoSuchFieldException e) {
			throw new AssertionError(e);
		}
		assertInstanceOf(ParameterizedType.class, mapType);

		GenericType<Object> genericType = RuntimeGenericType.of(mapType);

		assertEquals(mapType, genericType.getType());
	}

}
