package com.shri.restinpeace.spring;

/**
 * One client's settings under {@code rest-in-peace.clients.<name>.*} -
 * connect/read timeout and proxy, bound by {@link RestInPeaceClientsRegistrar}
 * straight off the {@code Environment} via Spring Boot's relaxed-binding
 * {@code Binder}, the same eager, bean-registration-time resolution
 * {@code baseUrlProperty} already gets. Plain JavaBean shape (not a Java
 * record) so binding needs no {@code -parameters} compiler flag.
 *
 * <p>
 * Every field is optional: a client with no matching {@code clients.<name>}
 * section keeps sharing the app-wide static Unirest client and its default
 * timeouts, exactly as if this class didn't exist - see
 * {@link com.shri.restinpeace.RipClientConfig}'s own javadoc.
 */
final class RestInPeaceClientProperties {

	private Integer connectTimeoutMillis;
	private Integer readTimeoutMillis;
	private Proxy proxy;

	Integer getConnectTimeoutMillis() {
		return connectTimeoutMillis;
	}

	void setConnectTimeoutMillis(Integer connectTimeoutMillis) {
		this.connectTimeoutMillis = connectTimeoutMillis;
	}

	Integer getReadTimeoutMillis() {
		return readTimeoutMillis;
	}

	void setReadTimeoutMillis(Integer readTimeoutMillis) {
		this.readTimeoutMillis = readTimeoutMillis;
	}

	Proxy getProxy() {
		return proxy;
	}

	void setProxy(Proxy proxy) {
		this.proxy = proxy;
	}

	/** A client's proxy settings, under {@code clients.<name>.proxy}. */
	static final class Proxy {

		private String host;
		private int port;
		private String username;
		private String password;

		String getHost() {
			return host;
		}

		void setHost(String host) {
			this.host = host;
		}

		int getPort() {
			return port;
		}

		void setPort(int port) {
			this.port = port;
		}

		String getUsername() {
			return username;
		}

		void setUsername(String username) {
			this.username = username;
		}

		String getPassword() {
			return password;
		}

		void setPassword(String password) {
			this.password = password;
		}

	}

}
