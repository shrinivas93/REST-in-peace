package com.shri.restinpeace.multipart;

import java.io.File;
import java.io.InputStream;

import com.shri.restinpeace.annotation.request.PartMap;

/**
 * Wraps a {@code File}, {@code byte[]}, or {@code InputStream} with an
 * explicit file name, for use as a {@link PartMap @PartMap} entry's value
 * when the default file name (the entry's key) isn't the one to send. A
 * plain (unwrapped) {@code String}/{@code File}/{@code byte[]}/
 * {@code InputStream} value is still handled directly - wrapping is opt-in,
 * only needed to override the file name.
 *
 * <pre>
 * Map&lt;String, Object&gt; parts = new LinkedHashMap&lt;&gt;();
 * parts.put("caption", "vacation photo");
 * parts.put("file", PartValue.of(photoBytes, "photo.jpg"));
 * </pre>
 */
public final class PartValue {

	private final Object value;
	private final String fileName;

	private PartValue(Object value, String fileName) {
		this.value = value;
		this.fileName = fileName;
	}

	/**
	 * @param file     the file to send
	 * @param fileName the file name to send it under, overriding the file's own name
	 * @return a {@code PartValue} wrapping {@code file} with {@code fileName}
	 */
	public static PartValue of(File file, String fileName) {
		return new PartValue(file, fileName);
	}

	/**
	 * @param bytes    the file content to send
	 * @param fileName the file name to send it under
	 * @return a {@code PartValue} wrapping {@code bytes} with {@code fileName}
	 */
	public static PartValue of(byte[] bytes, String fileName) {
		return new PartValue(bytes, fileName);
	}

	/**
	 * @param stream   the file content to send
	 * @param fileName the file name to send it under
	 * @return a {@code PartValue} wrapping {@code stream} with {@code fileName}
	 */
	public static PartValue of(InputStream stream, String fileName) {
		return new PartValue(stream, fileName);
	}

	/**
	 * @return the wrapped value
	 */
	public Object getValue() {
		return value;
	}

	/**
	 * @return the file name to send the wrapped value under
	 */
	public String getFileName() {
		return fileName;
	}

}
