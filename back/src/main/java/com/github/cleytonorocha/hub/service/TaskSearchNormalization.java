package com.github.cleytonorocha.hub.service;

import java.text.Normalizer;
import java.util.Locale;

/** Shared H2 search normalization, kept byte-for-byte equivalent to the frontend rule. */
public final class TaskSearchNormalization {
	private static final Locale SEARCH_LOCALE = Locale.forLanguageTag("pt-BR");

	private TaskSearchNormalization() {}

	public static String normalize(String value) {
		if (value == null) return "";
		return Normalizer.normalize(value, Normalizer.Form.NFD)
			.replaceAll("[\\u0300-\\u036f]", "")
			.toLowerCase(SEARCH_LOCALE);
	}
}
