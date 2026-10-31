package com.github.cleytonorocha.hub.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns the external processes. All arguments and output locations are server controlled. */
@Component
public class VideoDownloadRunner {
	private static final int MAX_METADATA = 4 * 1024 * 1024;
	private static final int MAX_OUTPUT = 64 * 1024;
	private final ObjectMapper mapper;
	private final String ytDlp, node, ffmpeg, ffprobe;

	public VideoDownloadRunner(ObjectMapper mapper,
			@Value("${hub.video-download.yt-dlp-command:yt-dlp}") String ytDlp,
			@Value("${hub.video-download.node-command:node}") String node,
			@Value("${hub.video-download.ffmpeg-command:ffmpeg}") String ffmpeg,
			@Value("${hub.video-download.ffprobe-command:ffprobe}") String ffprobe) {
		this.mapper = mapper; this.ytDlp = ytDlp; this.node = node; this.ffmpeg = ffmpeg; this.ffprobe = ffprobe;
	}

	boolean available() { return executable(ytDlp) != null && executable(node) != null && executable(ffmpeg) != null && executable(ffprobe) != null; }

	Media prepare(Control control) throws IOException, InterruptedException {
		control.check();
		VideoDownloadUrl.requirePublicDns(control.url);
		control.phase.accept("ANALYZING");
		List<String> probe = base();
		probe.addAll(List.of("--dump-single-json", "--skip-download", "--", control.url.toASCIIString()));
		JsonNode info = mapper.readTree(run(probe, control, MAX_METADATA));
		validateMetadata(info);
		control.check();
		control.phase.accept("DOWNLOADING");
		List<String> download = base();
		download.addAll(List.of("--format", "bestvideo*+bestaudio/best", "--format-sort", "res:" + control.quality,
			"--max-filesize", "1G", "--merge-output-format", "mp4/mkv", "--output", control.directory.resolve("video.%(ext)s").toString(),
			"--print", "after_move:%(filepath)j", "--no-simulate", "--", control.url.toASCIIString()));
		String result = run(download, control, MAX_OUTPUT).strip();
		String[] lines = result.split("\\R");
		if (lines.length != 1) throw VideoDownloadFailure.failed("INVALID_OUTPUT", "O extrator não produziu um único vídeo.");
		JsonNode output = mapper.readTree(lines[0]);
		if (output == null || !output.isTextual()) throw VideoDownloadFailure.failed("INVALID_OUTPUT", "O extrator não informou o arquivo final.");
		Path file = validateFinalPath(control.directory, output.asText());
		long size = Files.size(file);
		if (size <= 0) throw VideoDownloadFailure.failed("INVALID_OUTPUT", "O extrator produziu um arquivo vazio.");
		boolean sizeReduced = false;
		if (control.reduceFileSize) {
			control.check();
			control.phase.accept("COMPRESSING");
			Path compressed = control.directory.resolve("video-compressed.mp4");
			run(List.of(required(ffmpeg), "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", file.toString(),
				"-map", "0:v:0", "-map", "0:a?", "-c:v", "libx264", "-preset", "fast", "-crf", "28",
				"-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", compressed.toString()), control, MAX_OUTPUT);
			Path candidate = validateFinalPath(control.directory, compressed.toString());
			long compressedSize = Files.size(candidate);
			if (compressedSize > 0 && compressedSize < size) {
				Files.delete(file);
				file = candidate;
				size = compressedSize;
				sizeReduced = true;
			} else {
				Files.deleteIfExists(candidate);
			}
		}
		if (size > VideoDownloadService.MAX_FILE_SIZE) throw VideoDownloadFailure.failed("SIZE_LIMIT", "O arquivo final deve ter até 1 GiB.");
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
		String type = switch (extension) {
			case "mp4" -> "video/mp4";
			case "webm" -> "video/webm";
			case "mkv" -> "video/x-matroska";
			case "mov" -> "video/quicktime";
			default -> throw VideoDownloadFailure.failed("INVALID_OUTPUT", "O formato produzido não é suportado.");
		};
		control.phase.accept("FINALIZING");
		JsonNode details = mapper.readTree(run(List.of(required(ffprobe), "-v", "error", "-show_entries", "stream=codec_type,width,height", "-of", "json", file.toString()), control, MAX_OUTPUT));
		Integer width = null, height = null;
		JsonNode streams = details == null ? null : details.get("streams");
		if (streams != null && streams.isArray()) for (JsonNode stream : streams) {
			if ("video".equals(stream.path("codec_type").asText())) {
				int w = stream.path("width").asInt(0), h = stream.path("height").asInt(0);
				if (w > 0 && h > 0) { width = w; height = h; break; }
			}
		}
		if (width == null) throw VideoDownloadFailure.failed("INVALID_OUTPUT", "O arquivo produzido não contém um vídeo válido.");
		control.check();
		return new Media(file, "video." + extension, type, size, width, height, sizeReduced);
	}

