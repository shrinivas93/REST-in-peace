package com.shri.restinpeace.spring;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.mock.MockRestServer;

/**
 * Backs {@link AutoConfigureMockRestServer}: starts a {@link MockRestServer}
 * (stopped automatically via its {@code close()} method when the context
 * closes - Spring's default destroy-method inference already covers
 * {@code AutoCloseable}), and overrides every registered client's base URL
 * to point at it.
 *
 * <p>
 * The override itself works directly on each {@link RestInPeaceClientFactoryBean}'s
 * {@link RipClientConfig.Builder} - the very same, still-mutable builder
 * instance {@link RestInPeaceClientsRegistrar} already stored as that
 * factory bean's constructor argument, reached here via the bean
 * *definition* before any factory bean is instantiated. A plain
 * {@link BeanFactoryPostProcessor} is guaranteed to run after every
 * {@code @RestClient} interface has already been scanned and registered
 * (registrars are a kind of {@code BeanDefinitionRegistryPostProcessor},
 * and every one of those runs to completion before any plain
 * {@code BeanFactoryPostProcessor} does) but before any bean - including
 * the {@link MockRestServer} bean this same post-processor also looks up -
 * is actually constructed.
 */
@Configuration
class MockRestServerTestConfiguration {

	@Bean
	MockRestServer mockRestServer() {
		return MockRestServer.start();
	}

	@Bean
	static BeanFactoryPostProcessor mockRestServerBaseUrlOverride() {
		return MockRestServerTestConfiguration::overrideRegisteredClientBaseUrls;
	}

	private static void overrideRegisteredClientBaseUrls(ConfigurableListableBeanFactory beanFactory) {
		MockRestServer server = beanFactory.getBean(MockRestServer.class);
		for (String beanName : beanFactory.getBeanNamesForType(RestInPeaceClientFactoryBean.class)) {
			// getBeanNamesForType(FactoryBean subtype) returns the "&"-dereferenced
			// name, which getBeanDefinition(...) itself never accepts.
			BeanDefinition definition = beanFactory.getBeanDefinition(BeanFactoryUtils.transformedBeanName(beanName));
			// BeanDefinitionBuilder.addConstructorArgValue stores indexed, not
			// generic, argument values.
			for (ValueHolder argument : definition.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
				if (argument.getValue() instanceof RipClientConfig.Builder configBuilder) {
					configBuilder.baseUrl(server.baseUrl());
				}
			}
		}
	}

}
