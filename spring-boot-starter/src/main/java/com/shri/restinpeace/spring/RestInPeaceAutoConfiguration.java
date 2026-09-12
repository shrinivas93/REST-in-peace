package com.shri.restinpeace.spring;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.interceptor.RequestInterceptor;

/**
 * Auto-configuration for the Spring Boot starter.
 *
 * <p>
 * A Spring Boot app is long-running, so calls
 * {@link RIP#useDaemonThreadsForAsync()} once at startup as a sane default -
 * a long-running Spring app has no reason to want non-daemon I/O threads
 * outliving its own shutdown (see the design doc's §5).
 *
 * <p>
 * Also registers every {@code RequestInterceptor} bean with no client
 * {@code @Qualifier} globally, once, via {@link RIP#addInterceptor}
 * (§4.4) - a cross-cutting concern meant for every client, the same as a
 * hand-written {@code RIP.addInterceptor(...)} call at application startup.
 * One qualified to a specific client name is deliberately skipped here -
 * {@link RestInPeaceClientsRegistrar} wires it into that client's own
 * {@code RipClientConfig} instead, never both.
 */
@AutoConfiguration
public class RestInPeaceAutoConfiguration implements InitializingBean {

	private final ConfigurableListableBeanFactory beanFactory;

	RestInPeaceAutoConfiguration(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void afterPropertiesSet() {
		RIP.useDaemonThreadsForAsync();
		for (String beanName : beanFactory.getBeanNamesForType(RequestInterceptor.class)) {
			if (RestInPeaceBeanQualifiers.qualifierValue(beanFactory, beanName).isEmpty()) {
				RIP.addInterceptor(beanFactory.getBean(beanName, RequestInterceptor.class));
			}
		}
	}

}
