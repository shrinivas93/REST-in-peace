package com.shri.restinpeace.codegen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Generates a {@code @RestClient} interface (annotations and all) from an
 * OpenAPI 3.x document - the opposite direction of this library's own
 * compile-time proxy generation, which turns a hand-written interface into
 * an implementation. Spec-first instead of annotation-first: point this at
 * an existing API's OpenAPI JSON and get a ready-to-compile interface
 * instead of transcribing every path/parameter by hand.
 *
 * <p>
 * <b>Scope:</b> this is a skeleton generator, not a full schema-to-POJO code
 * generator like swagger-codegen/OpenAPI Generator - every parameter and
 * every request/response body is generated as {@code String}. OpenAPI's own
 * {@code {name}} path-template placeholder syntax already matches
 * {@link com.shri.restinpeace.annotation.request.PathParam @PathParam}'s
 * exactly, so paths need no translation at all. What this generator gets
 * right - the paths, HTTP methods, parameter names/locations, and base URL -
 * is the tedious, error-prone part of a large API; narrowing a specific
 * parameter's type or replacing a body's {@code String} with your own POJO
 * is a normal hand-edit of the generated file afterward, not something this
 * generator attempts.
 *
 * <p>
 * Only {@code get}/{@code post}/{@code put}/{@code delete}/{@code patch}
 * operations are read from each path item ({@code head}/{@code options}/
 * {@code trace} are skipped); a {@code requestBody} of any shape becomes one
 * {@code @Body String} parameter; every {@code parameters} entry with
 * {@code in: path} or {@code in: query} becomes a
 * {@code @PathParam}/{@code @QueryParam} of type {@code String} (any other
 * {@code in} - {@code header}/{@code cookie} - is skipped, since honoring it
 * is a per-call/interceptor concern, not part of the interface shape).
 * {@code servers[0].url}, if present, becomes the interface's
 * {@code @BaseUrl}; its absence isn't an error - the generated interface
 * then simply requires every method's own URL to already be absolute,
 * exactly like a hand-written one would.
 */
public final class OpenApiClientGenerator {

	private static final List<String> SUPPORTED_HTTP_METHODS = Arrays.asList("get", "post", "put", "delete", "patch");

	private static final Set<String> JAVA_RESERVED_WORDS = new TreeSet<>(Arrays.asList("abstract", "assert", "boolean",
			"break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else",
			"enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
			"int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
			"short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
			"try", "void", "volatile", "while"));

	private OpenApiClientGenerator() {
	}

