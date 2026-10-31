package com.github.cleytonorocha.hub.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class PortableCopyArchiveCodecDuplicateTests {
	@TempDir Path temp;
	private final ObjectMapper mapper = new ObjectMapper();
	private final PortableCopyArchiveCodec codec = new PortableCopyArchiveCodec(mapper);

	@Test
	void rejectsDuplicatePropertyInV12Manifest() throws Exception {
		Path zip = writeV12Package(false, true);
		Path stage = Files.createDirectory(temp.resolve("stage-manifest"));
		assertThatThrownBy(() -> codec.readVersion12(zip, stage)).isInstanceOf(IOException.class);
	}

	@Test
	void rejectsDuplicatePropertyInsideV12Section() throws Exception {
		Path zip = writeV12Package(true, false);
		Path stage = Files.createDirectory(temp.resolve("stage-section"));
		assertThatThrownBy(() -> codec.readVersion12(zip, stage)).isInstanceOf(IOException.class);
	}

	private Path writeV12Package(boolean duplicateTaskField, boolean duplicateManifestField) throws Exception {
		Map<String, byte[]> sections = new LinkedHashMap<>();
		sections.put("tasks", bytes(duplicateTaskField ? "[{\"id\":\"x\",\"id\":\"x\"}]" : "[]"));
		for (String name : List.of("series", "readingItems", "authors", "habits", "focusSessions", "notes")) sections.put(name, bytes("[]"));
		Map<String, Object> preferences = new LinkedHashMap<>();
		preferences.put("weeklySummaryVisible", true);
		preferences.put("videoDownloaderCardVisible", true);
		preferences.put("sidebarPinned", false);
		preferences.put("taskDefaults", Map.of("category", "Geral", "color", "#A986FF", "priority", "NORMAL"));
		preferences.put("calendarPresentations", Map.of("day", "", "week", "", "month", "", "year", ""));
		sections.put("preferences", mapper.writeValueAsBytes(preferences));

		List<Map<String, Object>> metadata = new ArrayList<>();
		for (var entry : sections.entrySet()) {
			int count = entry.getKey().equals("preferences") || (duplicateTaskField && entry.getKey().equals("tasks")) ? 1 : 0;
			metadata.add(Map.of("name", entry.getKey(), "path", "data/" + entry.getKey() + ".json", "count", count,
				"size", entry.getValue().length, "sha256", sha256(entry.getValue())));
		}
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("formatVersion", 12); manifest.put("generatedAt", "2026-10-31T00:00:00Z");
		manifest.put("sections", metadata); manifest.put("assets", List.of());
		String manifestText = mapper.writeValueAsString(manifest);
		if (duplicateManifestField) manifestText = manifestText.replace("\"formatVersion\":12", "\"formatVersion\":12,\"formatVersion\":12");
		Path zipPath = temp.resolve("duplicate-" + (duplicateTaskField ? "section" : "manifest") + ".zip");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			put(zip, "manifest.json", bytes(manifestText));
			for (var entry : sections.entrySet()) put(zip, "data/" + entry.getKey() + ".json", entry.getValue());
		}
		Files.write(zipPath, output.toByteArray());
		return zipPath;
	}

	private void put(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
		zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
	}
	private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
	private String sha256(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
}
