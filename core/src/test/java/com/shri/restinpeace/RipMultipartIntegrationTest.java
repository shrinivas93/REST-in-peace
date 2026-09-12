package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.multipart.PartValue;

/**
 * {@code @Multipart}/{@code @Part}/{@code @PartMap} body building and the
 * upload-progress monitor - split out of {@code RipIntegrationTest} (see
 * {@link AbstractRipIntegrationTest}).
 */
class RipMultipartIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void multipart_withStringAndFileParts_sendsMultipartFormData() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipart(port, "abc", "a caption", file);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.header("Content-Type").startsWith("multipart/form-data"));
		assertTrue(request.body.contains("name=\"caption\""));
		assertTrue(request.body.contains("a caption"));
		assertTrue(request.body.contains("name=\"file\""));
		assertTrue(request.body.contains(file.getName()));
		assertTrue(request.body.contains("file contents"));
	}

	@Test
	void multipart_withNullOptionalPart_skipsThatField() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		api.uploadMultipart(port, "abc", null, file);

		assertFalse(LAST_REQUEST.get().body.contains("name=\"caption\""));
	}

	@Test
	void multipart_withMissingRequiredPart_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = new File("unused.txt");

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.uploadMultipartWithRequiredCaption(port, "abc", null, file));
		assertTrue(exception.getMessage().contains("Missing required value"));
	}

	@Test
	void multipart_withBytePartAndFileName_sendsGivenFileNameAndBytes() {
		LocalApi api = RIP.getClient(LocalApi.class);
		byte[] data = "byte contents".getBytes(StandardCharsets.UTF_8);
		InputStream stream = new ByteArrayInputStream("stream contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipartWithBytesAndStream(port, "abc", data, stream);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("name=\"data\""));
		assertTrue(request.body.contains("filename=\"data.bin\""));
		assertTrue(request.body.contains("byte contents"));
		assertTrue(request.body.contains("name=\"stream\""));
		assertTrue(request.body.contains("filename=\"stream\""));
		assertTrue(request.body.contains("stream contents"));
	}

	@Test
	void multipart_withFilePartAndFileName_overridesFileNameNotContentType() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		api.uploadMultipartWithRenamedFile(port, "abc", file);

		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("filename=\"renamed.txt\""));
		assertFalse(request.body.contains(file.getName()));
		assertTrue(request.body.contains("file contents"));
	}

	@Test
	void multipart_withUploadProgressListener_reportsFinalByteCountsForFileField() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));
		List<String> reportedFields = new ArrayList<>();
		List<Long> reportedBytesWritten = new ArrayList<>();

		String result = api.uploadMultipartWithProgress(port, "abc", file, (field, bytesWritten, totalBytes) -> {
			reportedFields.add(field);
			reportedBytesWritten.add(bytesWritten);
		});

		assertEquals("ok", result);
		assertFalse(reportedFields.isEmpty());
		assertTrue(reportedFields.stream().allMatch("file"::equals));
		assertEquals(file.length(), (long) reportedBytesWritten.get(reportedBytesWritten.size() - 1));
	}

	@Test
	void multipart_withNullUploadProgressListener_skipsProgressReporting() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipartWithProgress(port, "abc", file, null);

		assertEquals("ok", result);
	}

	@Test
	void partMap_withMixedValueTypes_sendsEachAsAppropriatePart() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("caption", "a caption");
		parts.put("data", "byte contents".getBytes(StandardCharsets.UTF_8));

		String result = api.uploadMultipartWithPartMap(port, "abc", parts);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("name=\"caption\""));
		assertTrue(request.body.contains("a caption"));
		assertTrue(request.body.contains("name=\"data\""));
		assertTrue(request.body.contains("filename=\"data\""));
		assertTrue(request.body.contains("byte contents"));
	}

	@Test
	void partMap_withPartValue_sendsGivenFileNameInsteadOfKey() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("file", PartValue.of("byte contents".getBytes(StandardCharsets.UTF_8), "photo.jpg"));

		String result = api.uploadMultipartWithPartMap(port, "abc", parts);

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("name=\"file\""));
		assertTrue(request.body.contains("filename=\"photo.jpg\""));
		assertFalse(request.body.contains("filename=\"file\""));
		assertTrue(request.body.contains("byte contents"));
	}

	@Test
	void partMap_withPartValueWrappingFile_overridesFileName() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File file = Files.createTempFile("rip-upload", ".txt").toFile();
		Files.write(file.toPath(), "file contents".getBytes(StandardCharsets.UTF_8));
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("file", PartValue.of(file, "renamed.txt"));

		api.uploadMultipartWithPartMap(port, "abc", parts);

		CapturedRequest request = LAST_REQUEST.get();
		assertTrue(request.body.contains("filename=\"renamed.txt\""));
		assertFalse(request.body.contains(file.getName()));
	}

	@Test
	void partMap_withNullMap_sendsNoParts() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.uploadMultipartWithPartMap(port, "abc", null);

		assertEquals("ok", result);
	}

	@Test
	void partMap_withNullValue_skipsThatEntry() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("caption", "a caption");
		parts.put("skip", null);

		api.uploadMultipartWithPartMap(port, "abc", parts);

		assertTrue(LAST_REQUEST.get().body.contains("name=\"caption\""));
		assertFalse(LAST_REQUEST.get().body.contains("name=\"skip\""));
	}

	@Test
	void partMap_withUnsupportedValueType_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("bad", 42);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.uploadMultipartWithPartMap(port, "abc", parts));
		assertTrue(exception.getMessage().contains("Unsupported"));
	}

}
