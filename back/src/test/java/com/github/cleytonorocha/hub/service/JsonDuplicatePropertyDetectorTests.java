package com.github.cleytonorocha.hub.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JsonDuplicatePropertyDetectorTests {
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void rejectsDuplicateEnvelopeProperty() {
		byte[] json = "{\"formatVersion\":3,\"formatVersion\":3,\"tasks\":[],\"series\":[]}".getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> JsonDuplicatePropertyDetector.reject(json, mapper)).isInstanceOf(IOException.class);
	}

	@Test
	void rejectsDuplicateNestedObjectProperty() {
		byte[] json = "{\"formatVersion\":3,\"tasks\":[{\"id\":\"a\",\"id\":\"b\"}],\"series\":[]}".getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> JsonDuplicatePropertyDetector.reject(json, mapper)).isInstanceOf(IOException.class);
	}
}
