package com.shri.restinpeace.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Reads a bean's {@code @Qualifier} value, if any, straight off its
 * {@link BeanDefinition} metadata - without instantiating the bean itself,
 * unlike {@link org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils#qualifiedBeanOfType}.
 * Needed because {@link RestInPeaceClientsRegistrar} decides which
 * {@code ObjectMapper}/{@code Cache}/{@code RequestInterceptor} bean(s) to
 * reference for a client at bean-*registration* time, deferring actual
 * resolution to normal Spring dependency injection (a property reference on
 * {@link RestInPeaceClientFactoryBean}, resolved only when that factory bean
 * is itself first created) rather than fetching an instance eagerly here,
 * well before singletons are ready.
 */
final class RestInPeaceBeanQualifiers {

	private RestInPeaceBeanQualifiers() {
	}

	/**
	 * @param beanFactory the bean factory to read bean definition metadata from
	 * @param beanName    the candidate bean's name
	 * @return the bean's {@code @Qualifier} value, or empty if it carries none
	 */
	static Optional<String> qualifierValue(ConfigurableListableBeanFactory beanFactory, String beanName) {
		BeanDefinition beanDefinition = beanFactory.getMergedBeanDefinition(beanName);
		if (!(beanDefinition instanceof AnnotatedBeanDefinition annotatedBeanDefinition)) {
			return Optional.empty();
		}
		AnnotatedTypeMetadata metadata = annotatedBeanDefinition.getFactoryMethodMetadata() != null
				? annotatedBeanDefinition.getFactoryMethodMetadata()
				: annotatedBeanDefinition.getMetadata();
		MergedAnnotations annotations = metadata.getAnnotations();
		MergedAnnotation<Qualifier> qualifier = annotations.get(Qualifier.class);
		return qualifier.isPresent() ? qualifier.getValue("value", String.class) : Optional.empty();
	}

	/**
	 * Finds the bean of {@code type} to use for {@code clientQualifier}: one
	 * explicitly qualified for it if present, otherwise the single
	 * unqualified bean of that type shared by every client without its own -
	 * see {@code docs/design/spring-boot-starter.md} §4.4.
	 *
	 * @param beanFactory      the bean factory to search
	 * @param type             the bean type to look for
	 * @param clientQualifier  the client's own qualifier value (its
	 *                         kebab-case name)
	 * @return the bean name to reference, or empty if neither a qualified nor
	 *         an unambiguous shared default bean exists
	 */
	static Optional<String> findQualifiedOrSharedBean(ConfigurableListableBeanFactory beanFactory, Class<?> type,
			String clientQualifier) {
		String[] candidates = beanFactory.getBeanNamesForType(type, false, false);
		List<String> unqualified = new ArrayList<>();
		for (String candidate : candidates) {
			Optional<String> qualifier = qualifierValue(beanFactory, candidate);
			if (qualifier.isPresent()) {
				if (qualifier.get().equals(clientQualifier)) {
					return Optional.of(candidate);
				}
			} else {
				unqualified.add(candidate);
			}
		}
		return unqualified.size() == 1 ? Optional.of(unqualified.get(0)) : Optional.empty();
	}

	/**
	 * Finds every bean of {@code type} explicitly qualified for
	 * {@code clientQualifier} - unlike {@link #findQualifiedOrSharedBean}, a
	 * client may have more than one (e.g. several interceptors), and there's
	 * no shared-default fallback: an unqualified bean of this type is never
	 * assumed to belong to a specific client.
	 *
	 * @param beanFactory     the bean factory to search
	 * @param type            the bean type to look for
	 * @param clientQualifier the client's own qualifier value (its
	 *                        kebab-case name)
	 * @return every matching bean's name, in factory-reported order; empty if
	 *         none match
	 */
	static List<String> findQualifiedBeans(ConfigurableListableBeanFactory beanFactory, Class<?> type,
			String clientQualifier) {
		List<String> matches = new ArrayList<>();
		for (String candidate : beanFactory.getBeanNamesForType(type, false, false)) {
			if (qualifierValue(beanFactory, candidate).filter(clientQualifier::equals).isPresent()) {
				matches.add(candidate);
			}
		}
		return matches;
	}

}
