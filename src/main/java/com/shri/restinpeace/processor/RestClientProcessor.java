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
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

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
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.Destination;
import com.shri.restinpeace.annotation.request.Field;
import com.shri.restinpeace.annotation.request.FieldMap;
import com.shri.restinpeace.annotation.request.FormUrlEncoded;
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.Headers;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PartMap;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.request.Url;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.constant.HTTPMethod;

/**
 * Generates a compile-time implementation ({@code <Interface>_RipImpl}) of
 * every {@code @RestClient} interface whose methods all fall within the
 * currently-supported shape: a single fixed HTTP verb, {@code @PathParam}/
 * {@code @QueryParam}/{@code @HeaderParam}/{@code @QueryMap}/
 * {@code @HeaderMap}/{@code @Body}/{@code @Url}/{@code @Part}/
 * {@code @PartMap}/{@code @Field}/{@code @FieldMap}/{@code @Destination}/an
 * {@code UploadProgressListener}/{@code DownloadProgressListener} parameter,
 * optional {@code @Timeout}/{@code @Retry}/{@code @Headers}/{@code @ErrorType}/
 * {@code @Multipart}/{@code @FormUrlEncoded}, and a {@code void},
 * {@code String}, non-generic POJO, {@code byte[]},
 * {@code File}, or {@code RipResponse<T>} (for any of the previous
 * return-type shapes) return type. {@code RIP.getClient(...)} prefers this
 * generated class over the reflective {@code java.lang.reflect.Proxy} it
 * falls back to for an interface this processor didn't (fully) generate for
 * - see {@code docs/design/compile-time-proxy-generation.md} for the full
 * design this is step 2 of.
 *
 * <p>
 * An interface with a nested/private declaration, a default or static
 * method, or any single method using a feature outside the shape above
 * (a {@code CompletableFuture} return type, ...) is silently skipped in its
 * entirety and left to the reflective proxy - generating a
 * partially-correct implementation would be worse than not generating one
 * at all.
 *
 * <p>
 * Before any of that, every {@code @RestClient} interface this processor
 * sees - whether or not it also happens to fall within the shape above - is
 * run through {@link CompileTimeValidator}, the compile-time counterpart of
 * {@link com.shri.restinpeace.validator.RestClientValidator}'s semantic
 * rules (an invalid {@code @Retry}, a malformed {@code @Headers} entry, an
 * unmatched path param, ...). A problem there fails compilation outright,
 * with the same message {@code RestClientValidator} would otherwise only
 * report at the first {@code RIP.getClient(...)} call - step 4 of
 * {@code docs/design/compile-time-proxy-generation.md}.
 */
