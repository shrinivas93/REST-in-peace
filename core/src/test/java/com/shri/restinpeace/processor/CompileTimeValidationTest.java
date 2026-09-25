package com.shri.restinpeace.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile-testing validation suite for compile-time proxy generation
 * (step 4 of {@code docs/design/compile-time-proxy-generation.md}'s rollout
 * plan): compiles small, self-contained {@code @RestClient} interfaces
 * through a real, isolated {@code javac} invocation - not the ambient Maven
 * build - and asserts on the resulting {@link Diagnostic}s, proving two
 * things the rest of the test suite can't: that a semantically invalid
 * interface (the same rules {@code ReflectiveRestClientValidatorTest} already covers
 * at runtime) fails <b>compilation</b> with a matching error message via
 * {@link CompileTimeRestClientValidator}, and that a valid one compiles clean and
 * produces a real {@code _RipImpl} class.
 *
 * <p>
 * Hand-rolled against {@code javax.tools.JavaCompiler} rather than Google's
 * {@code compile-testing} library, per the design doc's own choice of
 * either - no new dependency, consistent with this project's one-dependency
 * (Unirest) footprint. Each fixture interface is compiled from an in-memory
 * source string (never a real {@code .java} file under {@code src/test/java}
 * - an invalid one would otherwise fail the ordinary {@code mvn test-compile}
 * itself, since {@code RestClientProcessor} auto-activates via its bundled
 * SPI file on this test's own classpath, exactly as it would for a real
 * downstream consumer).
 */
class CompileTimeValidationTest {

	@TempDir
	Path outputDir;

