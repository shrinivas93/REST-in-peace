package com.shri.restinpeace.restclient;

import java.util.List;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * A single interface mixing every shape that used to disqualify a whole
 * interface's codegen (a default method, a static method, and a
 * {@code List<T>}-returning method) with a fully codegen-supported one -
 * proving {@code RestClientProcessor} now generates a real implementation
 * for the supported method (unlike {@link GeneratedApiWithListReturn}, whose
 * single method IS the unsupported one, so it still falls back to the
 * reflective proxy in its entirety - see that interface's own javadoc),
 * while the {@code List<String>}-returning method delegates to a lazily-built
 * internal reflective sub-proxy instead of silently regressing every other
 * method back to reflection too. A default/static method needs no such
 * fallback at all - the generated class simply doesn't override either, so
 * ordinary Java default-method/static-method semantics apply unchanged.
 */
@RestClient
public interface GeneratedApiWithPartialSupport {

	@GET("http://localhost:{port}/items/{id}")
	String get(@PathParam("port") int port, @PathParam("id") String id);

	@GET("http://localhost:{port}/string-list")
	List<String> list(@PathParam("port") int port);

	/** Computed purely from the argument - proves this is dispatched as a real default method, not an HTTP call. */
	default String greeting(String name) {
		return "Hello, " + name + "!";
	}

	static String staticHelper() {
		return "static-helper";
	}

}
