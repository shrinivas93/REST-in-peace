package com.shri.restinpeace.spring;

import java.beans.Introspector;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.interceptor.RequestInterceptor;

import kong.unirest.ObjectMapper;

/**
 * Backs {@link EnableRestInPeaceClients}: scans
 * {@link EnableRestInPeaceClients#basePackages()} for {@code @RestClient}
 * interfaces and registers one {@link RestInPeaceClientFactoryBean} per
 * interface found.
 *
 * <p>
 * A plain {@link ClassPathScanningCandidateComponentProvider} only ever
 * considers concrete, non-abstract classes a candidate - an interface is
 * always abstract, so it's filtered out before {@code @RestClient} is even
 * checked. Overriding {@code isCandidateComponent(AnnotatedBeanDefinition)}
 * to accept any independent (top-level or static nested) type instead is
 * the same fix MyBatis-Spring's own mapper-interface scanner uses for the
 * identical problem.
 *
 * <p>
 * Implements {@link EnvironmentAware} - a standard Spring extension point
 * for {@link ImportBeanDefinitionRegistrar} - purely to resolve
 * {@link RestClient#baseUrlProperty()} and this client's
 * {@code rest-in-peace.clients.<name>.*} timeout/proxy settings against the
 * real {@code Environment} at bean-registration time, before any client is
 * constructed. {@code ObjectMapper}/{@code Cache}/{@code RequestInterceptor}
 * beans are handled differently: only bean *names* are resolved here (via
 * {@link RestInPeaceBeanQualifiers}, reading definition metadata rather than
 * instantiating anything), wired onto {@link RestInPeaceClientFactoryBean}
 * as ordinary property references Spring itself resolves later, at that
 * factory bean's own creation time.
 */
final class RestInPeaceClientsRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

	private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
		AnnotationAttributes attributes = AnnotationAttributes
				.fromMap(importingClassMetadata.getAnnotationAttributes(EnableRestInPeaceClients.class.getName()));
		String[] basePackages = attributes.getStringArray("basePackages");
		if (basePackages.length == 0) {
			basePackages = new String[] { ClassUtils.getPackageName(importingClassMetadata.getClassName()) };
		}

		ClassPathScanningCandidateComponentProvider scanner = createScanner();
		for (String basePackage : basePackages) {
			for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
				registerClient(candidate.getBeanClassName(), registry);
			}
		}
	}

	private void registerClient(String restClientInterfaceName, BeanDefinitionRegistry registry) {
		Class<?> restClientInterface;
		try {
			restClientInterface = ClassUtils.forName(restClientInterfaceName, getClass().getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(
					String.format("Could not load @RestClient interface %s.", restClientInterfaceName), e);
		}
		RestClient metadata = restClientInterface.getAnnotation(RestClient.class);
		String beanName = resolveBeanName(restClientInterface, metadata);
		String qualifier = toKebabCase(beanName);

		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(RestInPeaceClientFactoryBean.class)
				.addConstructorArgValue(restClientInterface).addConstructorArgValue(resolveConfigBuilder(metadata, qualifier));

		if (registry instanceof ConfigurableListableBeanFactory beanFactory) {
			wireOptionalBeans(beanFactory, builder, qualifier);
		}

		registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
	}

	private RipClientConfig.Builder resolveConfigBuilder(RestClient metadata, String qualifier) {
		RipClientConfig.Builder builder = RipClientConfig.builder();
		if (!metadata.baseUrlProperty().isEmpty()) {
			builder.baseUrl(environment.getRequiredProperty(metadata.baseUrlProperty()));
		}

		RestInPeaceClientProperties properties = Binder.get(environment)
				.bind("rest-in-peace.clients." + qualifier, Bindable.of(RestInPeaceClientProperties.class))
				.orElseGet(RestInPeaceClientProperties::new);
		if (properties.getConnectTimeoutMillis() != null) {
			builder.connectTimeoutMillis(properties.getConnectTimeoutMillis());
		}
		if (properties.getReadTimeoutMillis() != null) {
			builder.readTimeoutMillis(properties.getReadTimeoutMillis());
		}
		if (properties.getProxy() != null) {
			RestInPeaceClientProperties.Proxy proxy = properties.getProxy();
			builder.proxy(proxy.getHost(), proxy.getPort(), proxy.getUsername(), proxy.getPassword());
		}
		return builder;
	}

	/**
	 * Wires this client's {@code objectMapper}/{@code cache}/
	 * {@code interceptors} properties onto {@code builder}'s eventual
	 * {@link RestInPeaceClientFactoryBean} as bean references, resolved by
	 * Spring only when that factory bean is itself created - see
	 * {@link RestInPeaceBeanQualifiers} for how the candidate bean name(s)
	 * are found without instantiating anything this early.
	 */
	private void wireOptionalBeans(ConfigurableListableBeanFactory beanFactory, BeanDefinitionBuilder builder,
			String qualifier) {
		RestInPeaceBeanQualifiers.findQualifiedOrSharedBean(beanFactory, ObjectMapper.class, qualifier)
				.ifPresent(name -> builder.addPropertyReference("objectMapper", name));
		RestInPeaceBeanQualifiers.findQualifiedOrSharedBean(beanFactory, Cache.class, qualifier)
				.ifPresent(name -> builder.addPropertyReference("cache", name));

		List<String> interceptorBeanNames = RestInPeaceBeanQualifiers.findQualifiedBeans(beanFactory,
				RequestInterceptor.class, qualifier);
		if (!interceptorBeanNames.isEmpty()) {
			ManagedList<RuntimeBeanReference> interceptorRefs = new ManagedList<>(interceptorBeanNames.size());
			for (String interceptorBeanName : interceptorBeanNames) {
				interceptorRefs.add(new RuntimeBeanReference(interceptorBeanName));
			}
			builder.addPropertyValue("interceptors", interceptorRefs);
		}
	}

	private String resolveBeanName(Class<?> restClientInterface, RestClient metadata) {
		if (!metadata.name().isEmpty()) {
			return metadata.name();
		}
		return Introspector.decapitalize(restClientInterface.getSimpleName());
	}

	/**
	 * {@link org.springframework.boot.context.properties.source.ConfigurationPropertyName#of(CharSequence)}
	 * only accepts an already-canonical (kebab-case) name - relaxed binding
	 * matches a canonical name against differently-cased property source
	 * keys, but never accepts a differently-cased name to parse in the first
	 * place. A derived bean name like {@code pingApi} has to become
	 * {@code ping-api} before it can be used as part of the property path
	 * passed to {@link Binder#bind(String, Bindable)} - the same kebab-case
	 * form doubles as this client's {@code @Qualifier} value for
	 * {@code ObjectMapper}/{@code Cache}/{@code RequestInterceptor} beans
	 * (§4.4), matching the YAML client key exactly.
	 *
	 * @param name a bean name, e.g. {@code pingApi} or {@code user-api}
	 * @return {@code name} in kebab-case, e.g. {@code ping-api}
	 */
	private static String toKebabCase(String name) {
		return CAMEL_CASE_BOUNDARY.matcher(name).replaceAll("$1-$2").toLowerCase();
	}

	private static ClassPathScanningCandidateComponentProvider createScanner() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				return beanDefinition.getMetadata().isIndependent();
			}
		};
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestClient.class));
		return scanner;
	}

}
