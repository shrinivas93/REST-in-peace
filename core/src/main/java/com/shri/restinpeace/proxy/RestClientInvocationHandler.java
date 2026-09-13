package com.shri.restinpeace.proxy;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
import com.shri.restinpeace.internal.RequestExecutor;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.RipClientConfig;

/**
 * JDK dynamic proxy handler backing every client returned by
 * {@link com.shri.restinpeace.RIP#getClient(Class)}. Routes {@code Object}
 * methods ({@code toString}/{@code equals}/{@code hashCode}) to
 * proxy-aware implementations, a default interface method (e.g. a
 * convenience wrapper calling another method on the same {@code @RestClient}
 * interface) to its own real implementation, and every other method call to
 * {@link RequestExecutor} based on its HTTP method annotation.
 */
public class RestClientInvocationHandler implements InvocationHandler {

	/**
	 * {@code MethodHandles.privateLookupIn(Class, Lookup)}, resolved
	 * reflectively since it was only added in Java 9 and this module
	 * compiles against the Java 8 API surface - {@code null} on an actual
	 * Java 8 runtime, where {@link #createPrivateLookup} falls back to the
	 * older, JVM-version-fragile {@code Lookup} constructor trick instead.
	 * Resolved once, lazily, the first time a default method is ever
	 * invoked - never in a static initializer, so a JVM where neither
	 * mechanism works can't break class loading (and every other, unrelated
	 * use of this class) merely by being loaded; the failure only surfaces
	 * if an actual default method call needs it.
	 */
	private static final Method PRIVATE_LOOKUP_IN = resolvePrivateLookupIn();

	private static Method resolvePrivateLookupIn() {
		try {
			return MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
		} catch (NoSuchMethodException javaEightRuntime) {
			return null;
		}
	}

	private final RequestExecutor restRequestProcessor;

	/** Creates a handler backed by a fresh {@link RequestExecutor} with no runtime base URL override. */
	public RestClientInvocationHandler() {
		this((String) null);
	}

	/**
	 * Creates a handler backed by a fresh {@link RequestExecutor} that
	 * resolves every relative method URL against {@code baseUrlOverride}.
	 *
	 * @param baseUrlOverride the runtime base URL, or {@code null} to fall
	 *                        back to the interface's {@code @BaseUrl}
	 */
	public RestClientInvocationHandler(String baseUrlOverride) {
		this.restRequestProcessor = new RequestExecutor(baseUrlOverride);
	}

	/**
	 * Creates a handler backed by a fresh {@link RequestExecutor} built
	 * from {@code config}.
	 *
	 * @param config the per-client settings
	 */
	public RestClientInvocationHandler(RipClientConfig config) {
		this.restRequestProcessor = new RequestExecutor(config);
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) {
			return invokeObjectMethod(proxy, method, args);
		}
		if (method.isDefault()) {
			return invokeDefaultMethod(proxy, method, args);
		}
		HTTPMethod httpMethod = getHTTPMethod(method);
		return restRequestProcessor.processRestRequest(method, httpMethod, args);
	}

	/**
	 * Invokes {@code method}'s own default implementation on the interface
	 * that declares it, as if this proxy weren't in the way - a plain
	 * {@code method.invoke(proxy, args)} would instead re-enter this same
	 * proxy's generated override of that method (infinite recursion),
	 * since a JDK dynamic proxy always provides a concrete, handler-routed
	 * override for every interface method, default ones included.
	 */
	private Object invokeDefaultMethod(Object proxy, Method method, Object[] args) throws Throwable {
		Class<?> declaringClass = method.getDeclaringClass();
		return createPrivateLookup(declaringClass).unreflectSpecial(method, declaringClass).bindTo(proxy)
				.invokeWithArguments(args);
	}

	/**
	 * A {@link MethodHandles.Lookup} with {@code PRIVATE}-mode access to
	 * {@code declaringClass}, needed by {@link MethodHandles.Lookup#unreflectSpecial}
	 * to invoke a default method as the interface's own code would. Prefers
	 * {@link #PRIVATE_LOOKUP_IN} (Java 9+, always works regardless of
	 * {@code declaringClass}'s own visibility - no {@code --add-opens}
	 * needed); falls back to directly instantiating {@code Lookup} via its
	 * non-public constructor otherwise, for an actual Java 8 runtime.
	 *
	 * <p>
	 * On that Java 8 fallback specifically, {@code declaringClass} (the
	 * {@code @RestClient} interface declaring the default method) must
	 * itself be {@code public} - verified directly against a real Java 8
	 * runtime; a package-private/private interface throws
	 * {@code IllegalAccessException: class is not public} from
	 * {@code unreflectSpecial} despite the {@code PRIVATE}-mode lookup,
	 * seemingly a Java-8-specific quirk of this constructor trick (every
	 * {@code @RestClient} interface in this library's own docs/samples is
	 * already {@code public}, the normal shape for an interface
	 * {@code RIP.getClient(...)} is called on from arbitrary caller
	 * packages). Not a concern on Java 9+, where {@link #PRIVATE_LOOKUP_IN}
	 * is used instead and has no such requirement.
	 */
	private static MethodHandles.Lookup createPrivateLookup(Class<?> declaringClass) {
		try {
			if (PRIVATE_LOOKUP_IN != null) {
				return (MethodHandles.Lookup) PRIVATE_LOOKUP_IN.invoke(null, declaringClass, MethodHandles.lookup());
			}
			Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
					.getDeclaredConstructor(Class.class, int.class);
			constructor.setAccessible(true);
			return constructor.newInstance(declaringClass, MethodHandles.Lookup.PRIVATE);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new RestInPeaceException(String.format(
					"Could not invoke a default method declared on %s - default interface methods on a "
							+ "@RestClient may not be supported on this JVM.",
					declaringClass.getName()), e);
		}
	}

	private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
		switch (method.getName()) {
		case "toString":
			return "RestClient[" + proxy.getClass().getInterfaces()[0].getName() + "]";
		case "hashCode":
			return System.identityHashCode(proxy);
		case "equals":
			return proxy == args[0];
		default:
			throw new RestInPeaceException(String.format("Unsupported Object method %s.", method));
		}
	}

	private HTTPMethod getHTTPMethod(Method method) {
		List<Annotation> httpAnnotations = Stream.of(method.getAnnotations()).filter(this::isHTTPMethod)
				.collect(Collectors.toList());
		if (httpAnnotations.size() > 1) {
			throw new RestInPeaceException(String
					.format("The interface method %s is annotated with more than 1 HTTP Method", method.toString()));
		}
		Annotation httpAnnotation = httpAnnotations.stream().findFirst()
				.orElseThrow(() -> new RestInPeaceException(String.format(
						"The interface method %s is not annotated with any of the HTTP Method annotation",
						method.toString())));
		return httpAnnotation.annotationType().getAnnotation(HTTPMethodMarker.class).value();
	}

	private boolean isHTTPMethod(Annotation annotation) {
		return annotation.annotationType().getAnnotation(HTTPMethodMarker.class) != null;
	}

}
