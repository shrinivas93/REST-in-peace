package com.shri.restinpeace.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.shri.restinpeace.annotation.error.ErrorType;
import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.DELETE;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.HEAD;
import com.shri.restinpeace.annotation.method.OPTIONS;
import com.shri.restinpeace.annotation.method.PATCH;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.request.Headers;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.constant.RIPConstant;

/**
 * Generates a compile-time implementation ({@code <Interface>_RipImpl}) of
 * every {@code @RestClient} interface whose methods all fall within the
 * minimal supported shape: a single fixed HTTP verb, only
 * {@link PathParam @PathParam}/{@link QueryParam @QueryParam} parameters,
 * an optional {@link Timeout @Timeout}/{@link Retry @Retry}, and a
 * {@code void}, {@code String}, or non-generic POJO return type.
 * {@code RIP.getClient(...)} prefers this generated class over the
 * reflective {@code java.lang.reflect.Proxy} it falls back to for an
 * interface this processor didn't (fully) generate for - see
 * {@code docs/design/compile-time-proxy-generation.md} for the full design
 * this is step 1 of.
 *
 * <p>
 * An interface with a nested/private declaration, a default or static
 * method, or any single method using a feature outside the shape above
 * (`@Headers`, `@HeaderParam`/`@HeaderMap`, `@Body`,
 * `@Multipart`, `@Url`, `@ErrorType`, `@QueryMap`, a required/defaulted
 * `@QueryParam`, a `CompletableFuture`/`RipResponse`/`byte[]`/`File`
 * return type, ...) is silently skipped in its entirety and left to the
 * reflective proxy - generating a partially-correct implementation would
 * be worse than not generating one at all.
 */
