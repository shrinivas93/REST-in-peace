package com.shri.restinpeace.spring;

import java.beans.Introspector;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

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
 * {@link RestClient#baseUrlProperty()} against the real {@code Environment}
 * at bean-registration time, before any client is constructed.
 */
final class RestInPeaceClientsRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

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

		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(RestInPeaceClientFactoryBean.class)
				.addConstructorArgValue(restClientInterface).addConstructorArgValue(resolveBaseUrl(metadata));
		registry.registerBeanDefinition(resolveBeanName(restClientInterface, metadata), builder.getBeanDefinition());
	}

	/**
	 * @return the base URL {@code metadata}'s {@code baseUrlProperty} names,
	 *         or {@code null} when left unset - the interface must then
	 *         resolve its own base URL entirely on its own
	 */
	private String resolveBaseUrl(RestClient metadata) {
		if (metadata.baseUrlProperty().isEmpty()) {
			return null;
		}
		return environment.getRequiredProperty(metadata.baseUrlProperty());
	}

	private String resolveBeanName(Class<?> restClientInterface, RestClient metadata) {
		if (!metadata.name().isEmpty()) {
			return metadata.name();
		}
		return Introspector.decapitalize(restClientInterface.getSimpleName());
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
