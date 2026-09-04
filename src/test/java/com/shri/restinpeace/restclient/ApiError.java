package com.shri.restinpeace.restclient;

/** A simple error-body POJO for {@link GeneratedApi#getError}'s {@code @ErrorType}. */
public class ApiError {

	private String code;
	private String message;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

}
