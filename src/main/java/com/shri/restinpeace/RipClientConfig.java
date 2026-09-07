package com.shri.restinpeace;

import java.util.Collections;
import java.util.List;

import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.interceptor.RequestInterceptor;

import kong.unirest.ObjectMapper;

/**
 * Per-client settings for {@link RIP#getClient(Class, RipClientConfig)} -
 * base URL, connect/read timeout, proxy, JSON {@code ObjectMapper}, cache,
 * and interceptors - for a {@code @RestClient} whose environment differs
 * from every other client's,
 * since {@code kong.unirest.Unirest}'s own global config is shared by every
 * RIP client that doesn't ask for its own. A method's {@link
 * com.shri.restinpeace.annotation.timeout.Timeout @Timeout} overrides this
 * config's timeout when both are present, the same way an absolute method
 * URL overrides {@link #getBaseUrl()}.
 *
 * <pre>
 * UserApi prodApi = RIP.getClient(UserApi.class, RipClientConfig.builder()
 *         .baseUrl(prodBaseUrl)
 *         .connectTimeoutMillis(2_000)
 *         .readTimeoutMillis(10_000)
 *         .build());
 * </pre>
 *
 * Setting a connect/read timeout, a proxy, or an {@code objectMapper} gives
 * the client its own dedicated {@code kong.unirest.UnirestInstance} (its own
 * connection pool) instead of sharing the app-wide static {@code Unirest}
 * client - a client built with only {@link #getBaseUrl()} set keeps sharing
 * the static client, same as {@link RIP#getClient(Class, String)}.
 */
public final class RipClientConfig {

	private final String baseUrl;
	private final Integer connectTimeoutMillis;
	private final Integer readTimeoutMillis;
	private final String proxyHost;
	private final int proxyPort;
	private final String proxyUsername;
	private final String proxyPassword;
	private final ObjectMapper objectMapper;
	private final Cache cache;
	private final List<RequestInterceptor> interceptors;

	private RipClientConfig(Builder builder) {
		this.baseUrl = builder.baseUrl;
		this.connectTimeoutMillis = builder.connectTimeoutMillis;
		this.readTimeoutMillis = builder.readTimeoutMillis;
		this.proxyHost = builder.proxyHost;
		this.proxyPort = builder.proxyPort;
		this.proxyUsername = builder.proxyUsername;
		this.proxyPassword = builder.proxyPassword;
		this.objectMapper = builder.objectMapper;
		this.cache = builder.cache;
		this.interceptors = builder.interceptors;
	}

	/**
	 * Starts building a new config.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the runtime base URL.
	 *
	 * @return the runtime base URL, or {@code null} to fall back to
	 *         {@code @BaseUrl} on the interface
	 */
	public String getBaseUrl() {
		return baseUrl;
	}

	/**
	 * Returns the connect timeout.
	 *
	 * @return the connect timeout in milliseconds, or {@code null} to use
	 *         the shared client's configured default
	 */
	public Integer getConnectTimeoutMillis() {
		return connectTimeoutMillis;
	}

	/**
	 * Returns the read (socket) timeout.
	 *
	 * @return the read (socket) timeout in milliseconds, or {@code null} to
	 *         use the shared client's configured default
	 */
	public Integer getReadTimeoutMillis() {
		return readTimeoutMillis;
	}

	/**
	 * Returns the proxy host.
	 *
	 * @return the proxy host, or {@code null} if no proxy is configured
	 */
	public String getProxyHost() {
		return proxyHost;
	}

	/**
	 * Returns the proxy port.
	 *
	 * @return the proxy port; meaningless if {@link #getProxyHost()} is {@code null}
	 */
	public int getProxyPort() {
		return proxyPort;
	}

	/**
	 * Returns the proxy username.
	 *
	 * @return the proxy username, or {@code null} for an unauthenticated proxy
	 */
	public String getProxyUsername() {
		return proxyUsername;
	}

	/**
	 * Returns the proxy password.
	 *
	 * @return the proxy password, or {@code null} for an unauthenticated proxy
	 */
	public String getProxyPassword() {
		return proxyPassword;
	}

	/**
	 * Returns the JSON {@code ObjectMapper} for this client.
	 *
	 * @return the JSON {@code ObjectMapper} for this client, or {@code null}
	 *         to use the shared client's configured default (Unirest's own
	 *         Gson-backed {@code JsonObjectMapper}, unless changed via
	 *         {@link RIP#setObjectMapper(ObjectMapper)})
	 */
	public ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	/**
	 * Returns the {@code Cache} for this client.
	 *
	 * @return this client's own {@code Cache}, or {@code null} to fall back
	 *         to the shared default set via {@link RIP#setCache(Cache)} (or
	 *         no caching at all, if that's never called either)
	 */
	public Cache getCache() {
		return cache;
	}

