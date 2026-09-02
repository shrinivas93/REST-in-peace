package com.shri.restinpeace.multipart;

/**
 * Notified as a {@code @Multipart} method's {@code File}/{@code InputStream}
 * parts are written to the request body, for surfacing upload progress on a
 * large file. Declare a parameter of this type on the method - it isn't
 * sent as a part itself, and doesn't need any annotation, but is only valid
 * on a method also annotated {@code @Multipart}:
 *
 * <pre>
 * {@literal @}POST("/reports")
 * {@literal @}Multipart
 * String upload({@literal @}Part("file") File file, UploadProgressListener onProgress);
 * </pre>
 *
 * Called once per {@code File}/{@code InputStream} part, so {@code field} -
 * the part's name, as given to {@code @Part}/{@code @PartMap} - identifies
 * which part {@code bytesWritten}/{@code totalBytes} describe; with more
 * than one such part, calls for different fields interleave rather than
 * running to completion one at a time. A {@code String} or {@code byte[]}
 * part is written in one shot and never reported - there's no meaningful
 * progress to observe for it. Pass {@code null} for a call that doesn't
 * need progress reporting.
 */
@FunctionalInterface
public interface UploadProgressListener {

	/**
	 * Called as one {@code File}/{@code InputStream} part is written.
	 *
	 * @param field        the part's name, as given to
	 *                     {@code @Part}/{@code @PartMap}
	 * @param bytesWritten the number of bytes written so far for this part
	 * @param totalBytes   the part's total size in bytes - exact for a
	 *                     {@code File} part, but only as reliable as
	 *                     {@link java.io.InputStream#available()} for an
	 *                     {@code InputStream} part, which may report
	 *                     {@code 0} regardless of the stream's real size
	 */
	void onProgress(String field, long bytesWritten, long totalBytes);

}
