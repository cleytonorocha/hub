package com.github.cleytonorocha.hub.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Runs yt-dlp for one public video URL from a supported platform. */
@Service
public class VideoDownloadService {
	private static final long MAX_FILE_SIZE_BYTES = 1024L * 1024L * 1024L;
	private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(10);
	private static final Semaphore DOWNLOAD_SLOTS = new Semaphore(2);
	private static final Map<Platform, List<String>> ALLOWED_HOSTS = allowedHosts();

	private final String ytDlpCommand;

	public VideoDownloadService(@Value("${hub.video-download.yt-dlp-command:yt-dlp}") String ytDlpCommand) {
		this.ytDlpCommand = ytDlpCommand;
	}

	/** Downloads a single video. The caller must close the result to remove its temporary files. */
	public DownloadedVideo download(Platform platform, String url) throws IOException, InterruptedException {
		URI videoUri = validateUrl(platform, url);
		if (!DOWNLOAD_SLOTS.tryAcquire()) throw new IllegalStateException("Já existem dois downloads em andamento.");

		Path directory = null;
		try {
			directory = Files.createTempDirectory("hub-video-");
			Path log = directory.resolve("yt-dlp.log");
			Path outputTemplate = directory.resolve("video.%(ext)s");
			List<String> command = List.of(
				ytDlpCommand,
				"--ignore-config",
				"--no-playlist",
				"--no-progress",
				"--no-warnings",
				"--no-part",
				"--socket-timeout", "30",
				"--retries", "2",
				"--extractor-retries", "2",
				"--max-filesize", "1G",
				"--format", "bestvideo*+bestaudio/best",
				"--output", outputTemplate.toString(),
				"--",
				videoUri.toASCIIString()
			);
			Process process = new ProcessBuilder(command)
				.directory(directory.toFile())
				.redirectErrorStream(true)
				.redirectOutput(log.toFile())
				.start();
			boolean finished;
			try {
				finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException error) {
				stopProcess(process);
				throw error;
			}
			if (!finished) {
				stopProcess(process);
				throw new IOException("Download excedeu o limite de dez minutos.");
			}
			if (process.exitValue() != 0) throw new IOException("A plataforma não permitiu concluir o download.");

			Path file;
			try (Stream<Path> files = Files.list(directory)) {
				file = files.filter(Files::isRegularFile)
					.filter(candidate -> !candidate.equals(log))
					.findFirst()
					.orElseThrow(() -> new IOException("O extrator não gerou um arquivo de vídeo."));
			}
			long size = Files.size(file);
			if (size <= 0 || size > MAX_FILE_SIZE_BYTES) throw new IOException("Arquivo de vídeo acima do limite de 1 GB.");
			String extension = extension(file);
			if (!List.of("mp4", "webm", "mkv", "mov").contains(extension)) {
				throw new IOException("Formato de vídeo não suportado.");
			}

			DownloadedVideo result = new DownloadedVideo(file, "video." + extension, size);
			directory = null;
			return result;
		} finally {
			if (directory != null) deleteDirectory(directory);
			DOWNLOAD_SLOTS.release();
		}
	}

	private static URI validateUrl(Platform platform, String rawUrl) {
		if (platform == null || rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048) {
			throw new IllegalArgumentException("Informe uma plataforma e um link HTTPS válido.");
		}
		URI uri;
		try {
			uri = URI.create(rawUrl.trim());
		} catch (IllegalArgumentException error) {
			throw new IllegalArgumentException("Informe um link HTTPS válido.");
		}
		String host = uri.getHost();
		boolean supportedHost = host != null && ALLOWED_HOSTS.get(platform).stream()
			.anyMatch(allowed -> host.equalsIgnoreCase(allowed) || host.toLowerCase().endsWith("." + allowed));
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !supportedHost || uri.getUserInfo() != null
			|| (uri.getPort() != -1 && uri.getPort() != 443)) {
			throw new IllegalArgumentException("O link HTTPS não corresponde à plataforma selecionada.");
		}
		return uri;
	}

	private static Map<Platform, List<String>> allowedHosts() {
		Map<Platform, List<String>> hosts = new EnumMap<>(Platform.class);
		hosts.put(Platform.YOUTUBE, List.of("youtube.com", "youtu.be", "youtube-nocookie.com"));
		hosts.put(Platform.INSTAGRAM, List.of("instagram.com"));
		hosts.put(Platform.TIKTOK, List.of("tiktok.com"));
		hosts.put(Platform.FACEBOOK, List.of("facebook.com", "fb.watch"));
		hosts.put(Platform.X, List.of("x.com", "twitter.com"));
		hosts.put(Platform.TWITCH, List.of("twitch.tv"));
		hosts.put(Platform.REDDIT, List.of("reddit.com", "redd.it"));
		return Map.copyOf(hosts);
	}

	private static String extension(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
	}

	private static void stopProcess(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}

	private static void deleteDirectory(Path directory) {
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted((first, second) -> second.getNameCount() - first.getNameCount())
				.forEach(path -> {
					try { Files.deleteIfExists(path); } catch (IOException ignored) { }
				});
		} catch (IOException ignored) { }
	}

	public enum Platform { YOUTUBE, INSTAGRAM, TIKTOK, FACEBOOK, X, TWITCH, REDDIT }

	public record DownloadedVideo(Path path, String fileName, long size) implements AutoCloseable {
		@Override
		public void close() {
			if (path != null && path.getParent() != null) deleteDirectory(path.getParent());
		}
	}
}