	/**
	 * Returns this client's own interceptors.
	 *
	 * @return this client's own interceptors, run in addition to (not instead
	 *         of) every globally registered {@link RIP#addInterceptor
	 *         interceptor} - empty if none were set
	 */
	public List<RequestInterceptor> getInterceptors() {
		return interceptors;
	}

	/** Builds a {@link RipClientConfig}. */
	public static final class Builder {

		private String baseUrl;
		private Integer connectTimeoutMillis;
		private Integer readTimeoutMillis;
		private String proxyHost;
		private int proxyPort;
		private String proxyUsername;
		private String proxyPassword;
		private ObjectMapper objectMapper;
		private Cache cache;
		private List<RequestInterceptor> interceptors = Collections.emptyList();

		private Builder() {
		}

		/**
		 * Sets the runtime base URL.
		 *
		 * @param baseUrl the runtime base URL to resolve relative method URLs
		 *                against
		 * @return this builder
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Sets the connect timeout.
		 *
		 * @param connectTimeoutMillis the connect timeout in milliseconds; must
		 *                             not be negative
		 * @return this builder
		 */
		public Builder connectTimeoutMillis(int connectTimeoutMillis) {
			if (connectTimeoutMillis < 0) {
				throw new IllegalArgumentException("connectTimeoutMillis must not be negative.");
			}
			this.connectTimeoutMillis = connectTimeoutMillis;
			return this;
		}

		/**
		 * Sets the read (socket) timeout.
		 *
		 * @param readTimeoutMillis the read (socket) timeout in milliseconds;
		 *                          must not be negative
		 * @return this builder
		 */
		public Builder readTimeoutMillis(int readTimeoutMillis) {
			if (readTimeoutMillis < 0) {
				throw new IllegalArgumentException("readTimeoutMillis must not be negative.");
			}
			this.readTimeoutMillis = readTimeoutMillis;
			return this;
		}

		/**
		 * Sets an unauthenticated proxy.
		 *
		 * @param host the proxy host
		 * @param port the proxy port
		 * @return this builder
		 */
		public Builder proxy(String host, int port) {
			return proxy(host, port, null, null);
		}

		/**
		 * Sets an authenticated proxy.
		 *
		 * @param host     the proxy host
		 * @param port     the proxy port
		 * @param username the proxy username
		 * @param password the proxy password
		 * @return this builder
		 */
		public Builder proxy(String host, int port, String username, String password) {
			this.proxyHost = host;
			this.proxyPort = port;
			this.proxyUsername = username;
			this.proxyPassword = password;
			return this;
		}

		/**
		 * Sets the JSON {@code ObjectMapper} for this client.
		 *
		 * @param objectMapper the JSON {@code ObjectMapper} for this client
		 * @return this builder
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the {@code Cache} for this client, overriding the shared
		 * default set via {@link RIP#setCache(Cache)} for this client only.
		 * Only {@code GET} responses are ever cached, and only when the
		 * response itself carries a {@code Cache-Control max-age}, an
		 * {@code ETag}, or a {@code Last-Modified} to honor.
		 *
		 * @param cache the cache to use for this client
		 * @return this builder
		 */
		public Builder cache(Cache cache) {
			this.cache = cache;
			return this;
		}

		/**
		 * Sets this client's own interceptors, run in addition to (not
		 * instead of) every globally registered {@link RIP#addInterceptor
		 * interceptor} - for a concern specific to this one client (e.g. this
		 * service's own auth scheme) instead of every call RIP ever makes.
		 * Global interceptors run first in {@code beforeRequest} (bracketing
		 * everything, including this client's own) and last in
		 * {@code afterResponse}, the same "onion" ordering
		 * {@link com.shri.restinpeace.interceptor.RequestInterceptor}'s own
		 * javadoc describes for interceptors registered globally.
		 *
		 * @param interceptors this client's own interceptors
		 * @return this builder
		 */
		public Builder interceptors(List<RequestInterceptor> interceptors) {
			this.interceptors = interceptors == null ? Collections.emptyList() : interceptors;
			return this;
		}

		/**
		 * Builds the config.
		 *
		 * @return the built config
		 */
		public RipClientConfig build() {
			return new RipClientConfig(this);
		}
	}

}
