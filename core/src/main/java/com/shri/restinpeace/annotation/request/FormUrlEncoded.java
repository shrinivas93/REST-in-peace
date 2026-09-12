package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as sending an {@code application/x-www-form-urlencoded}
 * body built from its {@link Field @Field}/{@link FieldMap @FieldMap}-annotated
 * parameters, instead of the JSON/raw-string body a {@link Body @Body}
 * parameter would send, or the {@code multipart/form-data} body
 * {@link Multipart @Multipart} would send. Valid only on
 * {@code @POST}, {@code @PUT}, {@code @PATCH}, and {@code @DELETE} methods -
 * using it on {@code @GET}, {@code @HEAD}, or {@code @OPTIONS} fails
 * validation, same as {@code @Body}/{@code @Multipart}. A method can't
 * combine {@code @FormUrlEncoded} with a {@code @Body} parameter or
 * {@code @Multipart}, and needs at least one {@code @Field}/{@code @FieldMap}
 * parameter to be worth declaring form-urlencoded at all.
 *
 * <pre>{@literal @}POST("/oauth/token")
 * {@literal @}FormUrlEncoded
 * String getToken({@literal @}Field("grant_type") String grantType, {@literal @}Field("client_id") String clientId);</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FormUrlEncoded {

}
