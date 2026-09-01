package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as sending a {@code multipart/form-data} body built from
 * its {@link Part @Part}/{@link PartMap @PartMap}-annotated parameters,
 * instead of the JSON/raw-string body a {@link Body @Body} parameter would
 * send. Valid only on
 * {@code @POST}, {@code @PUT}, {@code @PATCH}, and {@code @DELETE} methods -
 * using it on {@code @GET}, {@code @HEAD}, or {@code @OPTIONS} fails
 * validation, same as {@code @Body}. A method can't combine {@code @Multipart}
 * with a {@code @Body} parameter, and needs at least one {@code @Part}/
 * {@code @PartMap} parameter to be worth declaring multipart at all.
 *
 * <pre>{@literal @}POST("/users/{id}/avatar")
 * {@literal @}Multipart
 * String uploadAvatar({@literal @}PathParam("id") String id, {@literal @}Part("file") File avatar);</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Multipart {

}
