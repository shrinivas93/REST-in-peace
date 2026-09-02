package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;

/**
 * Uses the annotated {@code String} parameter as the method's entire URL,
 * instead of resolving one from the HTTP method annotation's template
 * (with {@code @BaseUrl}/a runtime base URL and {@code @PathParam}s). For a
 * call whose URL isn't a fixed shape known in advance - a pagination
 * {@code next} link, a HATEOAS action link from a previous response - where
 * the caller already holds the full URL to call verbatim:
 *
 * <pre>
 * {@literal @}GET
 * Page{@literal <}Item{@literal >} nextPage({@literal @}Url String url);
 * </pre>
 *
 * Only valid alongside a method whose HTTP method annotation has no
 * {@code value()} of its own - combining a static URL template with
 * {@code @Url} fails validation, since there would be two conflicting
 * sources of truth for the URL. At most one parameter per method may be
 * annotated {@code @Url}. {@code @PathParam}/{@code @BaseUrl}/a runtime base
 * URL are all ignored when {@code @Url} is used - there's no template left
 * for them to apply to - but {@code @QueryParam}/{@code @HeaderParam}/etc.
 * still work normally, appended to the given URL.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.URL)

public @interface Url {
}
