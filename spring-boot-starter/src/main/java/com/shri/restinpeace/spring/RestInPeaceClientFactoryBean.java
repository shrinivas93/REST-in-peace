package com.shri.restinpeace.spring;

import org.springframework.beans.factory.config.AbstractFactoryBean;

import com.shri.restinpeace.RIP;

/**
 * Constructs one {@code @RestClient} interface's client, once, via
 * {@link RIP#getClient(Class)} - the same single choke point a hand-written
 * {@code @Bean} method already calls. {@link AbstractFactoryBean} caches the
 * result, so {@link RIP#getClient(Class)} - which re-validates and
 * re-resolves its interface on every call - runs exactly once per
 * interface, at this bean's first use, not once per injection point.
 *
 * @param <T> the {@code @RestClient} interface type
 */
final class RestInPeaceClientFactoryBean<T> extends AbstractFactoryBean<T> {

	private final Class<T> restClientInterface;

	RestInPeaceClientFactoryBean(Class<T> restClientInterface) {
		this.restClientInterface = restClientInterface;
	}

	@Override
	public Class<?> getObjectType() {
		return restClientInterface;
	}

	@Override
	protected T createInstance() {
		return RIP.getClient(restClientInterface);
	}

}
