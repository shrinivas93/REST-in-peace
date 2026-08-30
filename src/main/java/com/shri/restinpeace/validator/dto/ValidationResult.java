package com.shri.restinpeace.validator.dto;

import static java.util.stream.Collectors.joining;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ValidationResult implements Serializable {

	private static final long serialVersionUID = 4405237972655265537L;

	private List<String> errors = new ArrayList<>();

	public List<String> getErrors() {
		return errors;
	}

	public boolean addError(String error) {
		return errors.add(error);
	}

	public String getAllErrors() {
		return errors.stream().collect(joining(", ", "[ ", " ]"));
	}

	public boolean hasError() {
		return !errors.isEmpty();
	}

}
