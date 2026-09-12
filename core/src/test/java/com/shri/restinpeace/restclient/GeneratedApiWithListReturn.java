package com.shri.restinpeace.restclient;

import java.util.List;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Otherwise-{@link GeneratedApi}-shaped, but a generic {@code List<String>}
 * return type - and, unlike every other "still unsupported" regression
 * interface in this package, permanently so: a generic collection return
 * type isn't decodable via a single {@code Class<?>} literal the way
 * {@code String}/POJO/{@code byte[]}/{@code File}/{@code RipResponse<T>}
 * are, with or without {@code CompletableFuture} wrapping it, so this was
 * never on the design doc's §5 feature-parity table to begin with (unlike
 * every other kind this package's other {@code GeneratedApiWith*Return}
 * interfaces demonstrated - {@code @Headers}, {@code @Multipart}, and
 * {@code CompletableFuture} were all eventually supported as step 2
 * progressed). Regression coverage that this still correctly falls back to
 * the reflective proxy in its entirety.
 */
@RestClient
public interface GeneratedApiWithListReturn {

	@GET("http://localhost:{port}/items/{id}")
	List<String> get(@PathParam("port") int port, @PathParam("id") String id);

}
