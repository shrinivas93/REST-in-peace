package com.shri.restinpeace.spring;

import org.springframework.beans.factory.config.AbstractFactoryBean;

import com.shri.restinpeace.RIP;

/**
 * Constructs one {@code @RestClient} interface's client, once, via
 * {@link RIP#getClient(Class)} (or {@link RIP#getClient(Class, String)}
 * when {@code baseUrl} is resolved from
 * {@link RestInPeaceClient#baseUrlProperty()}) - the same single choke
 * point a hand-written {@code @Bean} method already calls.
 * {@link AbstractFactoryBean} caches the result, so that call - which
 * re-validates and re-resolves its interface every time - runs exactly
 * once per interface, at this bean's first use, not once per injection
 * point.
 *
 * @param <T> the {@code @RestClient} interface type
 */
final class RestInPeaceClientFactoryBean<T> extends AbstractFactoryBean<T> {

	private final Class<T> restClientInterface;
	private final String baseUrl;

	/**
	 * @param restClientInterface the {@code @RestClient} interface to construct
	 * @param baseUrl             the base URL resolved from
	 *                            {@link RestInPeaceClient#baseUrlProperty()},
	 *                            or {@code null} when unset - the interface
	 *                            must then resolve its own base URL entirely
	 *                            on its own
	 */
	RestInPeaceClientFactoryBean(Class<T> restClientInterface, String baseUrl) {
		this.restClientInterface = restClientInterface;
		this.baseUrl = baseUrl;
	}

	@Override
	public Class<?> getObjectType() {
		return restClientInterface;
	}

	@Override
	protected T createInstance() {
		return baseUrl != null ? RIP.getClient(restClientInterface, baseUrl) : RIP.getClient(restClientInterface);
	}

}
