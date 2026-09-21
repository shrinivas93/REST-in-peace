package com.shri.restinpeace.internal;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.shri.restinpeace.Page;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;
import com.shri.restinpeace.annotation.pagination.PointerKind;
import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.HttpResponse;

/**
 * Turns a {@code @Paginated} method's settled per-page {@link HttpResponse}
 * into a {@link Page}: extracting the items array and the termination
 * signals (§6.5 of {@code docs/design/pagination-helper.md}) from the
 * response's JSON body/headers, and - while there's a next page - building
 * a {@link Page#next()} that re-invokes the fetch with the pointer
 * substituted in, exactly as if the annotated method were called again by
 * hand (§6.9). Extracted out of {@link RequestExecutor} since pagination
 * extraction is a genuinely separate concern, the same reasoning behind
 * {@link CacheCoordinator}/{@link RetryExecutor}/{@link InterceptorDispatcher}
 * living here too.
 *
 * <p>
 * {@link RequestExecutor} owns actually building and executing each page's
 * request (through the client's full retry/cache/circuit-breaker/bulkhead/
 * interceptor pipeline, unchanged) - this class only ever sees the settled
 * response, via the {@code pageFetch} callback passed into
 * {@link #fetchFirstPage}, and never touches {@code HttpRequest} construction
 * itself.
 */
final class PaginationCoordinator {

	private final ResponseDecoder responseDecoder;

	PaginationCoordinator(ResponseDecoder responseDecoder) {
		this.responseDecoder = responseDecoder;
	}

	/**
	 * Resolves a {@code @Paginated} method's item type {@code T} from its
	 * {@code Page<T>} return type - assumes the method already passed
	 * {@code ReflectiveRestClientValidator}, which guarantees the return
	 * type actually is a parameterized {@code Page<T>}.
	 */
	Type resolveItemType(Method method) {
		Type genericReturnType = method.getGenericReturnType();
		if (!(genericReturnType instanceof ParameterizedType)) {
			throw new RestInPeaceException(
					String.format("The method %s returns a raw Page with no type parameter.", method));
		}
		Type itemType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
		if (!(itemType instanceof Class) && !(itemType instanceof ParameterizedType)) {
			throw new RestInPeaceException(
					String.format("The method %s returns Page<%s>, which is not a supported type parameter.", method,
							itemType));
		}
		return itemType;
	}

