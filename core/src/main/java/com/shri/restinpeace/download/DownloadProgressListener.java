package com.shri.restinpeace.download;

/**
 * Notified as a {@code byte[]}- or {@code File}-returning method's response
 * body streams in, for surfacing download progress (a progress bar, a
 * percentage log line) on a large download. Declare a parameter of this
 * type on the method - it isn't sent as part of the request, and doesn't
 * need any annotation:
 *
 * <pre>
 * {@literal @}GET("/reports/{id}/pdf")
 * File downloadReport({@literal @}PathParam("id") String id, {@literal @}Destination File target,
 *         DownloadProgressListener onProgress);
 * </pre>
 *
 * Pass {@code null} for a call that doesn't need progress reporting.
 */
@FunctionalInterface
public interface DownloadProgressListener {

	/**
	 * Called as the response body streams in.
	 *
	 * @param bytesWritten the number of bytes received so far
	 * @param totalBytes   the response's total size in bytes, or {@code -1}
	 *                     if the server didn't report a {@code Content-Length}
	 */
	void onProgress(long bytesWritten, long totalBytes);

}
