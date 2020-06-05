package com.shri.restinpeace.exception;

public class RestInPeaceException extends RuntimeException {

	private static final long serialVersionUID = 8111958117033765908L;

	public RestInPeaceException() {
		super();
	}

	public RestInPeaceException(String message, Throwable cause) {
		super(message, cause);
	}

	public RestInPeaceException(String message) {
		super(message);
	}

	public RestInPeaceException(Throwable cause) {
		super(cause);
	}

}
