package com.github.cleytonorocha.hub.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict ZIP v12 codec. Domain decoding and persistence stay in TaskBackupService. */
@Component
public final class PortableCopyArchiveCodec {
	private static final Set<String> SECTION_NAMES = Set.of("tasks", "series", "readingItems", "authors", "habits", "focusSessions", "notes", "preferences");
	private static final long MAX_EXPANDED = 100L * 1024 * 1024;
	private final ObjectMapper mapper;

	public PortableCopyArchiveCodec(ObjectMapper mapper) { this.mapper = mapper; }

	public byte[] manifestBytes(String generatedAt, Map<String, byte[]> sections, Map<String, Path> assets) throws IOException {
		List<Section> sectionList = new ArrayList<>();
		for (String name : List.of("tasks", "series", "readingItems", "authors", "habits", "focusSessions", "notes", "preferences")) {
			byte[] bytes = sections.get(name);
			if (bytes == null) throw new IllegalArgumentException("ZIP v12 is missing section " + name);
			JsonNode data = mapper.readTree(bytes);
			int count = "preferences".equals(name) ? 1 : data == null || !data.isArray() ? -1 : data.size();
			if (count < 0) throw new IllegalArgumentException("ZIP v12 section has invalid JSON shape: " + name);
			sectionList.add(new Section(name, "data/" + name + ".json", count, bytes.length, sha256(bytes)));
		}
		List<Asset> assetList = new ArrayList<>();
		for (Map.Entry<String, Path> entry : assets.entrySet()) {
			byte[] bytes = Files.readAllBytes(entry.getValue());
			assetList.add(new Asset(entry.getKey(), bytes.length, sha256(bytes)));
		}
		return mapper.writeValueAsBytes(new Manifest(12, generatedAt, sectionList, assetList));
	}

