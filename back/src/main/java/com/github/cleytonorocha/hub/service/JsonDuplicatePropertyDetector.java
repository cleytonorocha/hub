package com.github.cleytonorocha.hub.service;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.databind.ObjectMapper;

/** Enforces duplicate object member rejection independently of databind tree handling. */
final class JsonDuplicatePropertyDetector {
	private JsonDuplicatePropertyDetector() {}

	static void reject(byte[] input, ObjectMapper mapper) throws IOException {
		TokenStreamFactory factory = mapper.tokenStreamFactory().rebuild()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
		try (JsonParser parser = factory.createParser(input)) {
			ArrayDeque<Set<String>> objectMembers = new ArrayDeque<>();
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if (token == JsonToken.START_OBJECT) objectMembers.push(new HashSet<>());
				else if (token == JsonToken.END_OBJECT) {
					if (objectMembers.isEmpty()) throw new IOException("Unexpected JSON object end.");
					objectMembers.pop();
				} else if (token == JsonToken.PROPERTY_NAME) {
					if (objectMembers.isEmpty() || !objectMembers.peek().add(parser.currentName()))
						throw new IOException("Duplicate JSON object property.");
				}
			}
			if (!objectMembers.isEmpty()) throw new IOException("Unclosed JSON object.");
		} catch (tools.jackson.core.JacksonException error) {
			throw new IOException("Invalid JSON object structure.", error);
		}
	}
}
