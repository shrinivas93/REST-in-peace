package com.shri.restinpeace.spring;

import org.springframework.beans.factory.config.AbstractFactoryBean;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipClientConfig;

/**
 * Constructs one {@code @RestClient} interface's client, once, via
 * {@link RIP#getClient(Class, RipClientConfig)} - the same single choke
 * point a hand-written {@code @Bean} method already calls.
 * {@link AbstractFactoryBean} caches the result, so that call - which
 * re-validates and re-resolves its interface every time - runs exactly
 * once per interface, at this bean's first use, not once per injection
 * point.
 *
 * <p>
 * A {@code config} with every field left unset behaves identically to
 * {@link RIP#getClient(Class)} - see {@link RipClientConfig}'s own javadoc -
 * so this factory bean always goes through the {@code RipClientConfig}
 * overload rather than branching between it and the simpler ones.
 *
 * @param <T> the {@code @RestClient} interface type
 */
final class RestInPeaceClientFactoryBean<T> extends AbstractFactoryBean<T> {

	private final Class<T> restClientInterface;
	private final RipClientConfig config;

	/**
	 * @param restClientInterface the {@code @RestClient} interface to construct
	 * @param config              this client's resolved base URL, timeout, and
	 *                            proxy settings
	 */
	RestInPeaceClientFactoryBean(Class<T> restClientInterface, RipClientConfig config) {
		this.restClientInterface = restClientInterface;
		this.config = config;
	}

	@Override
	public Class<?> getObjectType() {
		return restClientInterface;
	}

	@Override
	protected T createInstance() {
		return RIP.getClient(restClientInterface, config);
	}

}
