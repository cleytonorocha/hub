package com.github.cleytonorocha.hub.service;

import static org.junit.jupiter.api.Assertions.*;
import com.github.cleytonorocha.hub.dto.VideoDownloadRequest.Platform;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class VideoDownloadRunnerTests {
	@TempDir Path temp;
	private final ObjectMapper mapper = new ObjectMapper();
	private final VideoDownloadRunner runner = new VideoDownloadRunner(mapper, "unused", "unused", "unused", "unused");

	@Test void canonicalizesOnlyIndividualRecognizedVideos() {
		assertEquals("https://www.youtube.com/watch?v=BaW_jenozKc", VideoDownloadUrl.canonicalize(Platform.YOUTUBE, "https://youtu.be/BaW_jenozKc?list=ignored#fragment").toString());
		assertEquals("https://www.youtube.com/watch?v=BaW_jenozKc", VideoDownloadUrl.canonicalize(Platform.YOUTUBE, "https://m.youtube.com/shorts/BaW_jenozKc?si=tracking").toString());
		assertEquals("https://www.instagram.com/reel/ABC_123-/", VideoDownloadUrl.canonicalize(Platform.INSTAGRAM, "https://www.instagram.com/reels/ABC_123-/?utm_source=ignored").toString());
		for (String value : List.of("http://www.youtube.com/watch?v=BaW_jenozKc", "https://youtube.com.attacker.test/watch?v=BaW_jenozKc", "https://attacker.youtube.com/watch?v=BaW_jenozKc", "https://name:secret@youtube.com/watch?v=BaW_jenozKc", "https://youtube.com:444/watch?v=BaW_jenozKc", "https://youtube.com/playlist?list=123", "https://youtube.com/@channel", "https://127.0.0.1/watch?v=BaW_jenozKc", "file:///tmp/video", "https://instagram.com/reel/ABC/"))
			assertThrows(VideoDownloadFailure.class, () -> VideoDownloadUrl.canonicalize(Platform.YOUTUBE, value), value);
		assertThrows(VideoDownloadFailure.class, () -> VideoDownloadUrl.canonicalize(Platform.INSTAGRAM, "https://instagram.com/person/"));
	}

	@Test void initialDnsRejectsSpecialUseIpv4AndIpv6WithoutNetworkAccess() throws Exception {
		for (String address : List.of("0.0.0.0", "127.0.0.1", "10.1.2.3", "172.16.1.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2001:2::1", "2002:a00:1::1", "3fff::1"))
			assertFalse(VideoDownloadUrl.isPublic(InetAddress.getByName(address)), address);
		assertTrue(VideoDownloadUrl.isPublic(InetAddress.getByName("8.8.8.8")));
		assertTrue(VideoDownloadUrl.isPublic(InetAddress.getByName("2001:4860:4860::8888")));
	}

	@Test void finalOutputMustBeARegularFileInsideTheJob() throws Exception {
		Path job = Files.createDirectory(temp.resolve("job"));
		Path valid = Files.writeString(job.resolve("video.mp4"), "video");
		Path outside = Files.writeString(temp.resolve("outside.mp4"), "video");
		assertEquals(valid.toRealPath(), VideoDownloadRunner.validateFinalPath(job, valid.toString()));
		assertThrows(VideoDownloadFailure.class, () -> VideoDownloadRunner.validateFinalPath(job, outside.toString()));
		assertThrows(VideoDownloadFailure.class, () -> VideoDownloadRunner.validateFinalPath(job, job.toString()));
		assertThrows(VideoDownloadFailure.class, () -> VideoDownloadRunner.validateFinalPath(job, job.resolve("missing.mp4").toString()));
	}

	@Test void metadataRefusesCollectionsLiveProtectedAndPrivateSources() throws Exception {
		for (String json : List.of("{\"entries\":[]}", "{\"_type\":\"playlist\"}", "{\"is_live\":true}", "{\"live_status\":\"is_upcoming\"}", "{\"has_drm\":true}", "{\"availability\":\"private\"}"))
			assertThrows(VideoDownloadFailure.class, () -> VideoDownloadRunner.validateMetadata(mapper.readTree(json)));
		assertDoesNotThrow(() -> VideoDownloadRunner.validateMetadata(mapper.readTree("{\"availability\":\"public\",\"live_status\":\"was_live\"}")));
	}

	@Test void boundedProcessOutputDoesNotAccumulateUnboundedMetadata() throws Exception {
		var control = control(Instant.now().plusSeconds(10));
		var failure = assertThrows(VideoDownloadFailure.class, () -> runner.run(command("large"), control, 1024));
		assertEquals("OUTPUT_LIMIT", failure.code());
	}

	@Test void cancellationTerminatesControlledSubprocessAndItsDescendant() throws Exception {
		var control = control(Instant.now().plusSeconds(15));
		try (var executor = Executors.newSingleThreadExecutor()) {
			var future = executor.submit(() -> runner.run(command("parent"), control, 1024));
			Path parentPid = temp.resolve("parent.pid"), childPid = temp.resolve("child.pid");
			try {
				awaitFile(parentPid); awaitFile(childPid);
				long parent = Long.parseLong(Files.readString(parentPid)), child = Long.parseLong(Files.readString(childPid));
				control.check(); control.cancel();
				assertFalse(control.hasLiveProcesses(), "Cancellation must wait for the captured parent and child");
				assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
				awaitStopped(parent); awaitStopped(child);
			} finally { control.cancel(); }
		}
	}

	@Test void deadlineTerminatesControlledProcess() throws Exception {
		var control = control(Instant.now().plusMillis(600));
		var failure = assertThrows(VideoDownloadFailure.class, () -> runner.run(command("child"), control, 1024));
		assertEquals("TIME_LIMIT", failure.code());
		Path pid = temp.resolve("child.pid"); if (Files.exists(pid)) awaitStopped(Long.parseLong(Files.readString(pid)));
	}

	private VideoDownloadRunner.Control control(Instant deadline) {
		return new VideoDownloadRunner.Control(temp, URI.create("https://www.youtube.com/watch?v=BaW_jenozKc"), 720, deadline, Clock.systemUTC(), () -> {}, phase -> {});
	}
	private List<String> command(String mode) { return List.of(java(), "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(), mode, temp.toString()); }
	private static String java() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString(); }
	private static void awaitFile(Path path) throws Exception {
		long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while ((!Files.exists(path) || Files.size(path) == 0) && System.nanoTime() < until) Thread.sleep(10);
		assertTrue(Files.exists(path) && Files.size(path) > 0, "Fixture did not start");
	}
	private static void awaitStopped(long pid) throws Exception {
		long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) Thread.sleep(10);
		assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Process still alive: " + pid);
	}

	/** Actual portable subprocess fixtures; no shell, network or installed media tools needed. */
	public static class ProcessFixture {
		public static void main(String[] arguments) throws Exception {
			String mode = arguments[0]; Path directory = Path.of(arguments[1]);
			if (mode.equals("large")) { System.out.print("x".repeat(128 * 1024)); return; }
			Files.writeString(directory.resolve(mode + ".pid"), Long.toString(ProcessHandle.current().pid()));
			if (mode.equals("parent")) new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(), "child", directory.toString()).start();
			Thread.sleep(60000);
		}
	}
}
