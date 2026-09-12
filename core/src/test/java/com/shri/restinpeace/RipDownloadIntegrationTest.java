package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceHttpException;

/**
 * {@code byte[]}/{@code File} return types, including the download-progress
 * monitor and a non-2xx status on either - split out of
 * {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipDownloadIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void get_withByteArrayReturnType_returnsExactBytesUncorrupted() {
		LocalApi api = RIP.getClient(LocalApi.class);

		byte[] result = api.downloadBytes(port, "abc");

		assertArrayEquals(BINARY_CONTENT, result);
	}

	@Test
	void getAsync_withByteArrayReturnType_completesWithExactBytes()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		byte[] result = api.downloadBytesAsync(port, "abc").get(5, TimeUnit.SECONDS);

		assertArrayEquals(BINARY_CONTENT, result);
	}

	@Test
	void get_withRipResponseOfByteArray_exposesStatusHeadersAndExactBytes() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<byte[]> response = api.downloadBytesWithResponse(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("application/octet-stream", response.getHeader("Content-Type"));
		assertArrayEquals(BINARY_CONTENT, response.getBody());
	}

	@Test
	void get_withByteArrayReturnTypeOnNonSuccessStatus_throwsWithStringRawBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.downloadBytesFromErrorEndpoint(port, "x"));

		assertEquals(422, exception.getStatus());
		assertEquals("{\"code\":\"INVALID\",\"message\":\"nope\"}", exception.getRawBody());
	}

	@Test
	void get_withDownloadProgressListener_reportsFinalByteCounts() {
		LocalApi api = RIP.getClient(LocalApi.class);
		List<Long> reportedBytesWritten = new ArrayList<>();
		List<Long> reportedTotalBytes = new ArrayList<>();

		byte[] result = api.downloadBytesWithProgress(port, "abc", (bytesWritten, totalBytes) -> {
			reportedBytesWritten.add(bytesWritten);
			reportedTotalBytes.add(totalBytes);
		});

		assertArrayEquals(BINARY_CONTENT, result);
		assertFalse(reportedBytesWritten.isEmpty());
		assertEquals(BINARY_CONTENT.length, (long) reportedBytesWritten.get(reportedBytesWritten.size() - 1));
	}

	@Test
	void get_withFileReturnTypeAndDestination_writesExactBytesToDestination() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File destination = Files.createTempFile("rip-download", ".bin").toFile();

		File result = api.downloadToFile(port, "abc", destination);

		assertEquals(destination, result);
		assertArrayEquals(BINARY_CONTENT, Files.readAllBytes(destination.toPath()));
	}

	@Test
	void getAsync_withFileReturnTypeAndDestination_writesExactBytesToDestination()
			throws IOException, InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File destination = Files.createTempFile("rip-download-async", ".bin").toFile();

		File result = api.downloadToFileAsync(port, "abc", destination).get(5, TimeUnit.SECONDS);

		assertEquals(destination, result);
		assertArrayEquals(BINARY_CONTENT, Files.readAllBytes(destination.toPath()));
	}

	@Test
	void get_withFileReturnTypeOnNonSuccessStatus_throwsAndDoesNotWriteDestination() throws IOException {
		LocalApi api = RIP.getClient(LocalApi.class);
		File destination = Files.createTempFile("rip-download-error", ".bin").toFile();

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.downloadToFileFromErrorEndpoint(port, "x", destination));

		assertEquals(422, exception.getStatus());
		assertEquals(0L, Files.size(destination.toPath()));
	}

}