	/** Returns the index of the method's one {@code @PaginationCursor} parameter, or {@code -1} if it has none. */
	int findCursorParamIndex(Method method) {
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].getAnnotation(PaginationCursor.class) != null) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Fetches the first page and returns it - {@link Page#next()} on the
	 * result (and on every page after it) recurses back through
	 * {@code pageFetch} the same way, threading the running fetched-items/
	 * fetched-pages counters forward for the termination precedence.
	 *
	 * @param method          the annotated method, for exception messages
	 * @param paginated       the method's {@code @Paginated} annotation
	 * @param itemType        {@code T}, resolved via {@link #resolveItemType}
	 * @param cursorParamIndex the {@code @PaginationCursor} parameter's index, or
	 *                        {@code -1} for a {@code pointerKind = FULL_URL} method
	 * @param errorType       the class to decode a non-2xx response's body into,
	 *                        or {@code null} for none
	 * @param initialArgs     the original call's argument values
	 * @param pageFetch       executes one page's HTTP call - {@code (args, urlOverride) -> response},
	 *                        {@code urlOverride} non-{@code null} only for a
	 *                        {@code FULL_URL} pointer's re-fetch
	 * @return the first page
	 */
	Page<Object> fetchFirstPage(Method method, Paginated paginated, Type itemType, int cursorParamIndex,
			Class<?> errorType, Object[] initialArgs,
			BiFunction<Object[], String, HttpResponse<String>> pageFetch) {
		return fetchPage(method, paginated, itemType, cursorParamIndex, errorType, initialArgs, null, 0, 0, pageFetch);
	}

	private Page<Object> fetchPage(Method method, Paginated paginated, Type itemType, int cursorParamIndex,
			Class<?> errorType, Object[] args, String urlOverride, int itemsFetchedSoFar, int pagesFetchedSoFar,
			BiFunction<Object[], String, HttpResponse<String>> pageFetch) {
		HttpResponse<String> response = pageFetch.apply(args, urlOverride);
		String rawBody = (String) responseDecoder.decodeOrThrow(response, errorType, String.class);
		JsonElement bodyTree = parseBody(rawBody, method);

		JsonElement itemsElement = paginated.itemsField().isEmpty() ? bodyTree
				: getPath(bodyTree, paginated.itemsField());
		if (itemsElement == null || !itemsElement.isJsonArray()) {
			throw new RestInPeaceException(String.format(
					"The @Paginated method %s expected an items array at '%s' but found %s.", method,
					paginated.itemsField().isEmpty() ? "the response body itself" : paginated.itemsField(),
					itemsElement == null ? "nothing" : itemsElement));
		}
		List<Object> items = decodeItems(itemsElement.getAsJsonArray(), itemType);

		JsonElement pointerElement = extractElement(bodyTree, response, paginated.pointerSource(),
				paginated.pointerField());
		String pointerValue = pointerElement == null ? null : pointerElement.getAsString();

		Boolean hasMore = toBoolean(extractElement(bodyTree, response, paginated.hasMoreSource(),
				paginated.hasMoreField()), method, "hasMoreField");
		Integer total = toInt(
				extractElement(bodyTree, response, paginated.totalSource(), paginated.totalField()), method,
				"totalField");
		Integer totalPages = toInt(extractElement(bodyTree, response, paginated.totalPagesSource(),
				paginated.totalPagesField()), method, "totalPagesField");

		int itemsFetchedTotal = itemsFetchedSoFar + items.size();
		int pagesFetchedTotal = pagesFetchedSoFar + 1;

		boolean hasNext;
		if (hasMore != null) {
			hasNext = hasMore;
		} else if (total != null) {
			hasNext = itemsFetchedTotal < total;
		} else if (totalPages != null) {
			hasNext = pagesFetchedTotal < totalPages;
		} else {
			hasNext = pointerValue != null && !pointerValue.isEmpty();
		}
		if (items.isEmpty()) {
			// Unconditional safety net (§6.5 step 5) - guards against a server bug
			// (stale hasMore/total) turning this into an infinite loop, regardless of
			// which termination signal said to continue.
			hasNext = false;
		}

		RipResponse<Void> rawResponse = new RipResponse<>(response.getStatus(),
				ResponseDecoder.toHeaderMap(response.getHeaders()), null);

		if (!hasNext) {
			return new PageImpl(items, false, rawResponse, null);
		}
		if (pointerValue == null || pointerValue.isEmpty()) {
			throw new RestInPeaceException(String.format(
					"The @Paginated method %s indicated another page exists but no next-page pointer could be "
							+ "extracted (pointerField '%s').",
					method, paginated.pointerField()));
		}

		Supplier<Page<Object>> nextPageSupplier;
		if (paginated.pointerKind() == PointerKind.FULL_URL) {
			nextPageSupplier = () -> fetchPage(method, paginated, itemType, cursorParamIndex, errorType, args,
					pointerValue, itemsFetchedTotal, pagesFetchedTotal, pageFetch);
		} else {
			// Coercing the extracted value into the cursor parameter's declared type is
			// deferred into the supplier (evaluated only when next() is actually called)
			// rather than done eagerly here - a malformed value is a problem with
			// fetching the NEXT page, not with the page already successfully returned.
			nextPageSupplier = () -> {
				Object[] nextArgs = args.clone();
				nextArgs[cursorParamIndex] = coerceCursorValue(pointerValue,
						method.getParameters()[cursorParamIndex].getType(), method);
				return fetchPage(method, paginated, itemType, cursorParamIndex, errorType, nextArgs, null,
						itemsFetchedTotal, pagesFetchedTotal, pageFetch);
			};
		}
		return new PageImpl(items, true, rawResponse, nextPageSupplier);
	}

	private JsonElement parseBody(String rawBody, Method method) {
		if (rawBody == null || rawBody.isEmpty()) {
			throw new RestInPeaceException(
					String.format("The @Paginated method %s received an empty response body.", method));
		}
		try {
			return JsonParser.parseString(rawBody);
		} catch (JsonParseException e) {
			throw new RestInPeaceException(
					String.format("The @Paginated method %s received a response body that isn't valid JSON.", method),
					e);
		}
	}

	/** Dotted-path {@code get} against a parsed body tree - the response-side counterpart of a request-side {@code set} (§6.7, a later chunk). */
	private JsonElement getPath(JsonElement root, String dottedPath) {
		JsonElement current = root;
		for (String segment : dottedPath.split("\\.")) {
			if (current == null || !current.isJsonObject()) {
				return null;
			}
			current = current.getAsJsonObject().get(segment);
		}
		return current;
	}

	/**
	 * Extracts one signal (pointer/hasMore/total/totalPages) per its own
	 * {@code source}/{@code field} pair - a header value is wrapped in a
	 * {@link JsonPrimitive} so callers can read it via the same
	 * {@code JsonElement} API regardless of source, matching an absent
	 * header ({@code Headers.getFirst(...)} returns {@code ""}, not
	 * {@code null}, for one that wasn't sent) to {@code null} the same way
	 * an absent/{@code null} body field already is.
	 */
	private JsonElement extractElement(JsonElement bodyTree, HttpResponse<String> response,
			PaginationSignalSource source, String field) {
		if (source == PaginationSignalSource.NONE || field.isEmpty()) {
			return null;
		}
		if (source == PaginationSignalSource.RESPONSE_HEADER) {
			String value = response.getHeaders().getFirst(field);
			return (value == null || value.isEmpty()) ? null : new JsonPrimitive(value);
		}
		JsonElement element = getPath(bodyTree, field);
		return (element == null || element.isJsonNull()) ? null : element;
	}

	private Boolean toBoolean(JsonElement element, Method method, String attributeName) {
		if (element == null) {
			return null;
		}
		try {
			return element.getAsBoolean();
		} catch (RuntimeException e) {
			throw new RestInPeaceException(String.format(
					"The @Paginated method %s's %s extracted a value that isn't a boolean: %s.", method,
					attributeName, element), e);
		}
	}

	private Integer toInt(JsonElement element, Method method, String attributeName) {
		if (element == null) {
			return null;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException e) {
			throw new RestInPeaceException(String.format(
					"The @Paginated method %s's %s extracted a value that isn't a number: %s.", method, attributeName,
					element), e);
		}
	}

	private Object coerceCursorValue(String rawValue, Class<?> paramType, Method method) {
		if (paramType == String.class) {
			return rawValue;
		}
		if (paramType == int.class || paramType == Integer.class) {
			try {
				return Integer.parseInt(rawValue);
			} catch (NumberFormatException e) {
				throw new RestInPeaceException(String.format(
						"The @Paginated method %s extracted a non-numeric next-page value '%s' for an int "
								+ "@PaginationCursor parameter.",
						method, rawValue), e);
			}
		}
		if (paramType == long.class || paramType == Long.class) {
			try {
				return Long.parseLong(rawValue);
			} catch (NumberFormatException e) {
				throw new RestInPeaceException(String.format(
						"The @Paginated method %s extracted a non-numeric next-page value '%s' for a long "
								+ "@PaginationCursor parameter.",
						method, rawValue), e);
			}
		}
		throw new RestInPeaceException(String.format(
				"The @Paginated method %s has a @PaginationCursor parameter of unsupported type %s.", method,
				paramType.getName()));
	}

	@SuppressWarnings("unchecked")
	private List<Object> decodeItems(JsonArray itemsArray, Type itemType) {
		Type listType = listOf(itemType);
		return (List<Object>) responseDecoder.getObjectMapper().readValue(itemsArray.toString(),
				RuntimeGenericType.of(listType));
	}

	private static Type listOf(Type itemType) {
		return new ParameterizedType() {
			@Override
			public Type[] getActualTypeArguments() {
				return new Type[] { itemType };
			}

			@Override
			public Type getRawType() {
				return List.class;
			}

			@Override
			public Type getOwnerType() {
				return null;
			}
		};
	}

	private static final class PageImpl implements Page<Object> {

		private final List<Object> items;
		private final boolean hasNext;
		private final RipResponse<Void> rawResponse;
		private final Supplier<Page<Object>> nextPageSupplier;

		PageImpl(List<Object> items, boolean hasNext, RipResponse<Void> rawResponse,
				Supplier<Page<Object>> nextPageSupplier) {
			this.items = items;
			this.hasNext = hasNext;
			this.rawResponse = rawResponse;
			this.nextPageSupplier = nextPageSupplier;
		}

		@Override
		public List<Object> items() {
			return items;
		}

		@Override
		public boolean hasNext() {
			return hasNext;
		}

		@Override
		public Page<Object> next() {
			if (!hasNext) {
				throw new RestInPeaceException("This is the last page - check hasNext() before calling next().");
			}
			return nextPageSupplier.get();
		}

		@Override
		public RipResponse<Void> rawResponse() {
			return rawResponse;
		}

	}

}