@SupportedAnnotationTypes("com.shri.restinpeace.annotation.marker.RestClient")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class RestClientProcessor extends AbstractProcessor {

	/** Instantiated reflectively by the annotation-processing tool via its {@code META-INF/services} SPI registration. */
	public RestClientProcessor() {
	}

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

		// Runs on every @RestClient interface, whether or not it also happens to fall
		// within the codegen-supported shape below - an interface can be semantically
		// invalid (e.g. @Multipart on a GET) yet still structurally "supported", and
		// should fail the build either way rather than silently falling back to the
		// reflective proxy and only failing on the first actual call.
		if (!CompileTimeValidator.validate(interfaceElement, processingEnv)) {
			return;
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
		HttpMethodAndUrl httpMethodAndUrl = httpMethodAndUrlOf(methodElement);
		if (httpMethodAndUrl == null) {
			return null;
		}
		ReturnModel returnModel = returnModelOf(methodElement);
		if (returnModel == null) {
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

		boolean isMultipart = methodElement.getAnnotation(Multipart.class) != null;
		boolean isFormUrlEncoded = methodElement.getAnnotation(FormUrlEncoded.class) != null;
		if (isMultipart && isFormUrlEncoded) {
			return null; // @Multipart + @FormUrlEncoded is itself a validation error; fall back to reflective
		}
		int destinationCount = 0;
		for (ParamModel param : params) {
			boolean isMultipartOnlyKind = param.kind == ParamKind.PART || param.kind == ParamKind.PART_MAP
					|| param.kind == ParamKind.UPLOAD_PROGRESS;
			if (isMultipartOnlyKind && !isMultipart) {
				// @Part/@PartMap/an UploadProgressListener parameter with no @Multipart on the
				// method is itself a validation error the runtime validator catches - but
				// generated code for it would reference a __ripMultipart local this processor
				// never declares without @Multipart, which wouldn't just misbehave at runtime
				// like most other validation errors do, it would fail to *compile*. Never let
				// that happen - fall back to the reflective proxy instead.
				return null;
			}
			if (isMultipart && param.kind == ParamKind.BODY) {
				return null; // @Multipart + @Body is itself a validation error; same reasoning as above
			}
			boolean isFormUrlEncodedOnlyKind = param.kind == ParamKind.FIELD || param.kind == ParamKind.FIELD_MAP;
			if (isFormUrlEncodedOnlyKind && !isFormUrlEncoded) {
				// Same reasoning as the @Multipart-only kinds above - a @Field/@FieldMap
				// parameter with no @FormUrlEncoded on the method would reference a
				// __ripFormFields local this processor never declares without it.
				return null;
			}
			if (isFormUrlEncoded && param.kind == ParamKind.BODY) {
				return null; // @FormUrlEncoded + @Body is itself a validation error; same reasoning as above
			}
			boolean isDownloadOnlyKind = param.kind == ParamKind.DESTINATION || param.kind == ParamKind.DOWNLOAD_PROGRESS;
			if (isDownloadOnlyKind && returnModel.kind != ReturnKind.FILE) {
				// @Destination/a DownloadProgressListener parameter only makes sense on a
				// File-returning method - same reasoning as the @Multipart-only kinds above:
				// generated code for it would reference the destination file this processor
				// only ever resolves for a FILE return kind.
				return null;
			}
			if (param.kind == ParamKind.DESTINATION) {
				destinationCount++;
			}
		}
		if (returnModel.kind == ReturnKind.FILE && destinationCount != 1) {
			return null; // exactly one @Destination is required for a File return - itself a
							// validation error otherwise, but generated code needs to know
							// unambiguously which parameter to write the response into
		}

		TimeoutModel timeoutModel = timeoutModelOf(methodElement);
		RetryModel retryModel = retryModelOf(methodElement);
		String[] headerEntries = headerEntriesOf(methodElement);
		String errorTypeClassName = errorTypeClassNameOf(methodElement);

		return new MethodModel(methodElement.getSimpleName().toString(), httpMethodAndUrl.httpMethod,
				httpMethodAndUrl.urlTemplate, returnModel, params, timeoutModel, retryModel, headerEntries,
				errorTypeClassName, isMultipart, isFormUrlEncoded);
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

	private String[] headerEntriesOf(ExecutableElement methodElement) {
		Headers headers = methodElement.getAnnotation(Headers.class);
		return headers == null ? new String[0] : headers.value();
	}

	/**
	 * {@code @ErrorType}'s {@code value()} is a {@code Class<?>}; calling it
	 * directly during annotation processing throws {@link MirroredTypeException}
	 * (the class may not even be compiled yet) - its {@link TypeMirror} is
	 * how annotation processors are meant to read a {@code Class}-valued
	 * attribute.
	 */
	private String errorTypeClassNameOf(ExecutableElement methodElement) {
		ErrorType errorType = methodElement.getAnnotation(ErrorType.class);
		if (errorType == null) {
			return null;
		}
		try {
			errorType.value();
			throw new IllegalStateException("Expected MirroredTypeException reading @ErrorType's value().");
		} catch (MirroredTypeException e) {
			return e.getTypeMirror().toString();
		}
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

	private ReturnModel returnModelOf(ExecutableElement methodElement) {
		TypeMirror returnType = methodElement.getReturnType();
		if (returnType.getKind() == TypeKind.DECLARED) {
			DeclaredType declaredReturnType = (DeclaredType) returnType;
			String rawTypeName = processingEnv.getTypeUtils().erasure(declaredReturnType).toString();
			if ("java.util.concurrent.CompletableFuture".equals(rawTypeName)) {
				if (declaredReturnType.getTypeArguments().size() != 1) {
					return null; // a raw CompletableFuture with no type parameter isn't decodable
				}
				ReturnModel innerModel = nonAsyncReturnModelOf(declaredReturnType.getTypeArguments().get(0));
				return innerModel == null ? null
						: new ReturnModel(innerModel.kind, true, returnType.toString(), innerModel.decodeTypeName);
			}
		}
		ReturnModel model = nonAsyncReturnModelOf(returnType);
		return model == null ? null : new ReturnModel(model.kind, false, returnType.toString(), model.decodeTypeName);
	}

	/**
	 * Classifies a return type - or a {@code CompletableFuture<T>}'s
	 * {@code T} - as {@code BYTES}/{@code FILE}/{@code RIP_RESPONSE}/
	 * {@code PLAIN}, and names the {@code Class<?>} to decode the response
	 * body into ({@code decodeTypeName} - the wrapped {@code T} for
	 * {@code RIP_RESPONSE}, meaningless for {@code BYTES}/{@code FILE}
	 * since those always decode as {@code byte[]}). {@code void}/
	 * {@code Void} is {@code PLAIN} - {@code decodeBody} already treats
	 * both as "no body to decode" - since a generic type argument can only
	 * ever be {@code Void}, never the primitive {@code void} a plain
	 * (non-{@code CompletableFuture}) method can return.
	 */
	private ReturnModel nonAsyncReturnModelOf(TypeMirror type) {
		if (type.getKind() == TypeKind.VOID) {
			return new ReturnModel(ReturnKind.PLAIN, false, "void", "void");
		}
		if (type.getKind() == TypeKind.ARRAY) {
			// byte[] is the only supported array return type; anything else (int[], ...)
			// isn't decodable and was never supported by the reflective path either.
			return "byte[]".equals(type.toString()) ? new ReturnModel(ReturnKind.BYTES, false, "byte[]", "") : null;
		}
		if (type.getKind() != TypeKind.DECLARED) {
			return null; // some other primitive - not supported
		}
		DeclaredType declaredType = (DeclaredType) type;
		String rawTypeName = processingEnv.getTypeUtils().erasure(declaredType).toString();
		if ("com.shri.restinpeace.RipResponse".equals(rawTypeName)) {
			if (declaredType.getTypeArguments().size() != 1) {
				return null; // a raw RipResponse with no type parameter isn't decodable
			}
			String innerTypeName = returnTypeArgumentNameOf(declaredType.getTypeArguments().get(0));
			return innerTypeName == null ? null
					: new ReturnModel(ReturnKind.RIP_RESPONSE, false, type.toString(), innerTypeName);
		}
		if (!declaredType.getTypeArguments().isEmpty()) {
			return null; // some other generic type (e.g. List<User>, a nested CompletableFuture) isn't supported
		}
		if ("java.io.File".equals(rawTypeName)) {
			return new ReturnModel(ReturnKind.FILE, false, "java.io.File", "");
		}
		return new ReturnModel(ReturnKind.PLAIN, false, type.toString(), type.toString());
	}

	/**
	 * Names {@code T} in a {@code RipResponse<T>} return type - {@code "byte[]"}
	 * for {@code RipResponse<byte[]>}, or a plain (non-generic) class name for
	 * a {@code String}/POJO {@code T}, mirroring the reflective path's own
	 * {@code resolveWrappedType}'s restrictions.
	 */
	private String returnTypeArgumentNameOf(TypeMirror typeArgument) {
		if (typeArgument.getKind() == TypeKind.ARRAY) {
			return "byte[]".equals(typeArgument.toString()) ? "byte[]" : null;
		}
		if (typeArgument.getKind() != TypeKind.DECLARED) {
			return null;
		}
		if (!((DeclaredType) typeArgument).getTypeArguments().isEmpty()) {
			return null; // e.g. RipResponse<List<User>> isn't decodable by Class<?> alone
		}
		return typeArgument.toString();
	}

	private ParamModel toSupportedParamModel(VariableElement parameter) {
		String javaParamName = parameter.getSimpleName().toString();
		String javaTypeName = parameter.asType().toString();

		// UploadProgressListener/DownloadProgressListener need no annotation at all -
		// detected by type alone, same as the reflective path's own
		// `parameter.getType() == UploadProgressListener.class`/`== DownloadProgressListener.class`.
		if ("com.shri.restinpeace.multipart.UploadProgressListener".equals(javaTypeName)) {
			return new ParamModel(ParamKind.UPLOAD_PROGRESS, "", javaParamName, javaTypeName, false, "", "");
		}
		if ("com.shri.restinpeace.download.DownloadProgressListener".equals(javaTypeName)) {
			return new ParamModel(ParamKind.DOWNLOAD_PROGRESS, "", javaParamName, javaTypeName, false, "", "");
		}

		PathParam pathParam = parameter.getAnnotation(PathParam.class);
		QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
		HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
		QueryMap queryMap = parameter.getAnnotation(QueryMap.class);
		HeaderMap headerMap = parameter.getAnnotation(HeaderMap.class);
		Body body = parameter.getAnnotation(Body.class);
		Url url = parameter.getAnnotation(Url.class);
		Part part = parameter.getAnnotation(Part.class);
		PartMap partMap = parameter.getAnnotation(PartMap.class);
		Field field = parameter.getAnnotation(Field.class);
		FieldMap fieldMap = parameter.getAnnotation(FieldMap.class);
		Destination destination = parameter.getAnnotation(Destination.class);

		int annotationCount = countNonNull(pathParam, queryParam, headerParam, queryMap, headerMap, body, url, part,
				partMap, field, fieldMap, destination);
		if (annotationCount != 1) {
			return null; // no recognized annotation, or more than one - either way unsupported here
		}
		if (destination != null) {
			// the generated method assigns this parameter straight into a File local it
			// writes the response into, so a non-File @Destination parameter (itself a
			// validation error the runtime validator catches) would otherwise produce
			// generated source that fails to compile - never let that happen.
			return "java.io.File".equals(javaTypeName)
					? new ParamModel(ParamKind.DESTINATION, "", javaParamName, javaTypeName, false, "", "")
					: null;
		}

		if (pathParam != null) {
			return new ParamModel(ParamKind.PATH, pathParam.value(), javaParamName, javaTypeName, false, "", "");
		}
		if (queryParam != null) {
			return new ParamModel(ParamKind.QUERY, queryParam.value(), javaParamName, javaTypeName,
					queryParam.required(), queryParam.defaultValue(), "");
		}
		if (headerParam != null) {
			return new ParamModel(ParamKind.HEADER, headerParam.value(), javaParamName, javaTypeName,
					headerParam.required(), headerParam.defaultValue(), "");
		}
		if (queryMap != null) {
			return isMapType(parameter.asType())
					? new ParamModel(ParamKind.QUERY_MAP, "", javaParamName, javaTypeName, false, "", "")
					: null;
		}
		if (headerMap != null) {
			return isMapType(parameter.asType())
					? new ParamModel(ParamKind.HEADER_MAP, "", javaParamName, javaTypeName, false, "", "")
					: null;
		}
		if (body != null) {
			return new ParamModel(ParamKind.BODY, "", javaParamName, javaTypeName, false, "", "");
		}
		if (part != null) {
			return new ParamModel(ParamKind.PART, part.value(), javaParamName, javaTypeName, part.required(), "",
					part.fileName());
		}
		if (partMap != null) {
			return isMapType(parameter.asType())
					? new ParamModel(ParamKind.PART_MAP, "", javaParamName, javaTypeName, false, "", "")
					: null;
		}
		if (field != null) {
			return new ParamModel(ParamKind.FIELD, field.value(), javaParamName, javaTypeName, field.required(), "",
					"");
		}
		if (fieldMap != null) {
			return isMapType(parameter.asType())
					? new ParamModel(ParamKind.FIELD_MAP, "", javaParamName, javaTypeName, false, "", "")
					: null;
		}
		// url != null - the generated method assigns the resolved String straight into a
		// String url local, so a non-String @Url parameter (itself a validation error the
		// runtime validator catches) would otherwise produce generated source that fails to
		// compile - never let that happen.
		return "java.lang.String".equals(javaTypeName)
				? new ParamModel(ParamKind.URL, "", javaParamName, javaTypeName, false, "", "")
				: null;
	}

	private static int countNonNull(Object... values) {
		int count = 0;
		for (Object value : values) {
			if (value != null) {
				count++;
			}
		}
		return count;
	}

	private boolean isMapType(TypeMirror type) {
		if (type.getKind() != TypeKind.DECLARED) {
			return false;
		}
		TypeMirror mapErasure = processingEnv.getTypeUtils()
				.erasure(processingEnv.getElementUtils().getTypeElement("java.util.Map").asType());
		TypeMirror paramErasure = processingEnv.getTypeUtils().erasure(type);
		return processingEnv.getTypeUtils().isSubtype(paramErasure, mapErasure);
	}

	private void writeImplementation(TypeElement interfaceElement, String interfaceBaseUrl,
			List<MethodModel> methods) {
		String interfaceName = interfaceElement.getQualifiedName().toString();
		PackageElement packageElement = (PackageElement) interfaceElement.getEnclosingElement();
		String packageName = packageElement.getQualifiedName().toString();
		String simpleName = interfaceElement.getSimpleName().toString();
		String implName = simpleName + "_RipImpl";

		String qualifiedImplName = packageName.isEmpty() ? implName : packageName + "." + implName;
		try {
			JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedImplName, interfaceElement);
			try (Writer writer = file.openWriter()) {
				writer.write(renderSource(packageName, implName, interfaceName, interfaceBaseUrl, methods));
			}
			writeNativeImageReflectConfig(qualifiedImplName, interfaceElement);
		} catch (IOException e) {
			processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
					"Failed to generate RIP implementation for " + interfaceName + ": " + e.getMessage(),
					interfaceElement);
		}
	}

	/**
	 * {@code RIP.getClient(...)} looks up {@code <Interface>_RipImpl} via
	 * {@code Class.forName(restClient.getName() + "_RipImpl")} - a
	 * dynamically-computed name GraalVM's native-image static analysis can't
	 * resolve at build time the way it resolves a literal
	 * {@code Class.forName("some.Constant")}, so without this, every
	 * generated class's own constructor throws
	 * {@code MissingReflectionRegistrationError} under native-image, and
	 * every consumer wanting a native-image build would have to hand-write a
	 * {@code reflect-config.json} entry per {@code @RestClient} interface -
	 * exactly the ergonomics problem this whole feature exists to remove
	 * (see the design doc's §1.1). Emitting this here, once per generated
	 * class, keeps that config in sync with the generated source
	 * automatically - zero extra consumer configuration, the same property
	 * every other part of this feature already has.
	 */
	private void writeNativeImageReflectConfig(String qualifiedImplName, TypeElement interfaceElement)
			throws IOException {
		String resourcePath = "META-INF/native-image/com.shri.restinpeace/" + qualifiedImplName
				+ "/reflect-config.json";
		FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
				resourcePath, interfaceElement);
		try (Writer writer = resource.openWriter()) {
			writer.write("[\n" //
					+ "  {\n" //
					+ "    \"name\": \"" + qualifiedImplName + "\",\n" //
					+ "    \"methods\": [\n" //
					+ "      {\n" //
					+ "        \"name\": \"<init>\",\n" //
					+ "        \"parameterTypes\": [\"com.shri.restinpeace.annotation.service.RestRequestProcessor\"]\n" //
					+ "      }\n" //
					+ "    ]\n" //
					+ "  }\n" //
					+ "]\n");
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
			appendMethod(out, method, interfaceBaseUrl, interfaceName);
		}

		out.append("}\n");
		return out.toString();
	}

	private void appendMethod(StringBuilder out, MethodModel method, String interfaceBaseUrl, String interfaceName) {
		out.append("\t@Override\n\tpublic ").append(method.returnModel.javaTypeName).append(" ").append(method.name)
				.append("(");
		for (int i = 0; i < method.params.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			ParamModel param = method.params.get(i);
			out.append(param.javaTypeName).append(" ").append(param.javaParamName);
		}
		out.append(") {\n");

		ParamModel urlParam = urlParamOf(method);
		if (urlParam != null) {
			out.append("\t\tString __ripUrl = com.shri.restinpeace.annotation.service.RestRequestProcessor.requireUrlParam(")
					.append(urlParam.javaParamName).append(", ")
					.append(stringLiteral(interfaceName + "." + method.name)).append(");\n");
		} else {
			out.append("\t\tString __ripUrl = this.ripProcessor.resolveGeneratedUrl(")
					.append(stringLiteral(method.urlTemplate)).append(", ")
					.append(interfaceBaseUrl == null ? "null" : stringLiteral(interfaceBaseUrl)).append(", ")
					.append(namesArrayLiteral(method, ParamKind.PATH)).append(", ")
					.append(valuesArrayLiteral(method, ParamKind.PATH)).append(");\n");
		}

		String httpMethodLiteral = "com.shri.restinpeace.constant.HTTPMethod." + method.httpMethod.name();
		out.append("\t\tcom.shri.restinpeace.interceptor.RequestContext __ripContext = new com.shri.restinpeace.interceptor.RequestContext(")
				.append(httpMethodLiteral).append(", __ripUrl);\n");
		out.append("\t\tkong.unirest.HttpRequest<?> __ripRequest = this.ripProcessor.createGeneratedRequest(")
				.append(httpMethodLiteral).append(", __ripUrl, ").append(method.timeout.connectMillis).append(", ")
				.append(method.timeout.readMillis).append(");\n");

		if (method.headerEntries.length > 0) {
			out.append("\t\tthis.ripProcessor.applyGeneratedHeaders(__ripRequest, ")
					.append(stringArrayLiteral(method.headerEntries)).append(");\n");
		}

		if (method.isMultipart) {
			out.append(
					"\t\tkong.unirest.MultipartBody __ripMultipart = this.ripProcessor.beginGeneratedMultipart(__ripRequest);\n");
			out.append("\t\t__ripRequest = __ripMultipart;\n");
		}
		if (method.isFormUrlEncoded) {
			out.append("\t\tjava.util.List<String> __ripFormFields = new java.util.ArrayList<>();\n");
		}

		for (ParamModel param : method.params) {
			appendParamApplication(out, param);
		}

		if (method.isFormUrlEncoded) {
			out.append(
					"\t\t__ripRequest = this.ripProcessor.applyFormUrlEncodedBody(__ripRequest, __ripFormFields);\n");
		}

		String errorTypeLiteral = method.errorTypeClassName == null ? "null" : method.errorTypeClassName + ".class";
		String retryArgsLiteral = method.retry.hasRetry + ", " + method.retry.times + ", " + method.retry.delayMillis
				+ "L, " + method.retry.backoffMultiplier + ", " + intArrayLiteral(method.retry.retryOnStatus);
		boolean async = method.returnModel.isAsync;

		switch (method.returnModel.kind) {
		case PLAIN:
			String finishPlain = async ? "finishGeneratedAsync" : "finishGeneratedSync";
			String resultType = async ? "java.util.concurrent.CompletableFuture<?>" : "Object";
			out.append("\t\t").append(resultType).append(" __ripResult = this.ripProcessor.").append(finishPlain)
					.append("(__ripRequest, __ripContext, ").append(method.returnModel.decodeTypeName)
					.append(".class, ").append(errorTypeLiteral).append(", ").append(retryArgsLiteral)
					.append(");\n");
			if (!"void".equals(method.returnModel.decodeTypeName) || async) {
				out.append("\t\treturn (").append(method.returnModel.javaTypeName).append(") __ripResult;\n");
			}
			break;
		case BYTES:
			out.append("\t\treturn this.ripProcessor.")
					.append(async ? "finishGeneratedAsyncBytes" : "finishGeneratedSyncBytes")
					.append("(__ripRequest, __ripContext, ").append(errorTypeLiteral).append(", ")
					.append(retryArgsLiteral).append(");\n");
			break;
		case FILE:
			out.append("\t\treturn this.ripProcessor.")
					.append(async ? "finishGeneratedAsyncFile" : "finishGeneratedSyncFile")
					.append("(__ripRequest, __ripContext, ").append(destinationParamOf(method).javaParamName)
					.append(", ").append(errorTypeLiteral).append(", ").append(retryArgsLiteral).append(");\n");
			break;
		case RIP_RESPONSE:
			if ("byte[]".equals(method.returnModel.decodeTypeName)) {
				out.append("\t\treturn this.ripProcessor.")
						.append(async ? "finishGeneratedAsyncRipResponseBytes" : "finishGeneratedSyncRipResponseBytes")
						.append("(__ripRequest, __ripContext, ").append(errorTypeLiteral).append(", ")
						.append(retryArgsLiteral).append(");\n");
			} else {
				String finishRipResponse = async ? "finishGeneratedAsyncRipResponse" : "finishGeneratedSyncRipResponse";
				String ripResultType = async ? "java.util.concurrent.CompletableFuture<com.shri.restinpeace.RipResponse<?>>"
						: "com.shri.restinpeace.RipResponse<?>";
				out.append("\t\t").append(ripResultType).append(" __ripResult = this.ripProcessor.")
						.append(finishRipResponse).append("(__ripRequest, __ripContext, ")
						.append(method.returnModel.decodeTypeName).append(".class, ").append(errorTypeLiteral)
						.append(", ").append(retryArgsLiteral).append(");\n");
				out.append("\t\treturn (").append(method.returnModel.javaTypeName).append(") __ripResult;\n");
			}
			break;
		default:
			throw new IllegalStateException("Unhandled return kind: " + method.returnModel.kind);
		}
		out.append("\t}\n\n");
	}

	private static ParamModel destinationParamOf(MethodModel method) {
		for (ParamModel param : method.params) {
			if (param.kind == ParamKind.DESTINATION) {
				return param;
			}
		}
		throw new IllegalStateException("Expected a @Destination parameter for a File return type.");
	}

	private void appendParamApplication(StringBuilder out, ParamModel param) {
		switch (param.kind) {
		case PATH:
		case URL:
			return; // already consumed while resolving the URL
		case QUERY:
			out.append("\t\t{\n\t\t\tObject __ripValue = this.ripProcessor.resolveValue(").append(param.javaParamName)
					.append(", ").append(param.required).append(", ").append(stringLiteral(param.defaultValue))
					.append(", ").append(stringLiteral(param.name)).append(");\n");
			out.append("\t\t\tif (__ripValue != null) { this.ripProcessor.applyQueryValue(__ripRequest, ")
					.append(stringLiteral(param.name)).append(", __ripValue); }\n\t\t}\n");
			return;
		case HEADER:
			out.append("\t\t{\n\t\t\tObject __ripValue = this.ripProcessor.resolveValue(").append(param.javaParamName)
					.append(", ").append(param.required).append(", ").append(stringLiteral(param.defaultValue))
					.append(", ").append(stringLiteral(param.name)).append(");\n");
			out.append("\t\t\tif (__ripValue != null) { __ripRequest.headerReplace(")
					.append(stringLiteral(param.name)).append(", String.valueOf(__ripValue)); }\n\t\t}\n");
			return;
		case QUERY_MAP:
			out.append("\t\tif (").append(param.javaParamName)
					.append(" != null) { this.ripProcessor.applyQueryMap(__ripRequest, ").append(param.javaParamName)
					.append("); }\n");
			return;
		case HEADER_MAP:
			out.append("\t\tif (").append(param.javaParamName)
					.append(" != null) { this.ripProcessor.applyHeaderMap(__ripRequest, ").append(param.javaParamName)
					.append("); }\n");
			return;
		case BODY:
			out.append("\t\t__ripRequest = this.ripProcessor.applyGeneratedBodyIfPresent(__ripRequest, ")
					.append(param.javaParamName).append(");\n");
			return;
		case PART:
			out.append("\t\t{\n\t\t\tObject __ripValue = this.ripProcessor.resolveValue(").append(param.javaParamName)
					.append(", ").append(param.required)
					.append(", com.shri.restinpeace.constant.RIPConstant.DEFAULT, ")
					.append(stringLiteral(param.name)).append(");\n");
			out.append("\t\t\tif (__ripValue != null) { this.ripProcessor.applyPartValue(__ripMultipart, ")
					.append(stringLiteral(param.name)).append(", ").append(stringLiteral(param.fileName))
					.append(", __ripValue); }\n\t\t}\n");
			return;
		case PART_MAP:
			out.append("\t\tif (").append(param.javaParamName)
					.append(" != null) { this.ripProcessor.applyPartMap(__ripMultipart, ")
					.append(param.javaParamName).append("); }\n");
			return;
		case UPLOAD_PROGRESS:
			out.append("\t\tif (").append(param.javaParamName)
					.append(" != null) { this.ripProcessor.applyUploadMonitor(__ripMultipart, ")
					.append(param.javaParamName).append("); }\n");
			return;
		case FIELD:
			out.append("\t\t{\n\t\t\tObject __ripValue = this.ripProcessor.resolveValue(").append(param.javaParamName)
					.append(", ").append(param.required)
					.append(", com.shri.restinpeace.constant.RIPConstant.DEFAULT, ")
					.append(stringLiteral(param.name)).append(");\n");
			out.append("\t\t\tif (__ripValue != null) { this.ripProcessor.appendFormField(__ripFormFields, ")
					.append(stringLiteral(param.name)).append(", __ripValue); }\n\t\t}\n");
			return;
		case FIELD_MAP:
			out.append("\t\tif (").append(param.javaParamName)
					.append(" != null) { this.ripProcessor.appendFormFieldMap(__ripFormFields, ")
					.append(param.javaParamName).append("); }\n");
			return;
		case DESTINATION:
			return; // consumed directly when calling finishGeneratedSyncFile
		case DOWNLOAD_PROGRESS:
			out.append("\t\tif (").append(param.javaParamName)
					.append(" != null) { this.ripProcessor.applyDownloadMonitor(__ripRequest, ")
					.append(param.javaParamName).append("); }\n");
			return;
		default:
			throw new IllegalStateException("Unhandled param kind: " + param.kind);
		}
	}

	private static ParamModel urlParamOf(MethodModel method) {
		for (ParamModel param : method.params) {
			if (param.kind == ParamKind.URL) {
				return param;
			}
		}
		return null;
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

	private static String stringArrayLiteral(String[] values) {
		StringBuilder out = new StringBuilder("new String[] {");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(stringLiteral(values[i]));
		}
		return out.append("}").toString();
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
		PATH, QUERY, HEADER, QUERY_MAP, HEADER_MAP, BODY, URL, PART, PART_MAP, FIELD, FIELD_MAP, UPLOAD_PROGRESS,
		DESTINATION, DOWNLOAD_PROGRESS
	}

	private enum ReturnKind {
		PLAIN, BYTES, FILE, RIP_RESPONSE
	}

	private static final class ReturnModel {
		final ReturnKind kind;
		final boolean isAsync;
		final String javaTypeName;
		final String decodeTypeName;

		ReturnModel(ReturnKind kind, boolean isAsync, String javaTypeName, String decodeTypeName) {
			this.kind = kind;
			this.isAsync = isAsync;
			this.javaTypeName = javaTypeName;
			this.decodeTypeName = decodeTypeName;
		}
	}

	private static final class ParamModel {
		final ParamKind kind;
		final String name;
		final String javaParamName;
		final String javaTypeName;
		final boolean required;
		final String defaultValue;
		final String fileName;

		ParamModel(ParamKind kind, String name, String javaParamName, String javaTypeName, boolean required,
				String defaultValue, String fileName) {
			this.kind = kind;
			this.name = name;
			this.javaParamName = javaParamName;
			this.javaTypeName = javaTypeName;
			this.required = required;
			this.defaultValue = defaultValue;
			this.fileName = fileName;
		}
	}

	private static final class MethodModel {
		final String name;
		final HTTPMethod httpMethod;
		final String urlTemplate;
		final ReturnModel returnModel;
		final List<ParamModel> params;
		final TimeoutModel timeout;
		final RetryModel retry;
		final String[] headerEntries;
		final String errorTypeClassName;
		final boolean isMultipart;
		final boolean isFormUrlEncoded;

		MethodModel(String name, HTTPMethod httpMethod, String urlTemplate, ReturnModel returnModel,
				List<ParamModel> params, TimeoutModel timeout, RetryModel retry, String[] headerEntries,
				String errorTypeClassName, boolean isMultipart, boolean isFormUrlEncoded) {
			this.name = name;
			this.httpMethod = httpMethod;
			this.urlTemplate = urlTemplate;
			this.returnModel = returnModel;
			this.params = params;
			this.timeout = timeout;
			this.retry = retry;
			this.headerEntries = headerEntries;
			this.errorTypeClassName = errorTypeClassName;
			this.isMultipart = isMultipart;
			this.isFormUrlEncoded = isFormUrlEncoded;
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