	static void validateMetadata(JsonNode info) {
		if (info == null || !info.isObject() || info.has("entries")
				|| (!info.path("_type").asText("video").equals("video")))
			throw VideoDownloadFailure.failed("UNSUPPORTED_CONTENT", "Use o link de um único vídeo, sem playlist ou coleção.");
		String live = info.path("live_status").asText("");
		if (info.path("is_live").asBoolean(false) || live.equals("is_live") || live.equals("is_upcoming") || info.path("has_drm").asBoolean(false))
			throw VideoDownloadFailure.failed("UNSUPPORTED_CONTENT", "Transmissões ao vivo, agendadas ou conteúdo protegido não são suportados.");
		String availability = info.path("availability").asText("");
		if (!availability.isEmpty() && !availability.equals("public") && !availability.equals("unlisted"))
			throw VideoDownloadFailure.failed("SOURCE_RESTRICTED", "Este conteúdo exige acesso ou autenticação na plataforma.");
	}

	static Path validateFinalPath(Path directory, String value) throws IOException {
		Path normalized = Path.of(value).toAbsolutePath().normalize();
		Path realDirectory = directory.toRealPath();
		if (!normalized.startsWith(directory.toAbsolutePath().normalize()) || Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))
			throw VideoDownloadFailure.failed("INVALID_OUTPUT", "O extrator produziu um caminho de arquivo inválido.");
		Path realFile = normalized.toRealPath();
		if (!realFile.startsWith(realDirectory)) throw VideoDownloadFailure.failed("INVALID_OUTPUT", "O extrator produziu um caminho de arquivo inválido.");
		return realFile;
	}

	private List<String> base() {
		return new ArrayList<>(List.of(required(ytDlp), "--ignore-config", "--no-cache-dir", "--no-playlist", "--no-progress", "--no-warnings", "--no-remote-components",
			"--use-extractors", "Youtube,Instagram", "--no-js-runtimes", "--js-runtimes", "node:" + required(node),
			"--ffmpeg-location", required(ffmpeg), "--socket-timeout", "30", "--retries", "2", "--extractor-retries", "2"));
	}

	String run(List<String> command, Control control, int maxOutput) throws IOException, InterruptedException {
		control.check();
		Process process;
		try { process = new ProcessBuilder(command).directory(control.directory.toFile()).start(); }
		catch (IOException error) { throw VideoDownloadFailure.unavailable(); }
		try { control.register(process); }
		catch (InterruptedException error) {
			try { control.unregister(process); } finally { closePipes(process); }
			throw error;
		}
		AtomicReference<IOException> readError = new AtomicReference<>();
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		Thread out = Thread.ofVirtual().name("video-process-output").start(() -> copyBounded(process.getInputStream(), stdout, maxOutput, readError));
		Thread err = Thread.ofVirtual().name("video-process-errors").start(() -> copyBounded(process.getErrorStream(), new ByteArrayOutputStream(), MAX_OUTPUT, readError));
		try {
			while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
				control.check();
				if (readError.get() != null) throw VideoDownloadFailure.failed("OUTPUT_LIMIT", "O extrator excedeu o limite de metadados ou diagnóstico.");
			}
			out.join(2000); err.join(2000);
			control.check();
			if (out.isAlive() || err.isAlive() || readError.get() != null) throw VideoDownloadFailure.failed("OUTPUT_LIMIT", "O extrator excedeu o limite de metadados ou diagnóstico.");
			if (process.exitValue() != 0) throw VideoDownloadFailure.failed("EXTRACTION_FAILED", "Não foi possível preparar este vídeo. Confira se o link é público e tente novamente.");
			return stdout.toString(StandardCharsets.UTF_8);
		} finally {
			try { control.unregister(process); } finally { closePipes(process); }
		}
	}

	private static void closePipes(Process process) {
		try { process.getInputStream().close(); } catch (IOException ignored) { }
		try { process.getErrorStream().close(); } catch (IOException ignored) { }
		try { process.getOutputStream().close(); } catch (IOException ignored) { }
	}

	private static void copyBounded(InputStream input, ByteArrayOutputStream output, int maximum, AtomicReference<IOException> error) {
		try (input) {
			byte[] buffer = new byte[8192]; int count;
			while ((count = input.read(buffer)) != -1) {
				if (output.size() + count > maximum) { error.compareAndSet(null, new IOException("Process output limit")); return; }
				output.write(buffer, 0, count);
			}
		} catch (IOException failure) { error.compareAndSet(null, failure); }
	}

	private static String required(String command) {
		Path path = executable(command); if (path == null) throw VideoDownloadFailure.unavailable(); return path.toString();
	}

	private static Path executable(String command) {
		if (command == null || command.isBlank()) return null;
		try {
			Path candidate = Path.of(command);
			if (candidate.isAbsolute() || command.contains("/") || command.contains("\\")) return Files.isRegularFile(candidate) && Files.isExecutable(candidate) ? candidate.toAbsolutePath().normalize() : null;
			String path = System.getenv("PATH");
			if (path == null) return null;
			boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
			for (String folder : path.split(java.io.File.pathSeparator)) for (String suffix : windows ? List.of("", ".exe", ".cmd", ".bat") : List.of("")) {
				candidate = Path.of(folder).resolve(command + suffix);
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize();
			}
		} catch (RuntimeException ignored) { }
		return null;
	}

	static class Control {
		final Path directory; final URI url; final int quality; final boolean reduceFileSize;
		final Instant deadline; final java.time.Clock clock; final Runnable resources;
		final Consumer<String> phase;
		volatile boolean cancelled;
		private volatile Process process;
		private final Set<ProcessHandle> processes = ConcurrentHashMap.newKeySet();
		Control(Path directory, URI url, int quality, boolean reduceFileSize, Instant deadline, java.time.Clock clock, Runnable resources, Consumer<String> phase) {
			this.directory = directory; this.url = url; this.quality = quality; this.reduceFileSize = reduceFileSize; this.deadline = deadline; this.clock = clock; this.resources = resources; this.phase = phase;
		}
		Control(Path directory, URI url, int quality, Instant deadline, java.time.Clock clock, Runnable resources, Consumer<String> phase) {
			this(directory, url, quality, false, deadline, clock, resources, phase);
		}
		void check() throws InterruptedException {
			snapshotProcesses();
			if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("Video preparation cancelled");
			if (!clock.instant().isBefore(deadline)) throw VideoDownloadFailure.failed("TIME_LIMIT", "A preparação excedeu o limite de dez minutos.");
			resources.run();
		}
		void register(Process value) throws InterruptedException {
			synchronized (this) { process = value; track(value.toHandle()); snapshotProcesses(); }
			if (cancelled) { terminateProcesses(); throw new InterruptedException(); }
		}
		void unregister(Process value) {
			terminateProcesses();
			synchronized (this) { if (process == value && !value.isAlive()) process = null; }
			if (hasLiveProcesses()) throw VideoDownloadFailure.failed("PROCESS_TERMINATION_FAILED", "O processamento não pôde ser encerrado. Aguarde a liberação dos recursos.");
		}
		void cancel() { cancelled = true; terminateProcesses(); }
		void track(ProcessHandle handle) { processes.add(handle); }
		private void snapshotProcesses() {
			Process current = process;
			if (current != null) { track(current.toHandle()); current.descendants().forEach(this::track); }
			for (ProcessHandle handle : List.copyOf(processes)) if (handle.isAlive()) handle.descendants().forEach(this::track);
		}
		boolean hasLiveProcesses() {
			snapshotProcesses();
			processes.removeIf(handle -> !handle.isAlive());
			return !processes.isEmpty();
		}
		/** Retain every live handle after the bounded wait so maintenance can retry safely. */
		void terminateProcesses() {
			snapshotProcesses();
			List<ProcessHandle> snapshot = List.copyOf(processes);
			boolean interrupted = Thread.interrupted();
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			try {
				Process primary = process;
				long primaryPid = primary == null ? -1 : primary.pid();
				List<ProcessHandle> descendants = snapshot.stream().filter(handle -> handle.pid() != primaryPid).toList();
				destroy(descendants);
				// Give a live parent a chance to reap children before killing it, especially
				// when Java is PID 1 in a container without a separate init process.
				long childrenUntil = Math.min(until, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
				while (descendants.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < childrenUntil) {
					try { Thread.sleep(20); } catch (InterruptedException error) { interrupted = true; }
				}
				snapshotProcesses(); snapshot = List.copyOf(processes);
				destroy(snapshot);
				while (snapshot.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < until) {
					try { Thread.sleep(20); } catch (InterruptedException error) { interrupted = true; }
				}
			} finally {
				processes.removeIf(handle -> !handle.isAlive());
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
		private static void destroy(List<ProcessHandle> handles) {
			for (ProcessHandle handle : handles) if (handle.isAlive()) {
				try { handle.destroyForcibly(); } catch (RuntimeException ignored) { /* Still live: reserve and retry. */ }
			}
		}
	}

	record Media(Path path, String fileName, String contentType, long sizeBytes, Integer width, Integer height, boolean sizeReduced) {
		Media(Path path, String fileName, String contentType, long sizeBytes, Integer width, Integer height) {
			this(path, fileName, contentType, sizeBytes, width, height, false);
		}
		Integer effectiveQuality() { return width == null || height == null ? null : Math.min(width, height); }
	}
}
