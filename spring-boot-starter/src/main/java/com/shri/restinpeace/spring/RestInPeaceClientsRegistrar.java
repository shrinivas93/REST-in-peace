package com.shri.restinpeace.spring;

import java.beans.Introspector;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
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
 * constructed.
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

		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(RestInPeaceClientFactoryBean.class)
				.addConstructorArgValue(restClientInterface).addConstructorArgValue(resolveClientConfig(metadata, beanName));
		registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
	}

	private RipClientConfig resolveClientConfig(RestClient metadata, String beanName) {
		RipClientConfig.Builder builder = RipClientConfig.builder();
		if (!metadata.baseUrlProperty().isEmpty()) {
			builder.baseUrl(environment.getRequiredProperty(metadata.baseUrlProperty()));
		}

		RestInPeaceClientProperties properties = Binder.get(environment)
				.bind("rest-in-peace.clients." + toKebabCase(beanName), Bindable.of(RestInPeaceClientProperties.class))
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
		return builder.build();
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
	 * passed to {@link Binder#bind(String, Bindable)}.
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