	public boolean isVersion12(Path archive) {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			ZipEntry manifest = zip.getEntry("manifest.json");
			if (manifest == null) return false;
			byte[] bytes;
			try (var input = zip.getInputStream(manifest)) { bytes = input.readNBytes(1024 * 1024); }
			JsonNode root = mapper.readTree(bytes);
			return root != null && root.path("formatVersion").asInt(-1) == 12;
		} catch (Exception ignored) { return false; }
	}

	public Contents readVersion12(Path archive, Path staging) throws IOException {
		Map<String, byte[]> raw = new LinkedHashMap<>();
		long expanded = 0;
		int entryCount = 0;
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				if (++entryCount > 5_009) throw new IllegalArgumentException("ZIP has too many entries");
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory() || raw.containsKey(entry.getName())) throw new IllegalArgumentException("ZIP has duplicate or directory entries");
				String name = entry.getName();
				long limit = name.equals("manifest.json") ? 90L * 1024 * 1024 : name.startsWith("assets/") ? ReadingCoverStorageService.MAX_FILE_BYTES : MAX_EXPANDED;
				try (var input = zip.getInputStream(entry); var output = new ByteArrayOutputStream()) {
					byte[] buffer = new byte[8192]; int n;
					while ((n = input.read(buffer)) >= 0) {
						if ((long) output.size() + n > limit) throw new IllegalArgumentException("ZIP entry exceeds its limit");
						output.write(buffer, 0, n);
					}
					byte[] data = output.toByteArray(); expanded += data.length;
					if (expanded > MAX_EXPANDED) throw new IllegalArgumentException("ZIP expanded content exceeds its limit");
					raw.put(name, data);
				}
			}
		}
		byte[] manifestBytes = raw.remove("manifest.json");
		if (manifestBytes == null) throw new IllegalArgumentException("ZIP is missing manifest");
		JsonNode root = parseStrict(manifestBytes);
		if (root == null || !root.isObject() || !fields(root).equals(Set.of("formatVersion", "generatedAt", "sections", "assets"))
				|| !root.path("formatVersion").isIntegralNumber() || root.path("formatVersion").asInt(-1) != 12 || !root.path("generatedAt").isTextual()
				|| !root.path("sections").isArray() || !root.path("assets").isArray()) throw new IllegalArgumentException("Invalid v12 manifest");
		Map<String, byte[]> sections = new LinkedHashMap<>();
		long expectedTotal = manifestBytes.length;
		for (JsonNode section : root.path("sections")) {
			if (!fields(section).equals(Set.of("name", "path", "count", "size", "sha256"))) throw new IllegalArgumentException("Invalid section metadata");
			if (!section.path("name").isTextual() || !section.path("path").isTextual() || !section.path("count").isIntegralNumber()
					|| section.path("count").asInt(-1) < 0 || !section.path("size").isIntegralNumber() || section.path("size").asLong(-1) < 0
					|| !section.path("sha256").isTextual() || !section.path("sha256").asText().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid section metadata types");
			String sectionName = section.path("name").asText();
			String path = section.path("path").asText();
			if (!SECTION_NAMES.contains(sectionName) || !path.equals("data/" + sectionName + ".json") || sections.containsKey(sectionName)) throw new IllegalArgumentException("Unexpected section path or name");
			byte[] bytes = raw.remove(path);
			if (bytes == null || bytes.length != section.path("size").asLong(-1) || !sha256(bytes).equals(section.path("sha256").asText())) throw new IllegalArgumentException("Section size or digest mismatch");
			JsonNode value = parseStrict(bytes);
			int count = "preferences".equals(sectionName) ? (value != null && value.isObject() ? 1 : -1) : (value != null && value.isArray() ? value.size() : -1);
			if (count != section.path("count").asInt(-2)) throw new IllegalArgumentException("Section count mismatch");
			sections.put(sectionName, bytes); expectedTotal += bytes.length;
		}
		if (!sections.keySet().equals(SECTION_NAMES)) throw new IllegalArgumentException("Missing v12 section");
		Map<String, Path> assets = new LinkedHashMap<>();
		Set<String> assetPaths = new java.util.LinkedHashSet<>();
		for (JsonNode asset : root.path("assets")) {
			if (!fields(asset).equals(Set.of("path", "size", "sha256"))) throw new IllegalArgumentException("Invalid asset metadata");
			if (!asset.path("path").isTextual() || !asset.path("size").isIntegralNumber() || asset.path("size").asLong(-1) < 0
					|| !asset.path("sha256").isTextual() || !asset.path("sha256").asText().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid asset metadata types");
			String path = asset.path("path").asText();
			if (!path.matches("assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)") || !assetPaths.add(path)) throw new IllegalArgumentException("Unexpected asset path");
			byte[] bytes = raw.remove(path);
			if (bytes == null || bytes.length != asset.path("size").asLong(-1) || !sha256(bytes).equals(asset.path("sha256").asText())) throw new IllegalArgumentException("Asset size or digest mismatch");
			Path target = staging.resolve(java.util.UUID.randomUUID().toString()); Files.write(target, bytes); assets.put(path, target); expectedTotal += bytes.length;
		}
		if (!raw.isEmpty() || expectedTotal > MAX_EXPANDED) throw new IllegalArgumentException("Unexpected ZIP entries or expanded size");
		return new Contents(root.path("generatedAt").asText(), sections, assets);
	}

	public void write(Path archive, byte[] manifest, Map<String, byte[]> sections, Map<String, Path> assets) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			put(zip, "manifest.json", manifest);
			for (String name : List.of("tasks", "series", "readingItems", "authors", "habits", "focusSessions", "notes", "preferences")) put(zip, "data/" + name + ".json", sections.get(name));
			for (Map.Entry<String, Path> asset : assets.entrySet()) put(zip, asset.getKey(), Files.readAllBytes(asset.getValue()));
		}
	}

	private Set<String> fields(JsonNode node) {
		if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected a JSON object.");
		return new java.util.LinkedHashSet<>(node.propertyNames());
	}
	private JsonNode parseStrict(byte[] bytes) throws IOException {
		JsonDuplicatePropertyDetector.reject(bytes, mapper);
		TokenStreamFactory strict = mapper.tokenStreamFactory().rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
		try (JsonParser parser = strict.createParser(bytes)) { return mapper.readTree(parser); }
	}
	private String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
	private void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); }
	private record Manifest(int formatVersion, String generatedAt, List<Section> sections, List<Asset> assets) {}
	private record Section(String name, String path, int count, long size, String sha256) {}
	private record Asset(String path, long size, String sha256) {}
	public record Contents(String generatedAt, Map<String, byte[]> sections, Map<String, Path> assets) {}
}