@SupportedAnnotationTypes("com.shri.restinpeace.annotation.marker.RestClient")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class RestClientProcessor extends AbstractProcessor {

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		for (Element element : roundEnv.getElementsAnnotatedWith(RestClient.class)) {
			if (element.getKind() == ElementKind.INTERFACE) {
				processRestClient((TypeElement) element);
			}
		}
		return true;
	}

	private void processRestClient(TypeElement interfaceElement) {
		if (interfaceElement.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
			return; // nested/private interfaces aren't supported yet
		}

		List<MethodModel> methods = new ArrayList<>();
		for (Element enclosed : interfaceElement.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.METHOD) {
				continue;
			}
			ExecutableElement methodElement = (ExecutableElement) enclosed;
			if (methodElement.getModifiers().contains(Modifier.DEFAULT)
					|| methodElement.getModifiers().contains(Modifier.STATIC)) {
				return; // default/static interface methods aren't supported yet
			}
			MethodModel model = toSupportedMethodModel(methodElement);
			if (model == null) {
				return; // one unsupported method disqualifies the whole interface
			}
			methods.add(model);
		}
		if (methods.isEmpty()) {
			return;
		}

		String interfaceBaseUrl = interfaceBaseUrlOf(interfaceElement);
		writeImplementation(interfaceElement, interfaceBaseUrl, methods);
	}

	private String interfaceBaseUrlOf(TypeElement interfaceElement) {
		BaseUrl baseUrl = interfaceElement.getAnnotation(BaseUrl.class);
		return baseUrl == null ? null : baseUrl.value();
	}

	private MethodModel toSupportedMethodModel(ExecutableElement methodElement) {
		if (methodElement.getAnnotation(Headers.class) != null || methodElement.getAnnotation(ErrorType.class) != null) {
			return null; // not supported yet
		}
		HttpMethodAndUrl httpMethodAndUrl = httpMethodAndUrlOf(methodElement);
		if (httpMethodAndUrl == null) {
			return null;
		}
		String returnTypeName = returnTypeNameOf(methodElement);
		if (returnTypeName == null) {
			return null;
		}

		List<ParamModel> params = new ArrayList<>();
		for (VariableElement parameter : methodElement.getParameters()) {
			ParamModel param = toSupportedParamModel(parameter);
			if (param == null) {
				return null;
			}
			params.add(param);
		}

		TimeoutModel timeoutModel = timeoutModelOf(methodElement);
		RetryModel retryModel = retryModelOf(methodElement);

		return new MethodModel(methodElement.getSimpleName().toString(), httpMethodAndUrl.httpMethod,
				httpMethodAndUrl.urlTemplate, returnTypeName, params, timeoutModel, retryModel);
	}

	private TimeoutModel timeoutModelOf(ExecutableElement methodElement) {
		Timeout timeout = methodElement.getAnnotation(Timeout.class);
		if (timeout == null) {
			return new TimeoutModel(-1, -1);
		}
		return new TimeoutModel(timeout.connectMillis(), timeout.readMillis());
	}

	private RetryModel retryModelOf(ExecutableElement methodElement) {
		Retry retry = methodElement.getAnnotation(Retry.class);
		if (retry == null) {
			return new RetryModel(false, 0, 0L, 1.0, new int[0]);
		}
		return new RetryModel(true, retry.times(), retry.delayMillis(), retry.backoffMultiplier(),
				retry.retryOnStatus());
	}

	private HttpMethodAndUrl httpMethodAndUrlOf(ExecutableElement methodElement) {
		GET get = methodElement.getAnnotation(GET.class);
		if (get != null) {
			return new HttpMethodAndUrl(HTTPMethod.GET, get.value());
		}
		POST post = methodElement.getAnnotation(POST.class);
		if (post != null) {
			return new HttpMethodAndUrl(HTTPMethod.POST, post.value());
		}
		PUT put = methodElement.getAnnotation(PUT.class);
		if (put != null) {
			return new HttpMethodAndUrl(HTTPMethod.PUT, put.value());
		}
		PATCH patch = methodElement.getAnnotation(PATCH.class);
		if (patch != null) {
			return new HttpMethodAndUrl(HTTPMethod.PATCH, patch.value());
		}
		DELETE delete = methodElement.getAnnotation(DELETE.class);
		if (delete != null) {
			return new HttpMethodAndUrl(HTTPMethod.DELETE, delete.value());
		}
		HEAD head = methodElement.getAnnotation(HEAD.class);
		if (head != null) {
			return new HttpMethodAndUrl(HTTPMethod.HEAD, head.value());
		}
		OPTIONS options = methodElement.getAnnotation(OPTIONS.class);
		if (options != null) {
			return new HttpMethodAndUrl(HTTPMethod.OPTIONS, options.value());
		}
		return null; // none, or (a validation error the runtime validator will catch) more than one
	}

	private String returnTypeNameOf(ExecutableElement methodElement) {
		TypeMirror returnType = methodElement.getReturnType();
		if (returnType.getKind() == TypeKind.VOID) {
			return "void";
		}
		if (returnType.getKind() != TypeKind.DECLARED) {
			return null; // a primitive (other than void) or array (e.g. byte[]) isn't supported yet
		}
		if (!((DeclaredType) returnType).getTypeArguments().isEmpty()) {
			return null; // a generic return type (e.g. List<User>) isn't decodable by Class<?> alone
		}
		return returnType.toString();
	}

	private ParamModel toSupportedParamModel(VariableElement parameter) {
		PathParam pathParam = parameter.getAnnotation(PathParam.class);
		QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
		if (pathParam != null && queryParam == null) {
			return new ParamModel(ParamKind.PATH, pathParam.value(), parameter.getSimpleName().toString(),
					parameter.asType().toString());
		}
		if (queryParam != null && pathParam == null) {
			if (queryParam.required() || !RIPConstant.DEFAULT.equals(queryParam.defaultValue())) {
				// required/defaultValue needs resolveValue()'s logic reproduced faithfully -
				// out of scope for this minimal shape, so this whole interface falls back.
				return null;
			}
			return new ParamModel(ParamKind.QUERY, queryParam.value(), parameter.getSimpleName().toString(),
					parameter.asType().toString());
		}
		return null; // no annotation, both, or some other annotation entirely (e.g. @HeaderParam)
	}

	private void writeImplementation(TypeElement interfaceElement, String interfaceBaseUrl,
			List<MethodModel> methods) {
		String interfaceName = interfaceElement.getQualifiedName().toString();
		PackageElement packageElement = (PackageElement) interfaceElement.getEnclosingElement();
		String packageName = packageElement.getQualifiedName().toString();
		String simpleName = interfaceElement.getSimpleName().toString();
		String implName = simpleName + "_RipImpl";

		try {
			JavaFileObject file = processingEnv.getFiler().createSourceFile(
					packageName.isEmpty() ? implName : packageName + "." + implName, interfaceElement);
			try (Writer writer = file.openWriter()) {
				writer.write(renderSource(packageName, implName, interfaceName, interfaceBaseUrl, methods));
			}
		} catch (IOException e) {
			processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
					"Failed to generate RIP implementation for " + interfaceName + ": " + e.getMessage(),
					interfaceElement);
		}
	}

	private String renderSource(String packageName, String implName, String interfaceName, String interfaceBaseUrl,
			List<MethodModel> methods) {
		StringBuilder out = new StringBuilder();
		if (!packageName.isEmpty()) {
			out.append("package ").append(packageName).append(";\n\n");
		}
		out.append("// Generated by RIP's RestClientProcessor. Do not edit - see\n");
		out.append("// docs/design/compile-time-proxy-generation.md.\n");
		out.append("public final class ").append(implName).append(" implements ").append(interfaceName)
				.append(" {\n\n");
		out.append("\tprivate final com.shri.restinpeace.annotation.service.RestRequestProcessor ripProcessor;\n\n");
		out.append("\tpublic ").append(implName)
				.append("(com.shri.restinpeace.annotation.service.RestRequestProcessor ripProcessor) {\n");
		out.append("\t\tthis.ripProcessor = ripProcessor;\n");
		out.append("\t}\n\n");

		for (MethodModel method : methods) {
			appendMethod(out, method, interfaceBaseUrl);
		}

		out.append("}\n");
		return out.toString();
	}

	private void appendMethod(StringBuilder out, MethodModel method, String interfaceBaseUrl) {
		out.append("\t@Override\n\tpublic ").append(method.returnTypeName).append(" ").append(method.name)
				.append("(");
		for (int i = 0; i < method.params.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			ParamModel param = method.params.get(i);
			out.append(param.javaTypeName).append(" ").append(param.javaParamName);
		}
		out.append(") {\n");

		out.append("\t\tObject result = ripProcessor.processGeneratedRequest(");
		out.append("com.shri.restinpeace.constant.HTTPMethod.").append(method.httpMethod.name()).append(", ");
		out.append(stringLiteral(method.urlTemplate)).append(", ");
		out.append(interfaceBaseUrl == null ? "null" : stringLiteral(interfaceBaseUrl)).append(", ");
		out.append(namesArrayLiteral(method, ParamKind.PATH)).append(", ");
		out.append(valuesArrayLiteral(method, ParamKind.PATH)).append(", ");
		out.append(namesArrayLiteral(method, ParamKind.QUERY)).append(", ");
		out.append(valuesArrayLiteral(method, ParamKind.QUERY)).append(", ");
		out.append(method.returnTypeName).append(".class, ");
		out.append(method.timeout.connectMillis).append(", ").append(method.timeout.readMillis).append(", ");
		out.append(method.retry.hasRetry).append(", ").append(method.retry.times).append(", ");
		out.append(method.retry.delayMillis).append("L, ").append(method.retry.backoffMultiplier).append(", ");
		out.append(intArrayLiteral(method.retry.retryOnStatus)).append(");\n");

		if (!"void".equals(method.returnTypeName)) {
			out.append("\t\treturn (").append(method.returnTypeName).append(") result;\n");
		}
		out.append("\t}\n\n");
	}

	private String namesArrayLiteral(MethodModel method, ParamKind kind) {
		StringBuilder names = new StringBuilder("new String[] {");
		boolean first = true;
		for (ParamModel param : method.params) {
			if (param.kind != kind) {
				continue;
			}
			if (!first) {
				names.append(", ");
			}
			names.append(stringLiteral(param.name));
			first = false;
		}
		return names.append("}").toString();
	}

	private String valuesArrayLiteral(MethodModel method, ParamKind kind) {
		StringBuilder values = new StringBuilder("new Object[] {");
		boolean first = true;
		for (ParamModel param : method.params) {
			if (param.kind != kind) {
				continue;
			}
			if (!first) {
				values.append(", ");
			}
			values.append(param.javaParamName);
			first = false;
		}
		return values.append("}").toString();
	}

	private static String stringLiteral(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String intArrayLiteral(int[] values) {
		StringBuilder out = new StringBuilder("new int[] {");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(values[i]);
		}
		return out.append("}").toString();
	}

	private static final class HttpMethodAndUrl {
		final HTTPMethod httpMethod;
		final String urlTemplate;

		HttpMethodAndUrl(HTTPMethod httpMethod, String urlTemplate) {
			this.httpMethod = httpMethod;
			this.urlTemplate = urlTemplate;
		}
	}

	private enum ParamKind {
		PATH, QUERY
	}

	private static final class ParamModel {
		final ParamKind kind;
		final String name;
		final String javaParamName;
		final String javaTypeName;

		ParamModel(ParamKind kind, String name, String javaParamName, String javaTypeName) {
			this.kind = kind;
			this.name = name;
			this.javaParamName = javaParamName;
			this.javaTypeName = javaTypeName;
		}
	}

	private static final class MethodModel {
		final String name;
		final HTTPMethod httpMethod;
		final String urlTemplate;
		final String returnTypeName;
		final List<ParamModel> params;
		final TimeoutModel timeout;
		final RetryModel retry;

		MethodModel(String name, HTTPMethod httpMethod, String urlTemplate, String returnTypeName,
				List<ParamModel> params, TimeoutModel timeout, RetryModel retry) {
			this.name = name;
			this.httpMethod = httpMethod;
			this.urlTemplate = urlTemplate;
			this.returnTypeName = returnTypeName;
			this.params = params;
			this.timeout = timeout;
			this.retry = retry;
		}
	}

	private static final class TimeoutModel {
		final int connectMillis;
		final int readMillis;

		TimeoutModel(int connectMillis, int readMillis) {
			this.connectMillis = connectMillis;
			this.readMillis = readMillis;
		}
	}

	private static final class RetryModel {
		final boolean hasRetry;
		final int times;
		final long delayMillis;
		final double backoffMultiplier;
		final int[] retryOnStatus;

		RetryModel(boolean hasRetry, int times, long delayMillis, double backoffMultiplier, int[] retryOnStatus) {
			this.hasRetry = hasRetry;
			this.times = times;
			this.delayMillis = delayMillis;
			this.backoffMultiplier = backoffMultiplier;
			this.retryOnStatus = retryOnStatus;
		}
	}

}
