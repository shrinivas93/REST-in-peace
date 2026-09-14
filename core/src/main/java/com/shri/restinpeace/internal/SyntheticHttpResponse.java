package com.shri.restinpeace.internal;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import kong.unirest.Cookies;
import kong.unirest.HttpMethod;
import kong.unirest.HttpRequestSummary;
import kong.unirest.HttpResponse;
import kong.unirest.UnirestParsingException;

/**
 * A {@code kong.unirest.HttpResponse} not backed by an actual network round
 * trip - handed to the same {@code decodeOrThrow}/{@code notifyAfterResponse}/
 * {@code wrapResponse} machinery a real response would go through, so a
 * synthetic response (a cache hit, an interceptor short-circuit) is decoded,
 * reported to interceptors, and wrapped in a {@code RipResponse} exactly
 * like any other response. Only {@link #getStatus()}/{@link #getBody()}/
 * {@link #getHeaders()} are ever actually exercised by that machinery; the
 * rest of this interface is implemented plainly (a synthetic response is
 * never itself a failure status by construction, since callers only ever
 * build one from data they already consider a successful stand-in).
 */
final class SyntheticHttpResponse<T> implements HttpResponse<T> {
	private final int status;
	private final kong.unirest.Headers headers;
	private final T body;

	SyntheticHttpResponse(int status, kong.unirest.Headers headers, T body) {
		this.status = status;
		this.headers = headers;
		this.body = body;
	}

	@Override
	public int getStatus() {
		return status;
	}

	@Override
	public String getStatusText() {
		return "";
	}

	@Override
	public kong.unirest.Headers getHeaders() {
		return headers;
	}

	@Override
	public T getBody() {
		return body;
	}

	@Override
	public Optional<UnirestParsingException> getParsingError() {
		return Optional.empty();
	}

	@Override
	public <V> V mapBody(Function<T, V> func) {
		return func.apply(body);
	}

	@Override
	public <V> HttpResponse<V> map(Function<T, V> func) {
		return new SyntheticHttpResponse<>(status, headers, func.apply(body));
	}

	@Override
	public HttpResponse<T> ifSuccess(Consumer<HttpResponse<T>> consumer) {
		if (isSuccess()) {
			consumer.accept(this);
		}
		return this;
	}

	@Override
	public HttpResponse<T> ifFailure(Consumer<HttpResponse<T>> consumer) {
		if (!isSuccess()) {
			consumer.accept(this);
		}
		return this;
	}

	@Override
	public <E> HttpResponse<T> ifFailure(Class<? extends E> type, Consumer<HttpResponse<E>> consumer) {
		return this;
	}

	@Override
	public boolean isSuccess() {
		return status >= 200 && status < 300;
	}

	@Override
	public <E> E mapError(Class<? extends E> type) {
		return null;
	}

	@Override
	public Cookies getCookies() {
		return new Cookies();
	}

	@Override
	public HttpRequestSummary getRequestSummary() {
		return new HttpRequestSummary() {
			@Override
			public HttpMethod getHttpMethod() {
				return HttpMethod.GET;
			}

			@Override
			public String getUrl() {
				return "";
			}

			@Override
			public String getRawPath() {
				return "";
			}

			@Override
			public String asString() {
				return "GET";
			}
		};
	}
}