	/**
	 * Reads {@code specFile} as an OpenAPI 3.x JSON document and writes a
	 * generated {@code @RestClient} interface to
	 * {@code <outputDirectory>/<interfaceName>.java}, creating
	 * {@code outputDirectory} (and any missing parent directories) if
	 * needed.
	 *
	 * @param specFile        the OpenAPI 3.x document, as JSON (not YAML -
	 *                        convert first if your spec is YAML)
	 * @param outputDirectory the directory the generated {@code .java} file
	 *                        is written into
	 * @param packageName     the generated interface's package
	 * @param interfaceName   the generated interface's simple name
	 * @throws IOException if {@code specFile} can't be read or the output
	 *                      file can't be written
	 */
	public static void generate(File specFile, File outputDirectory, String packageName, String interfaceName)
			throws IOException {
		String json = new String(Files.readAllBytes(specFile.toPath()), StandardCharsets.UTF_8);
		JsonObject spec = JsonParser.parseString(json).getAsJsonObject();
		String baseUrl = extractBaseUrl(spec);
		List<GeneratedMethod> methods = extractMethods(spec);
		String source = render(packageName, interfaceName, baseUrl, methods, specTitle(spec));
		if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
			throw new IOException("Could not create output directory: " + outputDirectory);
		}
		Files.write(new File(outputDirectory, interfaceName + ".java").toPath(), source.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Command-line entry point: {@code specFile outputDirectory packageName interfaceName}.
	 *
	 * @param args the four positional arguments described above
	 * @throws IOException if {@code specFile} can't be read or the output
	 *                      file can't be written
	 */
	public static void main(String[] args) throws IOException {
		if (args.length != 4) {
			System.err.println("Usage: OpenApiClientGenerator <specFile> <outputDirectory> <packageName> <interfaceName>");
			System.exit(1);
			return;
		}
		generate(new File(args[0]), new File(args[1]), args[2], args[3]);
	}

	private static String specTitle(JsonObject spec) {
		JsonObject info = spec.getAsJsonObject("info");
		if (info != null && info.has("title")) {
			return info.get("title").getAsString();
		}
		return "an OpenAPI spec";
	}

	private static String extractBaseUrl(JsonObject spec) {
		JsonArray servers = spec.getAsJsonArray("servers");
		if (servers == null || servers.size() == 0) {
			return null;
		}
		JsonObject firstServer = servers.get(0).getAsJsonObject();
		return firstServer.has("url") ? firstServer.get("url").getAsString() : null;
	}

	private static List<GeneratedMethod> extractMethods(JsonObject spec) {
		List<GeneratedMethod> methods = new ArrayList<>();
		JsonObject paths = spec.getAsJsonObject("paths");
		if (paths == null) {
			return methods;
		}
		Set<String> usedNames = new LinkedHashSet<>();
		for (Map.Entry<String, JsonElement> pathEntry : paths.entrySet()) {
			String path = pathEntry.getKey();
			if (!pathEntry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject pathItem = pathEntry.getValue().getAsJsonObject();
			for (String httpMethod : SUPPORTED_HTTP_METHODS) {
				if (!pathItem.has(httpMethod) || !pathItem.get(httpMethod).isJsonObject()) {
					continue;
				}
				methods.add(buildMethod(httpMethod, path, pathItem.getAsJsonObject(httpMethod), usedNames));
			}
		}
		return methods;
	}

	private static GeneratedMethod buildMethod(String httpMethod, String path, JsonObject operation,
			Set<String> usedNames) {
		String methodName = uniqueName(methodNameFor(httpMethod, path, operation), usedNames);
		List<GeneratedParameter> parameters = new ArrayList<>();
		Set<String> usedParameterNames = new LinkedHashSet<>();
		JsonArray operationParameters = operation.getAsJsonArray("parameters");
		if (operationParameters != null) {
			for (JsonElement parameterElement : operationParameters) {
				GeneratedParameter parameter = toParameter(parameterElement.getAsJsonObject(), usedParameterNames);
				if (parameter != null) {
					parameters.add(parameter);
				}
			}
		}
		boolean hasBody = operation.has("requestBody");
		if (hasBody) {
			parameters.add(GeneratedParameter.body(uniqueName("body", usedParameterNames)));
		}
		return new GeneratedMethod(httpMethod, path, methodName, parameters);
	}

	private static GeneratedParameter toParameter(JsonObject parameter, Set<String> usedParameterNames) {
		if (!parameter.has("name") || !parameter.has("in")) {
			return null;
		}
		String name = parameter.get("name").getAsString();
		String in = parameter.get("in").getAsString();
		String javaName = uniqueName(sanitizeIdentifier(name), usedParameterNames);
		if ("path".equals(in)) {
			return GeneratedParameter.path(name, javaName);
		}
		if ("query".equals(in)) {
			boolean required = parameter.has("required") && parameter.get("required").getAsBoolean();
			return GeneratedParameter.query(name, javaName, required);
		}
		return null; // header/cookie params - not part of the interface shape, see class javadoc
	}

	private static String methodNameFor(String httpMethod, String path, JsonObject operation) {
		if (operation.has("operationId")) {
			String sanitized = sanitizeIdentifier(operation.get("operationId").getAsString());
			if (!sanitized.isEmpty()) {
				return Character.isUpperCase(sanitized.charAt(0))
						? Character.toLowerCase(sanitized.charAt(0)) + sanitized.substring(1)
						: sanitized;
			}
		}
		StringBuilder name = new StringBuilder(httpMethod);
		for (String segment : path.split("/")) {
			if (segment.isEmpty()) {
				continue;
			}
			String cleaned = segment.startsWith("{") && segment.endsWith("}") ? segment.substring(1, segment.length() - 1)
					: segment;
			String capitalized = sanitizeIdentifier(cleaned);
			if (!capitalized.isEmpty()) {
				name.append(Character.toUpperCase(capitalized.charAt(0))).append(capitalized.substring(1));
			}
		}
		return name.toString();
	}

	private static String uniqueName(String candidate, Set<String> usedNames) {
		String name = candidate;
		int suffix = 2;
		while (!usedNames.add(name)) {
			name = candidate + suffix++;
		}
		return name;
	}

	/**
	 * Converts an arbitrary OpenAPI identifier (a parameter/operationId
	 * name, a path segment) into a valid Java identifier - non-identifier
	 * characters become {@code _}, a leading digit gets a {@code _} prefix,
	 * and a Java reserved word gets a trailing {@code _}.
	 */
	private static String sanitizeIdentifier(String raw) {
		StringBuilder sanitized = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			boolean valid = sanitized.length() == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c);
			sanitized.append(valid ? c : '_');
		}
		String result = sanitized.length() == 0 || !Character.isJavaIdentifierStart(sanitized.charAt(0))
				? "_" + sanitized
				: sanitized.toString();
		return JAVA_RESERVED_WORDS.contains(result) ? result + "_" : result;
	}

