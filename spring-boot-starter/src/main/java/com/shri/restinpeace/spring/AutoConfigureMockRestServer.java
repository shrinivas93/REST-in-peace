package com.shri.restinpeace.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

import com.shri.restinpeace.mock.MockRestServer;

/**
 * Starts one {@link MockRestServer} for the test's application context,
 * exposes it as an injectable bean, and overrides every
 * {@code @RestClient} bean registered via {@link EnableRestInPeaceClients}
 * to resolve its base URL against {@link MockRestServer#baseUrl()} instead
 * of whatever {@code @BaseUrl}/{@code baseUrlProperty()}/{@code
 * rest-in-peace.clients.*} would otherwise resolve - the one piece that
 * doesn't already fall out of "call {@code RIP.getClient(...)} for you",
 * since a test has no real base URL to point at.
 *
 * <pre>
 * &#64;SpringBootTest
 * &#64;AutoConfigureMockRestServer
 * class UserServiceTest {
 *     &#64;Autowired MockRestServer server;
 *     &#64;Autowired UserApi userApi;   // already pointed at server.baseUrl()
 *
 *     &#64;Test
 *     void getUser_returnsDecodedBody() {
 *         server.on(HTTPMethod.GET, "/users/{id}", MockResponse.json(new User("42", "Shrinivas")));
 *         assertEquals("Shrinivas", userApi.getUser("42").name);
 *     }
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(MockRestServerTestConfiguration.class)
public @interface AutoConfigureMockRestServer {
}
