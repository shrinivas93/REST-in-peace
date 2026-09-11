package com.shri.restinpeace.spring;

import java.beans.Introspector;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
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
 */
final class RestInPeaceClientsRegistrar implements ImportBeanDefinitionRegistrar {

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
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(RestInPeaceClientFactoryBean.class)
				.addConstructorArgValue(restClientInterface);
		String beanName = Introspector.decapitalize(restClientInterface.getSimpleName());
		registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
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
