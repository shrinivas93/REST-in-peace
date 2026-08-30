package com.shri.restinpeace.annotation.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.shri.restinpeace.annotation.method.meta.HTTPRequestParamMarker;
import com.shri.restinpeace.constant.HTTPRequestParam;
import com.shri.restinpeace.constant.RIPConstant;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@HTTPRequestParamMarker(HTTPRequestParam.HEADER)

public @interface HeaderParam {
	String value();

	boolean required() default false;

	String defaultValue() default RIPConstant.DEFAULT;
}
