package com.shri.restinpeace.multipart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

class PartValueTest {

	@Test
	void of_inputStream_wrapsItWithTheGivenFileName() {
		InputStream stream = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

		PartValue partValue = PartValue.of(stream, "photo.jpg");

		assertSame(stream, partValue.getValue());
		assertEquals("photo.jpg", partValue.getFileName());
	}

}