	private static String render(String packageName, String interfaceName, String baseUrl,
			List<GeneratedMethod> methods, String specTitle) {
		Set<String> httpMethodImports = new TreeSet<>();
		Set<String> paramAnnotationImports = new TreeSet<>();
		for (GeneratedMethod method : methods) {
			httpMethodImports.add(method.httpMethod.toUpperCase(java.util.Locale.ROOT));
			for (GeneratedParameter parameter : method.parameters) {
				paramAnnotationImports.add(parameter.annotationSimpleName());
			}
		}

		StringBuilder source = new StringBuilder();
		source.append("package ").append(packageName).append(";\n\n");
		source.append("import com.shri.restinpeace.annotation.marker.RestClient;\n");
		if (baseUrl != null) {
			source.append("import com.shri.restinpeace.annotation.marker.BaseUrl;\n");
		}
		for (String httpMethodImport : httpMethodImports) {
			source.append("import com.shri.restinpeace.annotation.method.").append(httpMethodImport).append(";\n");
		}
		for (String paramAnnotationImport : paramAnnotationImports) {
			source.append("import com.shri.restinpeace.annotation.request.").append(paramAnnotationImport).append(";\n");
		}
		source.append("\n");
		source.append("/**\n");
		source.append(" * Generated by {@link com.shri.restinpeace.codegen.OpenApiClientGenerator} from \"")
				.append(specTitle).append("\" - regenerate from the spec instead of hand-editing the\n");
		source.append(" * paths/parameters below; every parameter and body is a plain {@code String} -\n");
		source.append(" * narrowing a type or replacing a body with your own POJO is a normal hand-edit.\n");
		source.append(" */\n");
		source.append("@RestClient\n");
		if (baseUrl != null) {
			source.append("@BaseUrl(\"").append(baseUrl).append("\")\n");
		}
		source.append("public interface ").append(interfaceName).append(" {\n\n");
		for (GeneratedMethod method : methods) {
			appendMethod(source, method);
		}
		source.append("}\n");
		return source.toString();
	}

	private static void appendMethod(StringBuilder source, GeneratedMethod method) {
		source.append("\t@").append(method.httpMethod.toUpperCase(java.util.Locale.ROOT)).append("(\"")
				.append(method.path).append("\")\n");
		source.append("\tString ").append(method.methodName).append("(");
		for (int i = 0; i < method.parameters.size(); i++) {
			if (i > 0) {
				source.append(", ");
			}
			source.append(method.parameters.get(i).declaration());
		}
		source.append(");\n\n");
	}

	/** One generated interface method - one OpenAPI operation. */
	private static final class GeneratedMethod {
		final String httpMethod;
		final String path;
		final String methodName;
		final List<GeneratedParameter> parameters;

		GeneratedMethod(String httpMethod, String path, String methodName, List<GeneratedParameter> parameters) {
			this.httpMethod = httpMethod;
			this.path = path;
			this.methodName = methodName;
			this.parameters = parameters;
		}
	}

	/** One generated method parameter - a path/query param, or the request body. */
	private static final class GeneratedParameter {
		private final String kind;
		private final String openApiName;
		private final String javaName;
		private final boolean required;

		private GeneratedParameter(String kind, String openApiName, String javaName, boolean required) {
			this.kind = kind;
			this.openApiName = openApiName;
			this.javaName = javaName;
			this.required = required;
		}

		static GeneratedParameter path(String openApiName, String javaName) {
			return new GeneratedParameter("path", openApiName, javaName, true);
		}

		static GeneratedParameter query(String openApiName, String javaName, boolean required) {
			return new GeneratedParameter("query", openApiName, javaName, required);
		}

		static GeneratedParameter body(String javaName) {
			return new GeneratedParameter("body", null, javaName, true);
		}

		String annotationSimpleName() {
			switch (kind) {
			case "path":
				return "PathParam";
			case "query":
				return "QueryParam";
			default:
				return "Body";
			}
		}

		String declaration() {
			switch (kind) {
			case "path":
				return "@PathParam(\"" + openApiName + "\") String " + javaName;
			case "query":
				return "@QueryParam(value = \"" + openApiName + "\", required = " + required + ") String " + javaName;
			default:
				return "@Body String " + javaName;
			}
		}
	}

}
