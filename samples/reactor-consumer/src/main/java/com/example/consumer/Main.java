package com.example.consumer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.reactor.RestInPeaceReactor;

import com.sun.net.httpserver.HttpServer;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;

import com.example.consumer.OrderApi.Order;

/**
 * A standalone consumer program - a completely separate Maven build from
 * REST-in-peace's own - demonstrating {@code rest-in-peace-reactor} the way
 * a real downstream user experiences it: add the published jar as an
 * ordinary dependency alongside the core library, call
 * {@link RestInPeaceReactor#register()} once, and every {@code Mono<T>}/
 * {@code Flux<T>}-returning method on an ordinary {@code @RestClient}
 * interface just works - no other configuration (see
 * {@code docs/design/reactor-call-adapter.md}).
 *
 * <p>
 * See this directory's {@code README.md} for how to build and run it (after
 * {@code mvn install}-ing the parent {@code rest-in-peace} project and
 * {@code rest-in-peace-reactor} first).
 */
public final class Main {

	public static void main(String[] args) throws Exception {
		AtomicInteger pagedFetchCount = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/orders/paged", exchange -> {
			pagedFetchCount.incrementAndGet();
			String query = exchange.getRequestURI().getRawQuery();
			// First page has no cursor query param at all; the second page's
			// request carries the cursor the first page's own "next" pointed at.
			String body = (query == null || !query.contains("cursor=page2"))
					? "{\"orders\":[{\"id\":\"p1\"}],\"next\":\"page2\"}"
					: "{\"orders\":[{\"id\":\"p2\"}]}"; // no "next" - this is the last page
			respond(exchange, body);
		});
		server.createContext("/orders/", exchange -> {
			String id = exchange.getRequestURI().getPath().substring("/orders/".length());
			respond(exchange, "{\"id\":\"" + id + "\"}");
		});
		server.createContext("/orders", exchange -> respond(exchange, "[{\"id\":\"1\"},{\"id\":\"2\"}]"));
		server.start();
		String baseUrl = "http://localhost:" + server.getAddress().getPort();

		try {
			// Every Mono<T>/Flux<T> call below dispatches through Unirest's async
			// client, whose I/O threads are non-daemon by default - opting into
			// daemon threads here lets this short-lived program exit on its own
			// once main() returns, instead of hanging (see RIP.useDaemonThreadsForAsync's
			// own javadoc: call once at startup, before building any client).
			RIP.useDaemonThreadsForAsync();
			RestInPeaceReactor.register();
			OrderApi api = RIP.getClient(OrderApi.class, baseUrl);

			// 1. Mono<T>: a single response, decoded and emitted eagerly (the HTTP
			// call already went out by the time getOrder(...) returns).
			Order order = api.getOrder("42").block();
			requireEquals("42", order.id, "Mono<Order> getOrder(\"42\")");
			System.out.println("getOrder(\"42\") returned order id: " + order.id);

			// 2. Flux<T> flavor 1 (§7.1): a single JSON array response, flattened
			// item by item.
			List<Order> orders = api.listOrders().collectList().block();
			requireEquals(2, orders.size(), "Flux<Order> listOrders() item count");
			requireEquals("1", orders.get(0).id, "Flux<Order> listOrders() item 0");
			requireEquals("2", orders.get(1).id, "Flux<Order> listOrders() item 1");
			System.out.println("listOrders() emitted " + orders.size() + " items");

			// 3. Flux<T> flavor 2 (§7.2): a @Paginated method auto-flattened across
			// pages, fetching the next page only once demand exceeds what's already
			// buffered. Subscribing with bounded, one-at-a-time demand (instead of
			// collectList()'s unbounded request) actually exercises that: page 2
			// must not be fetched until the first item is consumed and more demand
			// is signaled.
			List<Order> allOrders = new ArrayList<>();
			CompletableFuture<Order> firstItemReceived = new CompletableFuture<>();
			CompletableFuture<Void> streamCompleted = new CompletableFuture<>();
			Subscription[] subscriptionHolder = new Subscription[1];
			api.streamAllOrders(null).subscribe(new BaseSubscriber<Order>() {
				@Override
				protected void hookOnSubscribe(Subscription subscription) {
					subscriptionHolder[0] = subscription;
					subscription.request(1);
				}

				@Override
				protected void hookOnNext(Order order) {
					allOrders.add(order);
					if (allOrders.size() == 1) {
						firstItemReceived.complete(order);
					}
				}

				@Override
				protected void hookOnComplete() {
					streamCompleted.complete(null);
				}
			});
			firstItemReceived.get(10, TimeUnit.SECONDS);
			requireEquals(1, pagedFetchCount.get(),
					"paged fetch count after the first streamAllOrders(...) item (page 2 must not be fetched yet)");
			subscriptionHolder[0].request(1);
			streamCompleted.get(10, TimeUnit.SECONDS);
			requireEquals(2, allOrders.size(), "Flux<Order> streamAllOrders(...) item count across both pages");
			requireEquals("p1", allOrders.get(0).id, "Flux<Order> streamAllOrders(...) item 0 (page 1)");
			requireEquals("p2", allOrders.get(1).id, "Flux<Order> streamAllOrders(...) item 1 (page 2)");
			requireEquals(2, pagedFetchCount.get(),
					"paged fetch count after the second streamAllOrders(...) item (page 2 fetched on demand)");
			System.out.println("streamAllOrders(...) emitted " + allOrders.size() + " items across 2 pages: "
					+ allOrders.get(0).id + ", " + allOrders.get(1).id);

			System.out.println(
					"VERIFICATION PASSED: rest-in-peace-reactor works for a real downstream consumer.");
		} finally {
			server.stop(0);
		}
	}

	private static void requireEquals(Object expected, Object actual, String what) {
		if (!expected.equals(actual)) {
			throw new IllegalStateException("Unexpected " + what + ". Expected '" + expected + "' but got '" + actual + "'");
		}
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

}
