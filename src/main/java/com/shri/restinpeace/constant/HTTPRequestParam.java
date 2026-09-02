package com.shri.restinpeace.constant;

/** The kinds of request parameter binding supported by REST-in-peace's parameter annotations. */
public enum HTTPRequestParam {

	/** Bound via {@code @Body}. */
	BODY,
	/** Bound via {@code @HeaderParam}. */
	HEADER,
	/** Bound via {@code @PathParam}. */
	PATH,
	/** Bound via {@code @QueryParam}. */
	QUERY,
	/** Bound via {@code @Url}. */
	URL

}