	@Test
	void validInterface_compilesCleanAndGeneratesImpl() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ValidApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface ValidApi {\n" //
				+ "  @GET(\"http://localhost:{port}/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"port\") int port, @PathParam(\"id\") String id);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ValidApi_RipImpl.class")),
				"Expected ValidApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void noHttpMethodAnnotation_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("NoHttpMethodAnnotation", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "@RestClient\n" //
				+ "public interface NoHttpMethodAnnotation {\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is not annotated with any of the HTTP method annotations");
	}

	@Test
	void urlParamWrongType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UrlParamWrongType", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Url;\n" //
				+ "@RestClient\n" //
				+ "public interface UrlParamWrongType {\n" //
				+ "  @GET(com.shri.restinpeace.constant.RIPConstants.DEFAULT)\n" //
				+ "  String getItem(@Url int url);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @Url parameter of type int - only String is supported");
	}

	@Test
	void unmatchedPathParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UnmatchedPathParam", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "public interface UnmatchedPathParam {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"has path param 'id' in its URL that is not annotated on any parameter with @PathParam");
	}

	@Test
	void stalePathParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("StalePathParam", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface StalePathParam {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id, @PathParam(\"userId\") String userId);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @PathParam('userId') that does not appear as '{userId}' in its URL");
	}

	@Test
	void urlParamWithPathParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UrlParamWithPathParam", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import com.shri.restinpeace.annotation.request.Url;\n" //
				+ "@RestClient\n" //
				+ "public interface UrlParamWithPathParam {\n" //
				+ "  @GET\n" //
				+ "  String getItem(@Url String url, @PathParam(\"id\") String id);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has both a @Url parameter and a @PathParam parameter");
	}

	@Test
	void invalidRetryJitterFactor_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("InvalidRetryJitterFactor", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.retry.Retry;\n" //
				+ "@RestClient\n" //
				+ "public interface InvalidRetryJitterFactor {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Retry(jitterFactor = 1.5)\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Retry but jitterFactor must be between 0.0 and 1.0 inclusive");
	}

	@Test
	void invalidInterfaceLevelRetryJitterFactor_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("InvalidInterfaceLevelRetryJitterFactor", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.retry.Retry;\n" //
				+ "@RestClient\n" //
				+ "@Retry(jitterFactor = 1.5)\n" //
				+ "public interface InvalidInterfaceLevelRetryJitterFactor {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Retry but jitterFactor must be between 0.0 and 1.0 inclusive");
	}

	@Test
	void validInterfaceLevelRetry_compilesCleanAndGeneratesImplHonoringIt() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ValidInterfaceLevelRetry", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.retry.Retry;\n" //
				+ "@RestClient\n" //
				+ "@Retry(times = 3)\n" //
				+ "public interface ValidInterfaceLevelRetry {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ValidInterfaceLevelRetry_RipImpl.class")),
				"Expected ValidInterfaceLevelRetry_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void interfaceWithDefaultMethod_compilesCleanAndStillGeneratesAnImplementation() throws IOException {
		// CompileTimeRestClientValidator doesn't fail the *build* over a default
		// method (mirroring ReflectiveRestClientValidator's own exemption for a
		// default/static method at runtime), and RestClientProcessor no longer
		// disqualifies the whole interface's codegen over it either - a default
		// method needs no generated override at all (ordinary Java default-method
		// dispatch already resolves it via the generated class's own inherited
		// implementation), so getItem() still gets a real generated implementation.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ApiWithDefaultMethod", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface ApiWithDefaultMethod {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  default String greeting() { return \"hi\"; }\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ApiWithDefaultMethod_RipImpl.class")),
				"Expected ApiWithDefaultMethod_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void interfaceMixingAnUnsupportedListReturnWithASupportedMethod_generatesAnImplementationForBoth()
			throws IOException {
		// The List<String>-returning method is outside RestClientProcessor's
		// codegen-supported shape (not decodable by a single Class<?> the way a
		// plain POJO is), but that alone no longer disqualifies getItem() - a
		// fully supported method on the very same interface - from codegen too.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ApiWithPartialSupport", "" //
				+ "import java.util.List;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface ApiWithPartialSupport {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  List<String> listItems();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ApiWithPartialSupport_RipImpl.class")),
				"Expected ApiWithPartialSupport_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void bodyOnGet_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("BodyOnGet", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "@RestClient\n" //
				+ "public interface BodyOnGet {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String createItem(@Body String payload);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Body but HTTP method GET does not support a request body");
	}

	@Test
	void rawCompletableFuture_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("RawCompletableFuture", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface RawCompletableFuture {\n" //
				+ "  @SuppressWarnings(\"rawtypes\")\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  CompletableFuture getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns a raw CompletableFuture with no type parameter");
	}

	@Test
	void completableFutureOfVoid_compilesCleanAndGeneratesImpl() throws IOException {
		// CompletableFuture<Void> is the async-void shape - isSupportedReturnTypeArgument's
		// own VOID-kind check exists specifically for this (a boxed Void type
		// argument, not a raw `void` return type, which is a different code
		// path entirely - see nonAsyncReturnModelOf's own primitive-void handling).
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompletableFutureOfVoid", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface CompletableFutureOfVoid {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  CompletableFuture<Void> createItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void completableFutureOfWildcard_failsCompilation() throws IOException {
		// A wildcard carries no runtime type to decode into at all - unlike a
		// generic collection like List<String> (a DeclaredType, fully supported -
		// see completableFutureOfGenericCollection_...), isSupportedReturnTypeArgument
		// must still reject a WILDCARD/TYPEVAR kind rather than silently accepting it.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompletableFutureOfWildcard", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface CompletableFutureOfWildcard {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  CompletableFuture<?> getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "which is not a supported type parameter");
	}

	@Test
	void ripResponseOfFile_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("RipResponseOfFile", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface RipResponseOfFile {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  RipResponse<File> getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns RipResponse<File>, which is not supported");
	}

	@Test
	void invalidRetryTimes_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("InvalidRetryTimes", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.retry.Retry;\n" //
				+ "@RestClient\n" //
				+ "public interface InvalidRetryTimes {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Retry(times = 0)\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Retry but times must be at least 1");
	}

	@Test
	void queryMapWrongType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("QueryMapWrongType", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryMap;\n" //
				+ "@RestClient\n" //
				+ "public interface QueryMapWrongType {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem(@QueryMap String notAMap);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a parameter annotated with @QueryMap that is not a Map");
	}

	@Test
	void multipartWithNoParts_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipartNoParts", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipartNoParts {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  @Multipart\n" //
				+ "  String createItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Multipart but has no @Part or @PartMap parameters");
	}

	@Test
	void validFormUrlEncoded_compilesCleanAndGeneratesImpl() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ValidFormUrlEncoded", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Field;\n" //
				+ "import com.shri.restinpeace.annotation.request.FormUrlEncoded;\n" //
				+ "@RestClient\n" //
				+ "public interface ValidFormUrlEncoded {\n" //
				+ "  @POST(\"http://localhost/oauth/token\")\n" //
				+ "  @FormUrlEncoded\n" //
				+ "  String getToken(@Field(\"grant_type\") String grantType, @Field(\"client_id\") String clientId);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ValidFormUrlEncoded_RipImpl.class")),
				"Expected ValidFormUrlEncoded_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void formUrlEncodedWithNoFields_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FormUrlEncodedNoFields", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.FormUrlEncoded;\n" //
				+ "@RestClient\n" //
				+ "public interface FormUrlEncodedNoFields {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  @FormUrlEncoded\n" //
				+ "  String createItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @FormUrlEncoded but has no @Field or @FieldMap parameters");
	}

	@Test
	void formUrlEncodedAndMultipart_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FormUrlEncodedAndMultipart", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Field;\n" //
				+ "import com.shri.restinpeace.annotation.request.FormUrlEncoded;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "import com.shri.restinpeace.annotation.request.Part;\n" //
				+ "@RestClient\n" //
				+ "public interface FormUrlEncodedAndMultipart {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  @FormUrlEncoded\n" //
				+ "  @Multipart\n" //
				+ "  String createItem(@Field(\"a\") String a, @Part(\"b\") String b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with both @Multipart and @FormUrlEncoded");
	}

	@Test
	void invalidTimeoutConnectMillis_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("InvalidTimeoutConnectMillis", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.timeout.Timeout;\n" //
				+ "@RestClient\n" //
				+ "public interface InvalidTimeoutConnectMillis {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Timeout(connectMillis = -5)\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Timeout but connectMillis must be -1 (unset) or a "
				+ "non-negative number of milliseconds");
	}

	@Test
	void headersEntryMissingColon_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("HeadersEntryMissingColon", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Headers;\n" //
				+ "@RestClient\n" //
				+ "public interface HeadersEntryMissingColon {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Headers(\"NoColonHere\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @Headers entry 'NoColonHere' with no ':' - expected 'Name: Value'");
	}

	@Test
	void destinationWithoutFileReturn_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("DestinationWithoutFileReturn", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Destination;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface DestinationWithoutFileReturn {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem(@Destination File destination);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @Destination parameter but does not return File");
	}

	@Test
	void downloadProgressListenerWrongReturn_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("DownloadListenerWrongReturn", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.download.DownloadProgressListener;\n" //
				+ "@RestClient\n" //
				+ "public interface DownloadListenerWrongReturn {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem(DownloadProgressListener listener);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a DownloadProgressListener parameter but does not return byte[] or File");
	}

	@Test
	void uploadProgressListenerWithoutMultipart_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UploadListenerWithoutMultipart", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.upload.UploadProgressListener;\n" //
				+ "@RestClient\n" //
				+ "public interface UploadListenerWithoutMultipart {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem(UploadProgressListener listener);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"has an UploadProgressListener parameter but is not annotated with @Multipart");
	}

	@Test
	void multipleHttpMethodAnnotations_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipleHttpMethodAnnotations", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipleHttpMethodAnnotations {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one HTTP method annotations");
	}

	@Test
	void multipleBodyParams_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipleBodyParams", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipleBodyParams {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  String createItem(@Body String a, @Body String b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one parameter annotated with @Body");
	}

	@Test
	void invalidTimeoutReadMillis_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("InvalidTimeoutReadMillis", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.timeout.Timeout;\n" //
				+ "@RestClient\n" //
				+ "public interface InvalidTimeoutReadMillis {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Timeout(readMillis = -5)\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Timeout but readMillis must be -1 (unset) or a "
				+ "non-negative number of milliseconds");
	}

	@Test
	void headersEntryEmptyName_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("HeadersEntryEmptyName", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Headers;\n" //
				+ "@RestClient\n" //
				+ "public interface HeadersEntryEmptyName {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Headers(\": value\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "with an empty header name");
	}

	@Test
	void multipleQueryMapParams_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipleQueryMapParams", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryMap;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipleQueryMapParams {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String search(@QueryMap Map<String, String> a, @QueryMap Map<String, String> b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one parameter annotated with @QueryMap");
	}

	@Test
	void multipartOnNonBodyMethod_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipartOnNonBodyMethod", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "import com.shri.restinpeace.annotation.request.Part;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipartOnNonBodyMethod {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Multipart\n" //
				+ "  String getItem(@Part(\"f\") String f);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"is annotated with @Multipart but HTTP method GET does not support a request body");
	}

	@Test
	void multipartWithBodyParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipartWithBodyParam", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "import com.shri.restinpeace.annotation.request.Part;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipartWithBodyParam {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  @Multipart\n" //
				+ "  String createItem(@Part(\"f\") String f, @Body String b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @Multipart and also has a @Body parameter");
	}

	@Test
	void partWithoutMultipart_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PartWithoutMultipart", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Part;\n" //
				+ "@RestClient\n" //
				+ "public interface PartWithoutMultipart {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  String createItem(@Part(\"f\") String f);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @Part parameter but is not annotated with @Multipart");
	}

	@Test
	void partMapWithoutMultipart_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PartMapWithoutMultipart", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.PartMap;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface PartMapWithoutMultipart {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  String createItem(@PartMap Map<String, Object> parts);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @PartMap parameter but is not annotated with @Multipart");
	}

	@Test
	void unsupportedPartType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UnsupportedPartType", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "import com.shri.restinpeace.annotation.request.Part;\n" //
				+ "@RestClient\n" //
				+ "public interface UnsupportedPartType {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  @Multipart\n" //
				+ "  String createItem(@Part(\"f\") int f);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"has a @Part parameter of type int - only String, File, byte[], and InputStream are supported");
	}

	@Test
	void formUrlEncodedOnNonBodyMethod_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FormUrlEncodedOnNonBodyMethod", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Field;\n" //
				+ "import com.shri.restinpeace.annotation.request.FormUrlEncoded;\n" //
				+ "@RestClient\n" //
				+ "public interface FormUrlEncodedOnNonBodyMethod {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @FormUrlEncoded\n" //
				+ "  String getItem(@Field(\"a\") String a);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"is annotated with @FormUrlEncoded but HTTP method GET does not support a request body");
	}

	@Test
	void formUrlEncodedWithBodyParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FormUrlEncodedWithBodyParam", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import com.shri.restinpeace.annotation.request.Field;\n" //
				+ "import com.shri.restinpeace.annotation.request.FormUrlEncoded;\n" //
				+ "@RestClient\n" //
				+ "public interface FormUrlEncodedWithBodyParam {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  @FormUrlEncoded\n" //
				+ "  String createItem(@Field(\"a\") String a, @Body String b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with @FormUrlEncoded and also has a @Body parameter");
	}

	@Test
	void fieldWithoutFormUrlEncoded_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FieldWithoutFormUrlEncoded", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Field;\n" //
				+ "@RestClient\n" //
				+ "public interface FieldWithoutFormUrlEncoded {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  String createItem(@Field(\"a\") String a);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @Field parameter but is not annotated with @FormUrlEncoded");
	}

	@Test
	void fieldMapWithoutFormUrlEncoded_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FieldMapWithoutFormUrlEncoded", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.FieldMap;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface FieldMapWithoutFormUrlEncoded {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  String createItem(@FieldMap Map<String, String> fields);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a @FieldMap parameter but is not annotated with @FormUrlEncoded");
	}

	@Test
	void completableFutureOfGenericCollection_fallsBackToReflectiveProxyInsteadOfFailingCompilation()
			throws IOException {
		// CompletableFuture<List<T>> is a fully supported, decodable return shape as
		// of E12 (ResponseDecoder/RuntimeGenericType) - ReflectiveRestClientValidator
		// was relaxed to accept it, and this validator must agree instead of still
		// treating it as an error: the method just isn't codegen-eligible (no single
		// Class<?> for RestClientProcessor to emit - see nonAsyncReturnModelOf's own
		// comment) and falls back to the reflective proxy, the same as a bare,
		// unwrapped List<String> return type already does (see
		// interfaceMixingAnUnsupportedListReturnWithASupportedMethod_generatesAnImplementationForBoth).
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompletableFutureOfGenericCollection", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import java.util.List;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface CompletableFutureOfGenericCollection {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  CompletableFuture<List<String>> listItems();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("CompletableFutureOfGenericCollection_RipImpl.class")),
				"Expected CompletableFutureOfGenericCollection_RipImpl.class to be generated, found: "
						+ list(outputDir));
	}

	@Test
	void multipleUrlParams_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipleUrlParams", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Url;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipleUrlParams {\n" //
				+ "  @GET\n" //
				+ "  String getItem(@Url String a, @Url String b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one parameter annotated with @Url");
	}

	@Test
	void urlParamWithStaticUrl_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UrlParamWithStaticUrl", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Url;\n" //
				+ "@RestClient\n" //
				+ "public interface UrlParamWithStaticUrl {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem(@Url String url);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has both a @Url parameter and a static URL");
	}

	@Test
	void invalidUrlSyntax_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("InvalidUrlSyntax", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "public interface InvalidUrlSyntax {\n" //
				+ "  @GET(\"http://localhost/items with space\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has an invalid URL");
	}

	@Test
	void multipleDestinationParams_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipleDestinationParams", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Destination;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipleDestinationParams {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  File getItem(@Destination File a, @Destination File b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one parameter annotated with @Destination");
	}

	@Test
	void destinationParamWrongType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("DestinationParamWrongType", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Destination;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface DestinationParamWrongType {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  File getItem(@Destination String notFile);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "only File is supported");
	}

	@Test
	void fileReturnWithoutDestination_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FileReturnWithoutDestination", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface FileReturnWithoutDestination {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  File getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns File but has no @Destination parameter to write the response to");
	}

	@Test
	void multipleDownloadProgressListeners_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipleDownloadProgressListeners", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.download.DownloadProgressListener;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipleDownloadProgressListeners {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  byte[] getItem(DownloadProgressListener a, DownloadProgressListener b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one DownloadProgressListener parameter");
	}

	@Test
	void multipleUploadProgressListeners_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipleUploadProgressListeners", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "import com.shri.restinpeace.annotation.request.Part;\n" //
				+ "import com.shri.restinpeace.upload.UploadProgressListener;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipleUploadProgressListeners {\n" //
				+ "  @POST(\"http://localhost/items\")\n" //
				+ "  @Multipart\n" //
				+ "  String createItem(@Part(\"f\") String f, UploadProgressListener a, UploadProgressListener b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one UploadProgressListener parameter");
	}

	@Test
	void staticMethodWithBody_compilesCleanAndIgnoresStaticMethod() throws IOException {
		// Mirrors interfaceWithDefaultMethod_compilesCleanAndStillGeneratesAnImplementation
		// above but for the other exempt-from-every-check modifier: a static interface
		// method isn't part of the implementing contract either (never called through
		// an instance), so both CompileTimeRestClientValidator and RestClientProcessor
		// skip it entirely rather than trying to validate or generate an override for it.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ApiWithStaticMethod", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface ApiWithStaticMethod {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  static String helper() { return \"hi\"; }\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ApiWithStaticMethod_RipImpl.class")),
				"Expected ApiWithStaticMethod_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void interfaceConstantField_isIgnoredDuringValidationAndCodegen() throws IOException {
		// A @RestClient interface's enclosed elements aren't only methods - a
		// constant field is legal interface syntax too - so both
		// CompileTimeRestClientValidator's own validate() loop and
		// RestClientProcessor's own method-collection loop must skip a non-METHOD
		// enclosed element rather than fail casting it to ExecutableElement.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ApiWithConstant", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface ApiWithConstant {\n" //
				+ "  String DEFAULT_ID = \"42\";\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ApiWithConstant_RipImpl.class")),
				"Expected ApiWithConstant_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void allHttpVerbsAndCommonParamKinds_generatesImplementation() throws IOException {
		// The rest of this suite only ever uses @GET/@POST - exercising
		// @PUT/@PATCH/@DELETE/@HEAD/@OPTIONS here too, alongside @Body,
		// @HeaderParam, and @QueryParam (none of which appear elsewhere in this
		// suite either), so RestClientProcessor's own per-verb/per-param-kind
		// codegen branches all actually run at least once.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AllVerbsApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.DELETE;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.method.HEAD;\n" //
				+ "import com.shri.restinpeace.annotation.method.OPTIONS;\n" //
				+ "import com.shri.restinpeace.annotation.method.PATCH;\n" //
				+ "import com.shri.restinpeace.annotation.method.PUT;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import com.shri.restinpeace.annotation.request.HeaderParam;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface AllVerbsApi {\n" //
				+ "  @PUT(\"http://localhost/items/{id}\")\n" //
				+ "  String putItem(@PathParam(\"id\") String id, @Body String body);\n" //
				+ "  @PATCH(\"http://localhost/items/{id}\")\n" //
				+ "  void patchItem(@PathParam(\"id\") String id, @HeaderParam(\"X-Trace\") String trace);\n" //
				+ "  @DELETE(\"http://localhost/items/{id}\")\n" //
				+ "  void deleteItem(@PathParam(\"id\") String id);\n" //
				+ "  @HEAD(\"http://localhost/items/{id}\")\n" //
				+ "  void headItem(@PathParam(\"id\") String id);\n" //
				+ "  @OPTIONS(\"http://localhost/items\")\n" //
				+ "  void optionsItems();\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String searchItems(@QueryParam(\"q\") String query);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("AllVerbsApi_RipImpl.class")),
				"Expected AllVerbsApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void interfaceLevelTimeoutDefault_appliesToMethodWithoutOwnTimeout() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("TimeoutDefaultApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.timeout.Timeout;\n" //
				+ "@RestClient\n" //
				+ "@Timeout(connectMillis = 1000, readMillis = 2000)\n" //
				+ "public interface TimeoutDefaultApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("TimeoutDefaultApi_RipImpl.class")),
				"Expected TimeoutDefaultApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void errorTypeAnnotation_resolvesClassNameAndGeneratesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ErrorTypeApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.error.ErrorType;\n" //
				+ "@RestClient\n" //
				+ "public interface ErrorTypeApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @ErrorType(String.class)\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ErrorTypeApi_RipImpl.class")),
				"Expected ErrorTypeApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void queryMapAndHeaderMapParams_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MapParamsApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.HeaderMap;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryMap;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface MapParamsApi {\n" //
				+ "  @GET(\"http://localhost/search\")\n" //
				+ "  String search(@QueryMap Map<String, String> filters, @HeaderMap Map<String, String> headers);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("MapParamsApi_RipImpl.class")),
				"Expected MapParamsApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void multipartWithPartMapAndUploadProgress_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipartUploadApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "import com.shri.restinpeace.annotation.request.PartMap;\n" //
				+ "import com.shri.restinpeace.upload.UploadProgressListener;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipartUploadApi {\n" //
				+ "  @POST(\"http://localhost/upload\")\n" //
				+ "  @Multipart\n" //
				+ "  String upload(@PartMap Map<String, Object> parts, UploadProgressListener listener);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("MultipartUploadApi_RipImpl.class")),
				"Expected MultipartUploadApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void multipartWithValidPart_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("MultipartPartApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.Multipart;\n" //
				+ "import com.shri.restinpeace.annotation.request.Part;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface MultipartPartApi {\n" //
				+ "  @POST(\"http://localhost/items/{id}/avatar\")\n" //
				+ "  @Multipart\n" //
				+ "  String uploadAvatar(@PathParam(\"id\") String id, @Part(\"file\") File avatar);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("MultipartPartApi_RipImpl.class")),
				"Expected MultipartPartApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void formUrlEncodedWithFieldMap_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FormFieldMapApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.request.FieldMap;\n" //
				+ "import com.shri.restinpeace.annotation.request.FormUrlEncoded;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface FormFieldMapApi {\n" //
				+ "  @POST(\"http://localhost/form\")\n" //
				+ "  @FormUrlEncoded\n" //
				+ "  String submit(@FieldMap Map<String, String> fields);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("FormFieldMapApi_RipImpl.class")),
				"Expected FormFieldMapApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void urlParam_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UrlParamApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Url;\n" //
				+ "@RestClient\n" //
				+ "public interface UrlParamApi {\n" //
				+ "  @GET\n" //
				+ "  String nextPage(@Url String url);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("UrlParamApi_RipImpl.class")),
				"Expected UrlParamApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void headersWithMultipleEntriesAndIdempotentRetryWithStatusCodes_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("HeadersRetryApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Headers;\n" //
				+ "import com.shri.restinpeace.annotation.retry.Retry;\n" //
				+ "@RestClient\n" //
				+ "public interface HeadersRetryApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @Headers({ \"X-A: 1\", \"X-B: 2\" })\n" //
				+ "  @Retry(times = 2, retryOnStatus = { 500, 502 }, idempotent = true)\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("HeadersRetryApi_RipImpl.class")),
				"Expected HeadersRetryApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void byteArrayReturnWithDownloadProgressListener_fallsBackToReflectiveProxy() throws IOException {
		// byte[] is a downloadable return type (returnsDownloadableBody), so this
		// passes CompileTimeRestClientValidator - but RestClientProcessor's own
		// codegen only wires a DownloadProgressListener parameter through for a
		// FILE-kind return, so download() here falls back to the reflective
		// proxy (see toSupportedMethodModel's isDownloadOnlyKind check) even
		// though getItem() on the same interface still gets a real generated
		// implementation - also the first fallback method in this suite to take
		// a parameter, exercising appendFallbackMethod's parameter-forwarding loop.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ByteArrayDownloadApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import com.shri.restinpeace.download.DownloadProgressListener;\n" //
				+ "@RestClient\n" //
				+ "public interface ByteArrayDownloadApi {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  @GET(\"http://localhost/download\")\n" //
				+ "  byte[] download(DownloadProgressListener listener);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("ByteArrayDownloadApi_RipImpl.class")),
				"Expected ByteArrayDownloadApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void asyncByteArrayReturnWithDownloadProgressListener_fallsBackToReflectiveProxy() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AsyncByteArrayDownloadApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import com.shri.restinpeace.download.DownloadProgressListener;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface AsyncByteArrayDownloadApi {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  @GET(\"http://localhost/download\")\n" //
				+ "  CompletableFuture<byte[]> download(DownloadProgressListener listener);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("AsyncByteArrayDownloadApi_RipImpl.class")),
				"Expected AsyncByteArrayDownloadApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void primitiveIntReturnType_fallsBackToReflectiveProxy() throws IOException {
		// int isn't DECLARED, so it hits both validateReturnType's and
		// nonAsyncReturnModelOf's early "not a type we special-case/support"
		// returns - a plain unsupported-primitive fallback, distinct from the
		// generic-type-argument fallback ApiWithPartialSupport already covers.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PrimitiveReturnApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PrimitiveReturnApi {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  @GET(\"http://localhost/count\")\n" //
				+ "  int count();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("PrimitiveReturnApi_RipImpl.class")),
				"Expected PrimitiveReturnApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void completableFutureOfNestedRipResponse_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AsyncRipResponseApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface AsyncRipResponseApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  CompletableFuture<RipResponse<String>> getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("AsyncRipResponseApi_RipImpl.class")),
				"Expected AsyncRipResponseApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void ripResponseByteArraySync_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("RipResponseBytesApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "@RestClient\n" //
				+ "public interface RipResponseBytesApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  RipResponse<byte[]> getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("RipResponseBytesApi_RipImpl.class")),
				"Expected RipResponseBytesApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void ripResponseByteArrayAsync_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AsyncRipResponseBytesApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface AsyncRipResponseBytesApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  CompletableFuture<RipResponse<byte[]>> getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("AsyncRipResponseBytesApi_RipImpl.class")),
				"Expected AsyncRipResponseBytesApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void fileReturnWithDestination_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FileDownloadApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Destination;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface FileDownloadApi {\n" //
				+ "  @GET(\"http://localhost/reports/{id}\")\n" //
				+ "  File download(@PathParam(\"id\") String id, @Destination File destination);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("FileDownloadApi_RipImpl.class")),
				"Expected FileDownloadApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void asyncFileReturnWithDestination_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AsyncFileDownloadApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Destination;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import java.io.File;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface AsyncFileDownloadApi {\n" //
				+ "  @GET(\"http://localhost/reports/{id}\")\n" //
				+ "  CompletableFuture<File> download(@PathParam(\"id\") String id, @Destination File destination);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("AsyncFileDownloadApi_RipImpl.class")),
				"Expected AsyncFileDownloadApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void byteArrayReturn_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("BytesApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface BytesApi {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  byte[] download(@PathParam(\"id\") String id);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("BytesApi_RipImpl.class")),
				"Expected BytesApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void asyncPlainStringReturn_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AsyncPlainApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface AsyncPlainApi {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  CompletableFuture<String> getItem(@PathParam(\"id\") String id);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("AsyncPlainApi_RipImpl.class")),
				"Expected AsyncPlainApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void nestedInterface_isIgnoredEntirely() throws IOException {
		// processRestClient's very first check - before even
		// CompileTimeRestClientValidator.validate() runs - is that the
		// interface's enclosing element is a package, not another type. A
		// nested interface (even a well-formed one) is a separate, structural
		// precondition unrelated to any one method's shape and isn't
		// processed at all: no validation, no codegen, no error.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("OuterWithNestedApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "public class OuterWithNestedApi {\n" //
				+ "  @RestClient\n" //
				+ "  public interface Inner {\n" //
				+ "    @GET(\"http://localhost/items\")\n" //
				+ "    String getItem();\n" //
				+ "  }\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("Inner_RipImpl.class")),
				"Expected no Inner_RipImpl.class to be generated for a nested interface, found: " + list(outputDir));
	}

	@Test
	void interfaceWithOnlyUnsupportedMethod_generatesNoImplementation() throws IOException {
		// A single method outside the codegen-supported shape no longer
		// disqualifies an interface that also has a supported method (see
		// interfaceMixingAnUnsupportedListReturnWithASupportedMethod above) -
		// but an interface with NO codegen-eligible method at all still isn't
		// generated for; the plain reflective proxy already covers that case.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("OnlyUnsupportedApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import java.util.List;\n" //
				+ "@RestClient\n" //
				+ "public interface OnlyUnsupportedApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  List<String> listItems();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("OnlyUnsupportedApi_RipImpl.class")),
				"Expected no OnlyUnsupportedApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void unannotatedParameter_fallsBackToReflectiveProxy() throws IOException {
		// CompileTimeRestClientValidator has no rule requiring every parameter
		// to carry a recognized annotation - only RestClientProcessor's own
		// toSupportedParamModel does (countNonNull(...) != 1), so a plain
		// unannotated parameter (not UploadProgressListener/
		// DownloadProgressListener either) passes validation cleanly but
		// still falls the whole method back to the reflective proxy.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("UnannotatedParamApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "@RestClient\n" //
				+ "public interface UnannotatedParamApi {\n" //
				+ "  @GET(\"http://localhost/items/{id}\")\n" //
				+ "  String getItem(@PathParam(\"id\") String id);\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String search(String unannotated);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("UnannotatedParamApi_RipImpl.class")),
				"Expected UnannotatedParamApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void noCacheAnnotation_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("NoCacheApi", "" //
				+ "import com.shri.restinpeace.annotation.cache.NoCache;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "public interface NoCacheApi {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  @NoCache\n" //
				+ "  String getItem();\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("NoCacheApi_RipImpl.class")),
				"Expected NoCacheApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void fileReturnWithDestinationAndDownloadProgressListener_generatesImplementation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FileDownloadWithProgressApi", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.Destination;\n" //
				+ "import com.shri.restinpeace.annotation.request.PathParam;\n" //
				+ "import com.shri.restinpeace.download.DownloadProgressListener;\n" //
				+ "import java.io.File;\n" //
				+ "@RestClient\n" //
				+ "public interface FileDownloadWithProgressApi {\n" //
				+ "  @GET(\"http://localhost/reports/{id}\")\n" //
				+ "  File download(@PathParam(\"id\") String id, @Destination File destination, "
				+ "DownloadProgressListener listener);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(outputDir.resolve("FileDownloadWithProgressApi_RipImpl.class")),
				"Expected FileDownloadWithProgressApi_RipImpl.class to be generated, found: " + list(outputDir));
	}

	@Test
	void completableFutureOfNonByteArray_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompletableFutureOfNonByteArray", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "@RestClient\n" //
				+ "public interface CompletableFutureOfNonByteArray {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  CompletableFuture<int[]> getItem();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "which is not a supported type parameter");
	}

	@Test
	void queryMapOnPrimitiveType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("QueryMapOnPrimitiveType", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryMap;\n" //
				+ "@RestClient\n" //
				+ "public interface QueryMapOnPrimitiveType {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem(@QueryMap int notAMap);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has a parameter annotated with @QueryMap that is not a Map");
	}

	@Test
	void paginatedInterface_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedApi", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedApi {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		// Page<T> has a type argument RestClientProcessor doesn't recognize - the
		// same E9 disqualification a raw List<User> return type already gets - so
		// this compiles clean but generates no _RipImpl (the method falls all the
		// way back to the reflective proxy at runtime).
		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("PaginatedApi_RipImpl.class")),
				"Expected no _RipImpl to be generated for an interface with only a @Paginated method, found: "
						+ list(outputDir));
	}

	@Test
	void paginatedMethodNotReturningPage_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedWrongReturnType", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedWrongReturnType {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  String listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		// No longer a compile error (chunk 4 of docs/design/reactor-call-adapter.md,
		// §7.2): a @Paginated return type this processor doesn't recognize might
		// still be legitimately claimed by a runtime-registered
		// PaginatedCallAdapterFactory (e.g. rest-in-peace-reactor's Flux<T>
		// pagination flavor) - registration is a plain method call this processor has
		// no way to see, so it can no longer treat "unrecognized" as "definitely
		// wrong" the way it safely could before that SPI existed.
		// ReflectiveRestClientValidatorPaginationTest's own
		// validate_paginatedNotReturningPage_throwsWithError still enforces this
		// at RIP.getClient(...) time when nothing actually claims it.
		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("PaginatedWrongReturnType_RipImpl.class")),
				"Expected no _RipImpl to be generated for a @Paginated method returning an unrecognized type, found: "
						+ list(outputDir));
	}

	@Test
	void paginatedValuePointerFieldEmpty_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ValuePointerFieldEmpty", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface ValuePointerFieldEmpty {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "must set pointerField");
	}

	@Test
	void paginatedVoidReturn_failsCompilation() throws IOException {
		// void's TypeMirror.getKind() is VOID, not DECLARED - exercises the
		// isDeclared == false branch of the raw-RipResponse/CompletableFuture and
		// RipResponse-wrapping-Stream/Iterator guards, which String (a declared
		// type) can't reach.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedVoidReturn", "" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedVoidReturn {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  void listOrders();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "does not return Page<T>, Stream<T>, or Iterator<T>");
	}

	@Test
	void pageReturnWithoutPaginated_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PageWithoutPaginated", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "public interface PageWithoutPaginated {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  Page<String> listOrders();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns Page<T> but is not annotated with @Paginated");
	}

	@Test
	void rawPageReturn_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("RawPageReturn", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PointerKind;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "@RestClient\n" //
				+ "@SuppressWarnings(\"rawtypes\")\n" //
				+ "public interface RawPageReturn {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerKind = PointerKind.FULL_URL, pointerField = \"next\")\n" //
				+ "  Page listOrders();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns a raw Page with no type parameter");
	}

	@Test
	void paginatedWithUrlParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedWithUrl", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PointerKind;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Url;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedWithUrl {\n" //
				+ "  @GET\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerKind = PointerKind.FULL_URL, pointerField = \"next\")\n" //
				+ "  Page<String> listOrders(@Url String url);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "is annotated with both @Paginated and @Url");
	}

	@Test
	void fullUrlPointerWithCursorParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("FullUrlWithCursorParam", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PointerKind;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface FullUrlWithCursorParam {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerKind = PointerKind.FULL_URL, pointerField = \"next\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "pointerKind = FULL_URL but also has a @PaginationCursor parameter");
	}

	@Test
	void valuePointerWithNoCursorParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ValuePointerNoCursorParam", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "@RestClient\n" //
				+ "public interface ValuePointerNoCursorParam {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "found 0");
	}

	@Test
	void valuePointerWithTwoCursorParams_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ValuePointerTwoCursorParams", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface ValuePointerTwoCursorParams {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"a\") @PaginationCursor String a,\n" //
				+ "      @QueryParam(\"b\") @PaginationCursor String b);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "found 2");
	}

	@Test
	void pointerSourceNone_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PointerSourceNone", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PointerSourceNone {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.NONE)\n" //
				+ "  Page<String> listOrders(@QueryParam(\"offset\") @PaginationCursor int offset);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "pointerSource = NONE, which needs advance() to be set");
	}

	@Test
	void pointerSourceItemField_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PointerSourceItemField", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PointerSourceItemField {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.ITEM_FIELD, pointerField = \"id\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"since\") @PaginationCursor String since);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("PointerSourceItemField_RipImpl.class")),
				"Expected no _RipImpl to be generated for an interface with only a @Paginated method, found: "
						+ list(outputDir));
	}

	@Test
	void compositeItemFieldTwoParams_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompositeItemFieldTwoParams", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface CompositeItemFieldTwoParams {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.ITEM_FIELD, "
				+ "pointerField = \"id,createdAt\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"lastId\") @PaginationCursor String lastId,\n" //
				+ "      @QueryParam(\"lastTs\") @PaginationCursor String lastTimestamp);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void compositeItemFieldWrongParamCount_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompositeItemFieldWrongParamCount", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface CompositeItemFieldWrongParamCount {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.ITEM_FIELD, "
				+ "pointerField = \"id,createdAt\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"lastId\") @PaginationCursor String lastId);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"needs 2 @PaginationCursor parameter(s) (matching pointerField's 2 comma-separated entries) - "
						+ "found 1");
	}

	@Test
	void compositeItemFieldIntoBody_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompositeItemFieldIntoBody", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface CompositeItemFieldIntoBody {\n" //
				+ "  @POST(\"http://localhost/orders/search\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.ITEM_FIELD, "
				+ "pointerField = \"id,createdAt\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"lastId,lastTimestamp\") "
				+ "Map<String,Object> body);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void compositeItemFieldIntoBodyWrongCount_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompositeItemFieldIntoBodyWrongCount", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface CompositeItemFieldIntoBodyWrongCount {\n" //
				+ "  @POST(\"http://localhost/orders/search\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.ITEM_FIELD, "
				+ "pointerField = \"id,createdAt,tenantId\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"lastId,lastTimestamp\") "
				+ "Map<String,Object> body);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"pointerField naming 3 value(s) but its @Body @PaginationCursor's bodyField names 2");
	}

	@Test
	void bodyCursorAlongsideAnotherCursorParam_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("BodyCursorAlongsideAnotherCursorParam", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface BodyCursorAlongsideAnotherCursorParam {\n" //
				+ "  @POST(\"http://localhost/orders/search\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.ITEM_FIELD, "
				+ "pointerField = \"id,createdAt\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"lastId\") @PaginationCursor String lastId,\n" //
				+ "      @Body @PaginationCursor(bodyField = \"lastTimestamp\") Map<String,Object> body);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "must be the method's only @PaginationCursor parameter");
	}

	@Test
	void paginatedAdvanceSetWithPointerSourceNotNone_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AdvanceSet", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationAdvance;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface AdvanceSet {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\", advance = PaginationAdvance.INCREMENT_BY_ONE)\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "sets advance() but pointerSource is not NONE");
	}

	@Test
	void paginatedAdvanceIncrementByPageSize_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AdvanceIncrementByPageSize", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationAdvance;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface AdvanceIncrementByPageSize {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.NONE, "
				+ "advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE, pageSize = 50, totalField = \"total\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"offset\") @PaginationCursor int offset);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void paginatedAdvanceIncrementByOne_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AdvanceIncrementByOne", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationAdvance;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface AdvanceIncrementByOne {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.NONE, "
				+ "advance = PaginationAdvance.INCREMENT_BY_ONE)\n" //
				+ "  Page<String> listOrders(@QueryParam(\"page\") @PaginationCursor int page);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void paginatedAdvanceIntoBody_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AdvanceIntoBody", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationAdvance;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface AdvanceIntoBody {\n" //
				+ "  @POST(\"http://localhost/orders/search\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.NONE, "
				+ "advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE, pageSize = 50)\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"offset\") "
				+ "Map<String,Object> body);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void paginatedAdvanceIncrementByPageSizeMissingPageSize_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AdvanceIncrementByPageSizeMissingPageSize",
				"" //
						+ "import com.shri.restinpeace.Page;\n" //
						+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
						+ "import com.shri.restinpeace.annotation.method.GET;\n" //
						+ "import com.shri.restinpeace.annotation.pagination.PaginationAdvance;\n" //
						+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
						+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
						+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
						+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
						+ "@RestClient\n" //
						+ "public interface AdvanceIncrementByPageSizeMissingPageSize {\n" //
						+ "  @GET(\"http://localhost/orders\")\n" //
						+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.NONE, "
						+ "advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE)\n" //
						+ "  Page<String> listOrders(@QueryParam(\"offset\") @PaginationCursor int offset);\n" //
						+ "}\n");

		assertErrorContains(diagnostics, "needs pageSize() to be a positive number");
	}

	@Test
	void paginatedAdvanceWithTwoCursorParams_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("AdvanceWithTwoCursorParams", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationAdvance;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.HeaderParam;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface AdvanceWithTwoCursorParams {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerSource = PaginationSignalSource.NONE, "
				+ "advance = PaginationAdvance.INCREMENT_BY_ONE)\n" //
				+ "  Page<String> listOrders(@QueryParam(\"page\") @PaginationCursor int page,\n" //
				+ "      @HeaderParam(\"X-Page\") @PaginationCursor int pageHeader);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"needs exactly one @PaginationCursor parameter to carry the client-computed offset/page value - "
						+ "found 2");
	}

	@Test
	void strategyPage_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("StrategyPage", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.PaginationStrategy;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface StrategyPage {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"status\") String status, "
				+ "PaginationStrategy<String> strategy);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("StrategyPage_RipImpl.class")),
				"Expected no _RipImpl to be generated for a PaginationStrategy method, found: " + list(outputDir));
	}

	@Test
	void strategyAndPaginatedTogether_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("StrategyAndPaginatedTogether", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.PaginationStrategy;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface StrategyAndPaginatedTogether {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor,\n" //
				+ "      PaginationStrategy<String> strategy);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"is annotated with @Paginated and also has a PaginationStrategy<T> parameter");
	}

	@Test
	void twoStrategyParams_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("TwoStrategyParams", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.PaginationStrategy;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "public interface TwoStrategyParams {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  Page<String> listOrders(PaginationStrategy<String> first, PaginationStrategy<String> second);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "has more than one PaginationStrategy<T> parameter");
	}

	@Test
	void strategyItemTypeMismatch_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("StrategyItemTypeMismatch", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.PaginationStrategy;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "public interface StrategyItemTypeMismatch {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  Page<String> listOrders(PaginationStrategy<Integer> strategy);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "parameter doesn't match its Page<java.lang.String> return type");
	}

	@Test
	void strategyNotReturningPage_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("StrategyNotReturningPage", "" //
				+ "import com.shri.restinpeace.PaginationStrategy;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import java.util.List;\n" //
				+ "@RestClient\n" //
				+ "public interface StrategyNotReturningPage {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  List<String> listOrders(PaginationStrategy<String> strategy);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "does not return Page<T>, Stream<T>, or Iterator<T>");
	}

	@Test
	void rawPageWithStrategy_failsCompilationWithRawTypeErrorOnly() throws IOException {
		// A raw Page return type is already flagged generically - the item-type-match
		// check must not also crash (or add a second error) when there's no return
		// type argument to compare the strategy's own type argument against.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("RawPageWithStrategy", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.PaginationStrategy;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "@SuppressWarnings(\"rawtypes\")\n" //
				+ "public interface RawPageWithStrategy {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  Page listOrders(PaginationStrategy<String> strategy);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns a raw Page with no type parameter");
		assertFalse(errorMessages(diagnostics).stream().anyMatch(message -> message.contains("doesn't match")));
	}

	@Test
	void rawStrategyParam_compilesCleanAndFallsBackReflectively() throws IOException {
		// A raw PaginationStrategy parameter (no type argument) has nothing to compare
		// against the method's own Page<String> return type - skipped, not an error.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("RawStrategyParam", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.PaginationStrategy;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "@SuppressWarnings(\"rawtypes\")\n" //
				+ "public interface RawStrategyParam {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  Page<String> listOrders(PaginationStrategy strategy);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void paginationCursorOnBody_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CursorOnBody", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface CursorOnBody {\n" //
				+ "  @POST(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"cursor\") Map<String,Object> body);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("CursorOnBody_RipImpl.class")),
				"Expected no _RipImpl to be generated for an interface with only a @Paginated method, found: "
						+ list(outputDir));
	}

	@Test
	void paginationCursorOnBodyMissingBodyField_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CursorOnBodyMissingBodyField", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface CursorOnBodyMissingBodyField {\n" //
				+ "  @POST(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor Map<String,Object> body);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "stacked on @Body but bodyField is empty");
	}

	@Test
	void paginationCursorOnBodyCompositeBodyField_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CursorOnBodyCompositeBodyField", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface CursorOnBodyCompositeBodyField {\n" //
				+ "  @POST(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"lastId,lastTimestamp\") "
				+ "Map<String,Object> body);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"pointerField naming 1 value(s) but its @Body @PaginationCursor's bodyField names 2");
	}

	@Test
	void paginationCursorOnBodyWrongMapType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CursorOnBodyWrongMapType", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@RestClient\n" //
				+ "public interface CursorOnBodyWrongMapType {\n" //
				+ "  @POST(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"cursor\") Map<String,String> body);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "declared type is not Map<String,Object>");
	}

	@Test
	void paginationCursorOnBodyRawMapType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CursorOnBodyRawMapType", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "import java.util.Map;\n" //
				+ "@SuppressWarnings(\"rawtypes\")\n" //
				+ "@RestClient\n" //
				+ "public interface CursorOnBodyRawMapType {\n" //
				+ "  @POST(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"cursor\") Map body);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "declared type is not Map<String,Object>");
	}

	@Test
	void paginationCursorOnBodyNonMapType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CursorOnBodyNonMapType", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.POST;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.Body;\n" //
				+ "@RestClient\n" //
				+ "public interface CursorOnBodyNonMapType {\n" //
				+ "  @POST(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@Body @PaginationCursor(bodyField = \"cursor\") String body);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "declared type is not Map<String,Object>");
	}

	@Test
	void paginationCursorBodyFieldWithoutBodyCarrier_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("BodyFieldWithoutBodyCarrier", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface BodyFieldWithoutBodyCarrier {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor(bodyField = \"cursor\") "
				+ "String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "non-empty bodyField but is not stacked on @Body");
	}

	@Test
	void bareParaginationCursor_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("BareCursorParam", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "@RestClient\n" //
				+ "public interface BareCursorParam {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "must be stacked on exactly one of @QueryParam/@PathParam/@HeaderParam/@Body");
	}

	@Test
	void paginationCursorWrongType_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CursorParamWrongType", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface CursorParamWrongType {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor double cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "only String, int, and long are supported");
	}

	@Test
	void hasMoreFieldMissing_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("HasMoreFieldMissing", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface HasMoreFieldMissing {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\", hasMoreSource = PaginationSignalSource.RESPONSE_BODY)\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "must set hasMoreField");
	}

	@Test
	void hasMoreSourceItemField_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("HasMoreSourceItemField", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface HasMoreSourceItemField {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\", hasMoreSource = PaginationSignalSource.ITEM_FIELD, hasMoreField = \"done\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "hasMoreSource = ITEM_FIELD, which is only meaningful for pointerSource");
	}

	@Test
	void compositePointerField_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("CompositePointerField", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface CompositePointerField {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"id,createdAt\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "composite pointer is only supported for pointerSource = ITEM_FIELD");
	}

	@Test
	void paginatedWithHasMoreAndTotalSignals_compilesClean() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedFullSignals", "" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedFullSignals {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next_cursor\",\n" //
				+ "      hasMoreSource = PaginationSignalSource.RESPONSE_BODY, hasMoreField = \"has_more\",\n" //
				+ "      totalSource = PaginationSignalSource.RESPONSE_HEADER, totalField = \"X-Total-Count\")\n" //
				+ "  Page<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
	}

	@Test
	void paginatedStreamReturn_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedStreamApi", "" //
				+ "import java.util.stream.Stream;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedStreamApi {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  Stream<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("PaginatedStreamApi_RipImpl.class")),
				"Expected no _RipImpl to be generated for an interface with only a @Paginated method, found: "
						+ list(outputDir));
	}

	@Test
	void paginatedRipResponseWrappingIterator_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedRipResponseIterator", "" //
				+ "import java.util.Iterator;\n" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedRipResponseIterator {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  RipResponse<Iterator<String>> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns RipResponse<Iterator<T>>, which is not supported");
	}

	@Test
	void paginatedRipResponseWrappingStream_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedRipResponseStream", "" //
				+ "import java.util.stream.Stream;\n" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedRipResponseStream {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  RipResponse<Stream<String>> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns RipResponse<Stream<T>>, which is not supported");
	}

	@Test
	void paginatedRipResponseWrappingNonStreamType_failsWithGenericError() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedRipResponseNonStream", "" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedRipResponseNonStream {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  RipResponse<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "does not return Page<T>, Stream<T>, or Iterator<T>");
	}

	@Test
	void paginatedRipResponseWrappingArrayType_failsWithGenericError() throws IOException {
		// RipResponse<byte[]>'s type argument isn't a DECLARED type (it's an
		// array), so streamOrIteratorInnerName's remaining guard rejects it
		// before comparing raw type names - falls through to the generic
		// "does not return Page/Stream/Iterator" message.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedRipResponseArray", "" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedRipResponseArray {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  RipResponse<byte[]> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "does not return Page<T>, Stream<T>, or Iterator<T>");
	}

	@Test
	void paginatedRawRipResponseReturn_failsWithTheExistingRawTypeError() throws IOException {
		// validateReturnType already flags a raw RipResponse regardless of
		// @Paginated - the pagination-specific checks add nothing further for
		// this exact case, avoiding a second, overlapping message.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedRawRipResponse", "" //
				+ "import com.shri.restinpeace.RipResponse;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@SuppressWarnings(\"rawtypes\")\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedRawRipResponse {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  RipResponse listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns a raw RipResponse with no type parameter");
	}

	@Test
	void paginatedRawCompletableFutureReturn_failsWithTheExistingRawTypeError() throws IOException {
		// Same overlap-avoidance as the raw RipResponse case above, but for the
		// other raw-generic return type the early-return guard covers.
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedRawCompletableFuture", "" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@SuppressWarnings(\"rawtypes\")\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedRawCompletableFuture {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  CompletableFuture listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns a raw CompletableFuture with no type parameter");
	}

	@Test
	void paginatedCompletableFutureOfPageReturn_failsCompilation() throws IOException {
		// CompletableFuture<Page<T>> - a genuinely parameterized (non-raw)
		// CompletableFuture, so the raw-type early-return above doesn't apply -
		// exercises the chunk-4 loosening's own CompletableFuture<T> exclusion
		// (§7.2 of docs/design/reactor-call-adapter.md): still an unconditional
		// hard error, since an async first fetch remains not implemented (the
		// class's own javadoc already calls this exact shape out by name).
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedCompletableFutureOfPage", "" //
				+ "import java.util.concurrent.CompletableFuture;\n" //
				+ "import com.shri.restinpeace.Page;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedCompletableFutureOfPage {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  CompletableFuture<Page<String>> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "does not return Page<T>, Stream<T>, or Iterator<T>");
	}

	@Test
	void paginatedIteratorReturn_compilesCleanAndFallsBackReflectively() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("PaginatedIteratorApi", "" //
				+ "import java.util.Iterator;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@RestClient\n" //
				+ "public interface PaginatedIteratorApi {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  Iterator<String> listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertNoErrors(diagnostics);
		assertFalse(Files.exists(outputDir.resolve("PaginatedIteratorApi_RipImpl.class")),
				"Expected no _RipImpl to be generated for an interface with only a @Paginated method, found: "
						+ list(outputDir));
	}

	@Test
	void rawStreamReturn_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("RawStreamReturn", "" //
				+ "import java.util.stream.Stream;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.PaginationCursor;\n" //
				+ "import com.shri.restinpeace.annotation.pagination.Paginated;\n" //
				+ "import com.shri.restinpeace.annotation.request.QueryParam;\n" //
				+ "@SuppressWarnings(\"rawtypes\")\n" //
				+ "@RestClient\n" //
				+ "public interface RawStreamReturn {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  @Paginated(itemsField = \"orders\", pointerField = \"next\")\n" //
				+ "  Stream listOrders(@QueryParam(\"cursor\") @PaginationCursor String cursor);\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns a raw Stream with no type parameter");
	}

	@Test
	void returnsStreamWithoutPaginated_failsCompilation() throws IOException {
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("ReturnsStreamWithoutPaginated", "" //
				+ "import java.util.stream.Stream;\n" //
				+ "import com.shri.restinpeace.annotation.marker.RestClient;\n" //
				+ "import com.shri.restinpeace.annotation.method.GET;\n" //
				+ "@RestClient\n" //
				+ "public interface ReturnsStreamWithoutPaginated {\n" //
				+ "  @GET(\"http://localhost/orders\")\n" //
				+ "  Stream<String> listOrders();\n" //
				+ "}\n");

		assertErrorContains(diagnostics, "returns Stream<T> but is not annotated with @Paginated");
	}

	private List<Diagnostic<? extends JavaFileObject>> compile(String className, String source) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		JavaFileObject sourceFile = new SimpleJavaFileObject(URI.create("string:///" + className + ".java"),
				JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return source;
			}
		};

		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null,
				StandardCharsets.UTF_8)) {
			fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(outputDir.toFile()));
			List<String> options = Arrays.asList("-classpath", System.getProperty("java.class.path"));
			JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
					Collections.singletonList(sourceFile));
			task.call();
		}
		return diagnostics.getDiagnostics();
	}

	private static void assertNoErrors(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
		List<String> errors = errorMessages(diagnostics);
		assertTrue(errors.isEmpty(), "Expected no compile errors, got: " + errors);
	}

	private static void assertErrorContains(List<Diagnostic<? extends JavaFileObject>> diagnostics,
			String expectedSubstring) {
		List<String> errors = errorMessages(diagnostics);
		assertFalse(errors.isEmpty(), "Expected a compile error containing '" + expectedSubstring + "', got none");
		assertTrue(errors.stream().anyMatch(message -> message.contains(expectedSubstring)),
				"Expected a compile error containing '" + expectedSubstring + "', got: " + errors);
	}

	private static List<String> errorMessages(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
		return diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
				.map(d -> d.getMessage(null)).collect(Collectors.toList());
	}

	private static List<String> list(Path dir) throws IOException {
		try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
			return paths.map(Path::toString).collect(Collectors.toList());
		}
	}

}
