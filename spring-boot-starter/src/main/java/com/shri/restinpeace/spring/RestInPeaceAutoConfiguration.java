package com.shri.restinpeace.spring;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
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
 *
 * <p>
 * {@code RIP.addInterceptor}'s underlying registry is a single JVM-static
 * list shared by every RIP client in the process, not scoped to any one
 * Spring {@code ApplicationContext} - so more than one context sharing a
 * JVM (a parameterized {@code @SpringBootTest} that varies a property,
 * defeating Spring's context cache; a multi-tenant host embedding several
 * contexts) would otherwise accumulate every context's interceptor beans
 * forever, since nothing ever removed a closed context's own. Implementing
 * {@link DisposableBean} and reversing on {@link #destroy()} exactly the
 * interceptor instances this context's own {@link #afterPropertiesSet()}
 * added - via {@link RIP#removeInterceptor}, not
 * {@link RIP#clearInterceptors()}, which would also wipe out any other
 * still-live context's interceptors - keeps that from happening.
 */
@AutoConfiguration
public class RestInPeaceAutoConfiguration implements InitializingBean, DisposableBean {

	private final ConfigurableListableBeanFactory beanFactory;
	private final List<RequestInterceptor> registeredInterceptors = new ArrayList<>();

	RestInPeaceAutoConfiguration(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void afterPropertiesSet() {
		RIP.useDaemonThreadsForAsync();
		for (String beanName : beanFactory.getBeanNamesForType(RequestInterceptor.class)) {
			if (RestInPeaceBeanQualifiers.qualifierValue(beanFactory, beanName).isEmpty()) {
				RequestInterceptor interceptor = beanFactory.getBean(beanName, RequestInterceptor.class);
				RIP.addInterceptor(interceptor);
				registeredInterceptors.add(interceptor);
			}
		}
	}

	@Override
	public void destroy() {
		registeredInterceptors.forEach(RIP::removeInterceptor);
		registeredInterceptors.clear();
	}

}
