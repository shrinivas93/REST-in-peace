package com.shri.restinpeace.spring;

import java.util.List;

import org.springframework.beans.factory.config.AbstractFactoryBean;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.interceptor.RequestInterceptor;

import kong.unirest.ObjectMapper;

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
 * {@code configBuilder} arrives with base URL, timeout, and proxy already
 * set by {@link RestInPeaceClientsRegistrar} (resolved eagerly, at
 * bean-registration time - see that class's own javadoc), but {@code
 * objectMapper}/{@code cache}/{@code interceptors} are wired here as
 * ordinary bean properties instead, resolved by Spring at this factory
 * bean's own creation time - the normal, lazy point in the bean lifecycle a
 * hand-written {@code @Bean} method's own {@code @Autowired} parameters
 * would resolve at too, rather than eagerly during bean *registration*
 * before the beans they'd reference are necessarily safe to instantiate.
 * {@link #createInstance()} only calls {@code configBuilder.build()} once
 * every property has already been set.
 *
 * @param <T> the {@code @RestClient} interface type
 */
final class RestInPeaceClientFactoryBean<T> extends AbstractFactoryBean<T> {

	private final Class<T> restClientInterface;
	private final RipClientConfig.Builder configBuilder;

	/**
	 * @param restClientInterface the {@code @RestClient} interface to construct
	 * @param configBuilder       this client's config, with base URL, timeout,
	 *                            and proxy already set
	 */
	RestInPeaceClientFactoryBean(Class<T> restClientInterface, RipClientConfig.Builder configBuilder) {
		this.restClientInterface = restClientInterface;
		this.configBuilder = configBuilder;
	}

	@Override
	public Class<?> getObjectType() {
		return restClientInterface;
	}

	/**
	 * @param objectMapper this client's qualified {@code ObjectMapper} bean,
	 *                     or the shared unqualified default - see
	 *                     {@link RestInPeaceBeanQualifiers#findQualifiedOrSharedBean}
	 */
	public void setObjectMapper(ObjectMapper objectMapper) {
		configBuilder.objectMapper(objectMapper);
	}

	/**
	 * @param cache this client's qualified {@code Cache} bean, or the shared
	 *              unqualified default
	 */
	public void setCache(Cache cache) {
		configBuilder.cache(cache);
	}

	/**
	 * @param interceptors every {@code RequestInterceptor} bean qualified
	 *                     specifically for this client
	 */
	public void setInterceptors(List<RequestInterceptor> interceptors) {
		configBuilder.interceptors(interceptors);
	}

	@Override
	protected T createInstance() {
		return RIP.getClient(restClientInterface, configBuilder.build());
	}

}
