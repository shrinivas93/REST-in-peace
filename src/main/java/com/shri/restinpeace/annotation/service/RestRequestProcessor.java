package com.shri.restinpeace.annotation.service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.constant.RIPConstant;
import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

public class RestRequestProcessor {

	public Object processRestRequest(Method method, HTTPMethod httpMethod, Object[] args) {
		switch (httpMethod) {
		case GET:
			return processGetRequest(method, args);
		case DELETE:
		case HEAD:
		case OPTIONS:
		case PATCH:
		case POST:
		case PUT:
			throw new RestInPeaceException(
					String.format("HTTP method %s is not yet supported (method %s).", httpMethod, method));
		default:
			throw new RestInPeaceException(String.format("Unknown HTTP method %s.", httpMethod));
		}
	}

	private Object processGetRequest(Method method, Object[] args) {
		GET get = method.getAnnotation(GET.class);
		String url = resolvePathParams(get.value(), method, args);

		GetRequest request = Unirest.get(url);
		Parameter[] parameters = method.getParameters();

		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			Object argValue = args == null ? null : args[i];

			QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
			if (queryParam != null) {
				Object value = resolveValue(argValue, queryParam.required(), queryParam.defaultValue(),
						queryParam.value());
				if (value != null) {
					request = request.queryString(queryParam.value(), value);
				}
			}

			HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
			if (headerParam != null) {
				Object value = resolveValue(argValue, headerParam.required(), headerParam.defaultValue(),
						headerParam.value());
				if (value != null) {
					request = request.header(headerParam.value(), String.valueOf(value));
				}
			}
		}

		HttpResponse<String> response = request.asString();
		return response.getBody();
	}

	private String resolvePathParams(String urlTemplate, Method method, Object[] args) {
		String url = urlTemplate;
		Parameter[] parameters = method.getParameters();

		for (int i = 0; i < parameters.length; i++) {
			PathParam pathParam = parameters[i].getAnnotation(PathParam.class);
			if (pathParam != null) {
				Object value = args == null ? null : args[i];
				if (value == null) {
					throw new RestInPeaceException(
							String.format("Missing value for path param '%s' in method %s.", pathParam.value(), method));
				}
				url = url.replace("{" + pathParam.value() + "}", String.valueOf(value));
			}
		}
		return url;
	}

	private Object resolveValue(Object argValue, boolean required, String defaultValue, String paramName) {
		if (argValue != null) {
			return argValue;
		}
		if (!RIPConstant.DEFAULT.equals(defaultValue)) {
			return defaultValue;
		}
		if (required) {
			throw new RestInPeaceException(String.format("Missing required value for param '%s'.", paramName));
		}
		return null;
	}

}
