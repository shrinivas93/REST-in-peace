package com.shri.restinpeace.annotation.service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.constant.HTTPMethod;

public class RestRequestProcessor {

	public RestRequestProcessor() {
		// TODO Auto-generated constructor stub
	}

	public void processRestRequest(Method method, HTTPMethod httpMethod) {
		switch (httpMethod) {
		case DELETE:
			processDeleteRequest(method);
			break;
		case GET:
			processGetRequest(method);
			break;
		case HEAD:
			processHeadRequest(method);
			break;
		case OPTIONS:
			processOptionsRequest(method);
			break;
		case PATCH:
			processPatchRequest(method);
			break;
		case POST:
			processPostRequest(method);
			break;
		case PUT:
			processPutRequest(method);
			break;
		}
	}

	private void processPutRequest(Method method) {
		// TODO Auto-generated method stub

	}

	private void processPostRequest(Method method) {
		// TODO Auto-generated method stub

	}

	private void processPatchRequest(Method method) {
		// TODO Auto-generated method stub

	}

	private void processOptionsRequest(Method method) {
		// TODO Auto-generated method stub

	}

	private void processHeadRequest(Method method) {
		// TODO Auto-generated method stub

	}

	private void processGetRequest(Method method) {
		GET get = method.getAnnotation(GET.class);
		String endpoint = get.value();
		Parameter[] parameters = method.getParameters();
	}

	private void processDeleteRequest(Method method) {
		// TODO Auto-generated method stub

	}

}
