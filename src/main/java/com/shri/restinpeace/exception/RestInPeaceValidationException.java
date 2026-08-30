package com.shri.restinpeace.exception;

import com.shri.restinpeace.validator.dto.ValidationResult;

public class RestInPeaceValidationException extends Exception {

	private static final long serialVersionUID = 8111958117033765908L;

	private final ValidationResult validationResult;

	public RestInPeaceValidationException(ValidationResult validationResult) {
		super(validationResult.getAllErrors());
		this.validationResult = validationResult;
	}

	public ValidationResult getValidationResult() {
		return validationResult;
	}

}
