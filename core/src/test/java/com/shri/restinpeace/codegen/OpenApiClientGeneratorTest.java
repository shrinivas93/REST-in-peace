package com.shri.restinpeace.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
 * {@link OpenApiClientGenerator} - structural checks on the generated source
 * text, plus a real {@code javac} compile (mirroring
 * {@code CompileTimeValidationTest}'s own approach) proving the generated
 * interface isn't just plausible-looking text but a genuinely valid
 * {@code @RestClient} that compiles clean and gets a real {@code _RipImpl}.
 */
class OpenApiClientGeneratorTest {

	@TempDir
	Path tempDir;

	private static final String SPEC = "" //
			+ "{\n" //
			+ "  \"openapi\": \"3.0.0\",\n" //
			+ "  \"info\": { \"title\": \"Pet Store\" },\n" //
			+ "  \"servers\": [ { \"url\": \"https://api.example.com\" } ],\n" //
			+ "  \"paths\": {\n" //
			+ "    \"/items/{id}\": {\n" //
			+ "      \"get\": {\n" //
			+ "        \"operationId\": \"getItem\",\n" //
			+ "        \"parameters\": [\n" //
			+ "          { \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } },\n" //
			+ "          { \"name\": \"verbose\", \"in\": \"query\", \"required\": false, \"schema\": { \"type\": \"boolean\" } }\n" //
			+ "        ]\n" //
			+ "      }\n" //
			+ "    },\n" //
			+ "    \"/items\": {\n" //
			+ "      \"get\": {},\n" //
			+ "      \"post\": {\n" //
			+ "        \"operationId\": \"createItem\",\n" //
			+ "        \"requestBody\": { \"content\": { \"application/json\": { \"schema\": { \"type\": \"object\" } } } }\n" //
			+ "      }\n" //
			+ "    }\n" //
			+ "  }\n" //
			+ "}\n";

	@Test
	void generate_writesInterfaceWithBaseUrlPathsAndParams() throws IOException {
		File specFile = writeSpec(SPEC);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "PetStoreApi");

		String source = readGenerated(outputDir, "PetStoreApi");
		assertTrue(source.contains("package com.example.generated;"));
		assertTrue(source.contains("@RestClient"));
		assertTrue(source.contains("@BaseUrl(\"https://api.example.com\")"));
		assertTrue(source.contains("@GET(\"/items/{id}\")"));
		assertTrue(source.contains("String getItem(@PathParam(\"id\") String id, "
				+ "@QueryParam(value = \"verbose\", required = false) String verbose);"));
		assertTrue(source.contains("@POST(\"/items\")"));
		assertTrue(source.contains("String createItem(@Body String body);"));
		assertTrue(source.contains("@GET(\"/items\")"));
		assertTrue(source.contains("String getItems();"));
	}

	@Test
	void generatedInterface_compilesCleanAndProducesARipImpl() throws IOException {
		File specFile = writeSpec(SPEC);
		File outputDir = tempDir.resolve("out").toFile();
		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "PetStoreApi");
		String source = readGenerated(outputDir, "PetStoreApi");

		Path classOutputDir = tempDir.resolve("classes");
		Files.createDirectories(classOutputDir);
		List<Diagnostic<? extends JavaFileObject>> diagnostics = compile("com.example.generated.PetStoreApi", source,
				classOutputDir);

		assertNoErrors(diagnostics);
		assertTrue(Files.exists(classOutputDir.resolve("com/example/generated/PetStoreApi_RipImpl.class")),
				"Expected PetStoreApi_RipImpl.class to be generated, found: " + list(classOutputDir));
	}

	@Test
	void generate_noServers_omitsBaseUrlEntirely() throws IOException {
		String specWithoutServers = "{ \"paths\": { \"/items\": { \"get\": {} } } }";
		File specFile = writeSpec(specWithoutServers);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "NoServersApi");

		String source = readGenerated(outputDir, "NoServersApi");
		assertFalse(source.contains("@BaseUrl"));
		assertFalse(source.contains("import com.shri.restinpeace.annotation.marker.BaseUrl;"));
	}

	@Test
	void generate_reservedWordOperationId_getsSanitized() throws IOException {
		String specWithReservedWord = "{ \"paths\": { \"/items\": { \"get\": { \"operationId\": \"class\" } } } }";
		File specFile = writeSpec(specWithReservedWord);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "ReservedWordApi");

		String source = readGenerated(outputDir, "ReservedWordApi");
		assertTrue(source.contains("String class_();"));
	}

	@Test
	void generate_duplicateGeneratedMethodNames_getsDeduped() throws IOException {
		// Neither operation names its own operationId, and both paths collapse to
		// the same synthesized name ("getItems") without deduping.
		String specWithCollision = "" //
				+ "{ \"paths\": {\n" //
				+ "  \"/items\": { \"get\": {} },\n" //
				+ "  \"/items/\": { \"get\": {} }\n" //
				+ "} }";
		File specFile = writeSpec(specWithCollision);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "CollisionApi");

		String source = readGenerated(outputDir, "CollisionApi");
		assertTrue(source.contains("String getItems();"));
		assertTrue(source.contains("String getItems2();"));
	}

	@Test
	void generate_outputDirectoryCannotBeCreated_throwsIOException() throws IOException {
		File specFile = writeSpec(SPEC);
		File blockerFile = tempDir.resolve("blocker").toFile();
		Files.write(blockerFile.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));
		File outputDir = new File(blockerFile, "subdir");

		assertThrows(IOException.class,
				() -> OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "BlockedApi"));
	}

	@Test
	void main_withValidArgs_generatesInterface() throws IOException {
		File specFile = writeSpec(SPEC);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.main(
				new String[] { specFile.getPath(), outputDir.getPath(), "com.example.generated", "MainInvokedApi" });

		String source = readGenerated(outputDir, "MainInvokedApi");
		assertTrue(source.contains("public interface MainInvokedApi"));
	}

	@Test
	void generate_serverWithNoUrl_omitsBaseUrl() throws IOException {
		String specWithUrllessServer = "{ \"servers\": [ {} ], \"paths\": { \"/items\": { \"get\": {} } } }";
		File specFile = writeSpec(specWithUrllessServer);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "UrllessServerApi");

		String source = readGenerated(outputDir, "UrllessServerApi");
		assertFalse(source.contains("@BaseUrl"));
	}

	@Test
	void generate_noPathsKey_generatesNoMethods() throws IOException {
		String specWithoutPaths = "{ \"info\": { \"title\": \"x\" } }";
		File specFile = writeSpec(specWithoutPaths);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "NoPathsApi");

		String source = readGenerated(outputDir, "NoPathsApi");
		assertTrue(source.contains("public interface NoPathsApi {"));
		assertFalse(source.contains("@GET"));
		assertFalse(source.contains("@POST"));
	}

	@Test
	void generate_nonObjectPathValue_isSkipped() throws IOException {
		String specWithBadPath = "" //
				+ "{ \"paths\": {\n" //
				+ "  \"/broken\": \"not-an-object\",\n" //
				+ "  \"/items\": { \"get\": {} }\n" //
				+ "} }";
		File specFile = writeSpec(specWithBadPath);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "BadPathApi");

		String source = readGenerated(outputDir, "BadPathApi");
		assertTrue(source.contains("String getItems();"));
		assertFalse(source.contains("broken"));
	}

	@Test
	void generate_nonObjectOperationValue_isSkipped() throws IOException {
		String specWithBadOperation = "" //
				+ "{ \"paths\": {\n" //
				+ "  \"/items\": { \"get\": \"oops\", \"post\": { \"operationId\": \"createItem\" } }\n" //
				+ "} }";
		File specFile = writeSpec(specWithBadOperation);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "BadOperationApi");

		String source = readGenerated(outputDir, "BadOperationApi");
		assertFalse(source.contains("@GET"));
		assertTrue(source.contains("String createItem();"));
	}

	@Test
	void generate_parameterMissingNameOrIn_isDropped() throws IOException {
		String specWithBadParams = "" //
				+ "{ \"paths\": { \"/items\": { \"get\": {\n" //
				+ "  \"operationId\": \"listItems\",\n" //
				+ "  \"parameters\": [\n" //
				+ "    { \"in\": \"query\", \"schema\": { \"type\": \"string\" } },\n" //
				+ "    { \"name\": \"orphan\" }\n" //
				+ "  ]\n" //
				+ "} } } }";
		File specFile = writeSpec(specWithBadParams);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "BadParamsApi");

		String source = readGenerated(outputDir, "BadParamsApi");
		assertTrue(source.contains("String listItems();"));
	}

	@Test
	void generate_requiredQueryParam_generatesRequiredTrue() throws IOException {
		String specWithRequiredQuery = "" //
				+ "{ \"paths\": { \"/items\": { \"get\": {\n" //
				+ "  \"operationId\": \"listItems\",\n" //
				+ "  \"parameters\": [\n" //
				+ "    { \"name\": \"category\", \"in\": \"query\", \"required\": true, \"schema\": { \"type\": \"string\" } }\n" //
				+ "  ]\n" //
				+ "} } } }";
		File specFile = writeSpec(specWithRequiredQuery);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "RequiredQueryApi");

		String source = readGenerated(outputDir, "RequiredQueryApi");
		assertTrue(
				source.contains("String listItems(@QueryParam(value = \"category\", required = true) String category);"));
	}

	@Test
	void generate_headerAndCookieParams_areSkipped() throws IOException {
		String specWithHeaderParam = "" //
				+ "{ \"paths\": { \"/items\": { \"get\": {\n" //
				+ "  \"operationId\": \"listItems\",\n" //
				+ "  \"parameters\": [\n" //
				+ "    { \"name\": \"X-Api-Key\", \"in\": \"header\", \"schema\": { \"type\": \"string\" } },\n" //
				+ "    { \"name\": \"session\", \"in\": \"cookie\", \"schema\": { \"type\": \"string\" } }\n" //
				+ "  ]\n" //
				+ "} } } }";
		File specFile = writeSpec(specWithHeaderParam);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "HeaderParamApi");

		String source = readGenerated(outputDir, "HeaderParamApi");
		assertTrue(source.contains("String listItems();"));
	}

	@Test
	void generate_uppercaseOperationId_getsFirstLetterLowerCased() throws IOException {
		String specWithUppercaseId = "{ \"paths\": { \"/items\": { \"get\": { \"operationId\": \"GetItem\" } } } }";
		File specFile = writeSpec(specWithUppercaseId);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "UppercaseIdApi");

		String source = readGenerated(outputDir, "UppercaseIdApi");
		assertTrue(source.contains("String getItem();"));
	}

	@Test
	void generate_pathBasedFallbackNaming_stripsPathParamBraces() throws IOException {
		String specWithoutOperationId = "{ \"paths\": { \"/widgets/{id}\": { \"delete\": {} } } }";
		File specFile = writeSpec(specWithoutOperationId);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "FallbackNamingApi");

		String source = readGenerated(outputDir, "FallbackNamingApi");
		assertTrue(source.contains("String deleteWidgetsId();"));
	}

	@Test
	void generate_nonIdentifierCharacters_getSanitizedToUnderscore() throws IOException {
		String specWithHyphenParam = "" //
				+ "{ \"paths\": { \"/items\": { \"get\": {\n" //
				+ "  \"operationId\": \"listItems\",\n" //
				+ "  \"parameters\": [\n" //
				+ "    { \"name\": \"x-request-id\", \"in\": \"query\", \"schema\": { \"type\": \"string\" } }\n" //
				+ "  ]\n" //
				+ "} } } }";
		File specFile = writeSpec(specWithHyphenParam);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "HyphenParamApi");

		String source = readGenerated(outputDir, "HyphenParamApi");
		assertTrue(source.contains("String x_request_id"));
	}

	@Test
	void generate_emptyOperationId_sanitizesToUnderscoreMethodName() throws IOException {
		String specWithEmptyOperationId = "{ \"paths\": { \"/items\": { \"get\": { \"operationId\": \"\" } } } }";
		File specFile = writeSpec(specWithEmptyOperationId);
		File outputDir = tempDir.resolve("out").toFile();

		OpenApiClientGenerator.generate(specFile, outputDir, "com.example.generated", "EmptyOperationIdApi");

		String source = readGenerated(outputDir, "EmptyOperationIdApi");
		assertTrue(source.contains("String _();"));
	}

	private File writeSpec(String json) throws IOException {
		File specFile = tempDir.resolve("spec-" + System.nanoTime() + ".json").toFile();
		Files.write(specFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
		return specFile;
	}

	private String readGenerated(File outputDir, String interfaceName) throws IOException {
		return new String(Files.readAllBytes(new File(outputDir, interfaceName + ".java").toPath()),
				StandardCharsets.UTF_8);
	}

	private List<Diagnostic<? extends JavaFileObject>> compile(String fullyQualifiedName, String source,
			Path classOutputDir) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		JavaFileObject sourceFile = new SimpleJavaFileObject(
				URI.create("string:///" + fullyQualifiedName.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return source;
			}
		};

		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null,
				StandardCharsets.UTF_8)) {
			fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classOutputDir.toFile()));
			List<String> options = Arrays.asList("-classpath", System.getProperty("java.class.path"));
			JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
					Collections.singletonList(sourceFile));
			task.call();
		}
		return diagnostics.getDiagnostics();
	}

	private static void assertNoErrors(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
		List<String> errors = diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
				.map(d -> d.getMessage(null)).collect(Collectors.toList());
		assertTrue(errors.isEmpty(), "Expected no compile errors, got: " + errors);
	}

	private static List<String> list(Path dir) throws IOException {
		try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
			return paths.map(Path::toString).collect(Collectors.toList());
		}
	}

}
