package com.shri.restinpeace.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.multipart.PartValue;
import com.shri.restinpeace.upload.UploadProgressListener;

import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.MultipartBody;

/**
 * Builds a {@code @Multipart} method's body: {@code @Part}/{@code @PartMap}
 * fields and an {@code UploadProgressListener} parameter's monitor. Stateless -
 * extracted out of {@link RequestExecutor} since multipart encoding is a
 * genuinely separate concern from everything else that class does, not
 * because it needed any state of its own.
 */
final class MultipartEncoder {

	/**
	 * Converts a request to a multipart body - the generated-code counterpart
	 * of the reflective path's own inline
	 * {@code ((HttpRequestWithBody) request).multiPartContent()} call for a
	 * {@code @Multipart} method.
	 *
	 * @param request the request to convert to a multipart body
	 * @return {@code request}, as a {@code MultipartBody}
	 */
	MultipartBody beginMultipart(HttpRequest<?> request) {
		return ((HttpRequestWithBody) request).multiPartContent();
	}

	/**
	 * Also used directly by compile-time-generated code for an {@code UploadProgressListener} parameter.
	 *
	 * @param multipartBody the multipart body to monitor
	 * @param listener      the listener to notify (callers only invoke this once
	 *                      a non-{@code null} listener argument is confirmed)
	 */
	void applyUploadMonitor(MultipartBody multipartBody, UploadProgressListener listener) {
		multipartBody.uploadMonitor((field, fileName, bytesWritten, totalBytes) -> listener.onProgress(field,
				bytesWritten == null ? 0L : bytesWritten, totalBytes == null ? -1L : totalBytes));
	}

	/**
	 * Also used directly by compile-time-generated code for a {@code @PartMap} parameter.
	 *
	 * @param multipartBody the multipart body to add parts to
	 * @param partMap       the {@code @PartMap} parameter's argument value; a
	 *                      {@code null}-valued entry is skipped
	 */
	void applyPartMap(MultipartBody multipartBody, Map<?, ?> partMap) {
		partMap.forEach((name, value) -> {
			if (value != null) {
				applyPartValue(multipartBody, String.valueOf(name), "", value);
			}
		});
	}

	/**
	 * Also used directly by compile-time-generated code for a {@code @Part} parameter.
	 *
	 * @param multipartBody the multipart body to add the part to
	 * @param name          the part's field name
	 * @param fileName      the file name to send a {@code File}/{@code byte[]}/
	 *                      {@code InputStream} part under, or empty to use
	 *                      {@code name} (or, for a {@code File}, its own name)
	 * @param value         the part's value - a {@code String}, {@code File},
	 *                      {@code byte[]}, {@code InputStream}, or a
	 *                      {@link PartValue} wrapping one of those with its own
	 *                      file name
	 */
	void applyPartValue(MultipartBody multipartBody, String name, String fileName, Object value) {
		Object effectiveValue = value;
		String effectiveFileName = fileName;
		if (value instanceof PartValue) {
			effectiveValue = ((PartValue) value).getValue();
			effectiveFileName = ((PartValue) value).getFileName();
		}
		boolean hasFileName = effectiveFileName != null && !effectiveFileName.isEmpty();
		String resolvedFileName = hasFileName ? effectiveFileName : name;
		if (effectiveValue instanceof String) {
			multipartBody.field(name, (String) effectiveValue);
		} else if (effectiveValue instanceof File) {
			if (hasFileName) {
				// MultipartBody's (name, File, String) overload sets the part's content
				// type, not its file name - there's no direct File+fileName overload, so
				// the file is streamed instead to reach the (name, InputStream, String)
				// overload that does set the file name.
				multipartBody.field(name, openFile((File) effectiveValue), resolvedFileName);
			} else {
				multipartBody.field(name, (File) effectiveValue);
			}
		} else if (effectiveValue instanceof byte[]) {
			multipartBody.field(name, (byte[]) effectiveValue, resolvedFileName);
		} else if (effectiveValue instanceof InputStream) {
			multipartBody.field(name, (InputStream) effectiveValue, resolvedFileName);
		} else {
			throw new RestInPeaceException(String.format(
					"Unsupported @Part/@PartMap value type %s for part '%s' - only String, File, byte[], and InputStream are supported.",
					effectiveValue == null ? "null" : effectiveValue.getClass().getName(), name));
		}
	}

	private static InputStream openFile(File file) {
		try {
			return new FileInputStream(file);
		} catch (FileNotFoundException e) {
			throw new RestInPeaceException(String.format("The file '%s' does not exist.", file.getPath()), e);
		}
	}

}
