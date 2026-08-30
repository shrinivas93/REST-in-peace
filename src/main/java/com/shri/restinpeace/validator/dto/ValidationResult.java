package com.shri.restinpeace.validator.dto;

import static java.util.stream.Collectors.joining;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates validation errors found while checking a {@code @RestClient}
 * interface, so {@link com.shri.restinpeace.validator.RestClientValidator}
 * can report every problem at once instead of failing on the first one.
 */
public class ValidationResult implements Serializable {

	private static final long serialVersionUID = 4405237972655265537L;

	/** The errors recorded so far. */
	private List<String> errors = new ArrayList<>();

	/** Creates an empty result with no errors recorded yet. */
	public ValidationResult() {
	}

	/**
	 * Returns the errors collected so far.
	 *
	 * @return the errors
	 */
	public List<String> getErrors() {
		return errors;
	}

	/**
	 * Records a validation error.
	 *
	 * @param error the error message
	 * @return always {@code true}, per {@link List#add}
	 */
	public boolean addError(String error) {
		return errors.add(error);
	}

	/**
	 * Joins every recorded error into a single human-readable string.
	 *
	 * @return the joined errors
	 */
	public String getAllErrors() {
		return errors.stream().collect(joining(", ", "[ ", " ]"));
	}

	/**
	 * Returns whether any error has been recorded.
	 *
	 * @return true if at least one error was recorded
	 */
	public boolean hasError() {
		return !errors.isEmpty();
	}

}
