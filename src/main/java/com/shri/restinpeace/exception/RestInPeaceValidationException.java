package com.shri.restinpeace.exception;

import com.shri.restinpeace.validator.dto.ValidationResult;

/**
 * Checked exception thrown by
 * {@link com.shri.restinpeace.validator.RestClientValidator#validate(Class)}
 * when a {@code @RestClient} interface fails validation. Carries the full
 * {@link ValidationResult} so every problem found can be reported at once
 * instead of failing on the first one.
 */
public class RestInPeaceValidationException extends Exception {

	private static final long serialVersionUID = 8111958117033765908L;

	/** The full validation result this exception was raised for. */
	private final ValidationResult validationResult;

	/**
	 * Creates the exception for a failed validation.
	 *
	 * @param validationResult the full validation result, including every error found
	 */
	public RestInPeaceValidationException(ValidationResult validationResult) {
		super(validationResult.getAllErrors());
		this.validationResult = validationResult;
	}

	/**
	 * Returns the full validation result, including every error found.
	 *
	 * @return the validation result
	 */
	public ValidationResult getValidationResult() {
		return validationResult;
	}

}
