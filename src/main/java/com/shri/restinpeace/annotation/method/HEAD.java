package com.shri.restinpeace.annotation.method;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPMethodMarker;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.constant.RIPConstant;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@HTTPMethodMarker(HTTPMethod.HEAD)

public @interface HEAD {
	String value() default RIPConstant.DEFAULT;
}
