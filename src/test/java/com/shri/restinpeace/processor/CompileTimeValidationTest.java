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
 * interface (the same rules {@code RestClientValidatorTest} already covers
 * at runtime) fails <b>compilation</b> with a matching error message via
 * {@link CompileTimeValidator}, and that a valid one compiles clean and
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
				+ "  @GET(com.shri.restinpeace.constant.RIPConstant.DEFAULT)\n" //
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
				+ "import com.shri.restinpeace.multipart.UploadProgressListener;\n" //
				+ "@RestClient\n" //
				+ "public interface UploadListenerWithoutMultipart {\n" //
				+ "  @GET(\"http://localhost/items\")\n" //
				+ "  String getItem(UploadProgressListener listener);\n" //
				+ "}\n");

		assertErrorContains(diagnostics,
				"has an UploadProgressListener parameter but is not annotated with @Multipart");
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
