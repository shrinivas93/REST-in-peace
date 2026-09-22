package com.shri.restinpeace.internal;

import java.util.List;

import com.google.gson.JsonElement;
import com.shri.restinpeace.PaginationContext;

import kong.unirest.HttpResponse;

/** Straightforward immutable {@link PaginationContext} - one page's already-decoded items plus its raw response. */
final class PaginationContextImpl<T> implements PaginationContext<T> {

	private final List<T> items;
	private final JsonElement rawBody;
	private final HttpResponse<String> response;
	private final int pagesFetchedSoFar;
	private final int itemsFetchedSoFar;

	PaginationContextImpl(List<T> items, JsonElement rawBody, HttpResponse<String> response, int pagesFetchedSoFar,
			int itemsFetchedSoFar) {
		this.items = items;
		this.rawBody = rawBody;
		this.response = response;
		this.pagesFetchedSoFar = pagesFetchedSoFar;
		this.itemsFetchedSoFar = itemsFetchedSoFar;
	}

	@Override
	public List<T> items() {
		return items;
	}

	@Override
	public JsonElement rawBody() {
		return rawBody;
	}

	@Override
	public String header(String name) {
		// Headers.getFirst(...) returns "", not null, for a header that wasn't sent -
		// normalized to null here so a strategy's Optional.ofNullable(ctx.header(...))
		// correctly reads an absent header as absent, matching the declarative path's
		// own extractElement normalization for the exact same Unirest quirk.
		String value = response.getHeaders().getFirst(name);
		return (value == null || value.isEmpty()) ? null : value;
	}

	@Override
	public int pagesFetchedSoFar() {
		return pagesFetchedSoFar;
	}

	@Override
	public int itemsFetchedSoFar() {
		return itemsFetchedSoFar;
	}

}
