package com.github.cleytonorocha.hub.service;

import static org.junit.jupiter.api.Assertions.*;
import com.github.cleytonorocha.hub.dto.VideoDownloadRequest;
import com.github.cleytonorocha.hub.dto.VideoDownloadRequest.Platform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class VideoDownloadServiceTests {
	@TempDir Path temp;
	private final List<VideoDownloadService> services = new ArrayList<>();
	private final MutableClock clock = new MutableClock();
	private static final VideoDownloadService.Policy POLICY = new VideoDownloadService.Policy(
		Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(10), 1024, 8192, 0);
	@AfterEach void shutdown() { services.forEach(VideoDownloadService::shutdown); }

	@Test void defaultQualityAndNativeResolutionMetadataArePublished() throws Exception {
		var service = service(new FakeRunner(1080, 1920, 8, false));
		var created = service.create("alice", request(null));
		var ready = await(service, "alice", created.id(), VideoDownloadService.State.READY);
		assertEquals(720, ready.requestedQuality());
		assertEquals(1080, ready.effectiveQuality());
		assertTrue(ready.fallbackToOriginal());
		assertEquals("/api/video-downloads/" + created.id() + "/file", ready.downloadUrl());
		assertFalse(ready.toString().contains(temp.toString()));
	}

	@Test void capacityIncludesReadyFilesAndReleasesOnlyAfterRemoval() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, false));
		var alice = service.create("alice", request(720));
		await(service, "alice", alice.id(), VideoDownloadService.State.READY);
		assertEquals("CAPACITY_LIMIT", assertThrows(VideoDownloadFailure.class, () -> service.create("alice", request(720))).code());
		var bob = service.create("bob", request(720));
		await(service, "bob", bob.id(), VideoDownloadService.State.READY);
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(VideoDownloadFailure.class, () -> service.create("carol", request(720))).status());
		service.cancel("alice", alice.id());
		var carol = service.create("carol", request(720));
		await(service, "carol", carol.id(), VideoDownloadService.State.READY);
	}

	@Test void anotherAccountCannotObserveCancelOrClaimTheFile() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, false));
		var created = service.create("alice", request(480));
		await(service, "alice", created.id(), VideoDownloadService.State.READY);
		assertEquals(HttpStatus.NOT_FOUND, assertThrows(VideoDownloadFailure.class, () -> service.status("bob", created.id())).status());
		assertEquals(HttpStatus.NOT_FOUND, assertThrows(VideoDownloadFailure.class, () -> service.cancel("bob", created.id())).status());
		assertEquals(HttpStatus.NOT_FOUND, assertThrows(VideoDownloadFailure.class, () -> service.claim("bob", created.id())).status());
		assertEquals(VideoDownloadService.State.READY, service.status("alice", created.id()).state());
	}

	@Test void transfersUseOneClaimAndRemoveFilesOnSuccess() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, false));
		var created = service.create("alice", request(720));
		await(service, "alice", created.id(), VideoDownloadService.State.READY);
		var transfer = service.claim("alice", created.id());
		assertEquals(HttpStatus.CONFLICT, assertThrows(VideoDownloadFailure.class, () -> service.claim("alice", created.id())).status());
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		service.stream(transfer, output);
		assertEquals(8, output.size());
		assertEquals(VideoDownloadService.State.COMPLETED, service.status("alice", created.id()).state());
		awaitEmpty();
		service.create("alice", request(720));
	}

	@Test void disconnectedTransfersAreTerminalAndReleaseTheirReservation() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, false));
		var created = service.create("alice", request(720));
		await(service, "alice", created.id(), VideoDownloadService.State.READY);
		var transfer = service.claim("alice", created.id());
		assertThrows(IOException.class, () -> service.stream(transfer, new java.io.OutputStream() {
			@Override public void write(int value) throws IOException { throw new IOException("client disconnected"); }
		}));
		assertEquals("TRANSFER_FAILED", service.status("alice", created.id()).error().code());
		awaitEmpty();
		service.create("alice", request(720));
	}

	@Test void readyExpiryAndUnstartedTransferHaveIndependentDeadlines() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, false));
		var first = service.create("alice", request(720));
		await(service, "alice", first.id(), VideoDownloadService.State.READY);
		clock.advance(Duration.ofMinutes(11)); service.maintain();
		assertEquals(VideoDownloadService.State.EXPIRED, service.status("alice", first.id()).state());
		assertEquals(HttpStatus.GONE, assertThrows(VideoDownloadFailure.class, () -> service.claim("alice", first.id())).status());
		var second = service.create("alice", request(720));
		await(service, "alice", second.id(), VideoDownloadService.State.READY);
		service.claim("alice", second.id());
		clock.advance(Duration.ofSeconds(31)); service.maintain();
		assertEquals("TRANSFER_TIMEOUT", service.status("alice", second.id()).error().code());
		assertEquals(0, temporaryFileCount());
	}

	@Test void cancelAndPreparationTimeoutStopWorkersAndCleanPartialFiles() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, true));
		var cancelled = service.create("alice", request(720));
		waitForFiles(); service.cancel("alice", cancelled.id());
		assertEquals(VideoDownloadService.State.CANCELLED, service.status("alice", cancelled.id()).state());
		assertEquals(0, temporaryFileCount());
		var timed = service.create("alice", request(720));
		waitForFiles(); clock.advance(Duration.ofMinutes(11)); service.maintain();
		await(service, "alice", timed.id(), VideoDownloadService.State.FAILED);
		assertEquals("TIME_LIMIT", service.status("alice", timed.id()).error().code());
		awaitEmpty();
	}

	@Test void finalSizeAndTemporaryBudgetAreIndependentlyEnforced() throws Exception {
		var service = service(new FakeRunner(640, 360, 2048, false));
		var created = service.create("alice", request(720));
		assertEquals("SIZE_LIMIT", await(service, "alice", created.id(), VideoDownloadService.State.FAILED).error().code());
		var overflowing = service(new FakeRunner(640, 360, 9000, true));
		var second = overflowing.create("bob", request(720));
		assertEquals("TEMP_STORAGE_LIMIT", await(overflowing, "bob", second.id(), VideoDownloadService.State.FAILED).error().code());
	}

	@Test void cleanupFailureRetainsCapacityUntilMaintenanceCanDelete() throws Exception {
		var service = new CleanupFailureService(new FakeRunner(640, 360, 8, false), temp.resolve("cleanup"), clock);
		services.add(service);
		var created = service.create("alice", request(720));
		await(service, "alice", created.id(), VideoDownloadService.State.READY);
		service.refuse = true; service.cancel("alice", created.id());
		assertEquals("CAPACITY_LIMIT", assertThrows(VideoDownloadFailure.class, () -> service.create("alice", request(720))).code());
		service.refuse = false; service.maintain();
		service.create("alice", request(720));
	}

	@Test void startupRemovesOnlyDedicatedJobDirectories() throws Exception {
		Path root = temp.resolve("startup"); Files.createDirectories(root);
		Path orphan = root.resolve("job-00000000-0000-0000-0000-000000000001"); Files.createDirectories(orphan); Files.writeString(orphan.resolve("partial"), "orphan");
		Path unrelated = root.resolve("keep.txt"); Files.writeString(unrelated, "keep");
		services.add(new VideoDownloadService(new FakeRunner(640, 360, 8, false), root, clock, POLICY));
		assertFalse(Files.exists(orphan)); assertEquals("keep", Files.readString(unrelated));
	}

	@Test void liveDescendantKeepsReservationUntilMaintenanceConfirmsItsExit() throws Exception {
		StubbornHandle child = new StubbornHandle();
		FakeRunner runner = new FakeRunner(640, 360, 8, false) {
			@Override Media prepare(Control control) throws IOException, InterruptedException {
				control.track(child); return super.prepare(control);
			}
		};
		var service = service(runner);
		var created = service.create("alice", request(720));
		await(service, "alice", created.id(), VideoDownloadService.State.READY);
		try {
			service.cancel("alice", created.id());
			assertTrue(child.isAlive());
			assertEquals("CAPACITY_LIMIT", assertThrows(VideoDownloadFailure.class, () -> service.create("alice", request(720))).code());
			assertTrue(temporaryFileCount() > 0, "A live descendant must retain the temporary directory");
		} finally { child.alive = false; service.maintain(); }
		awaitEmpty(); service.create("alice", request(720));
	}

	@Test void blockedServletWriteDoesNotBlockCancellationOrMaintenanceAndKeepsCapacity() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, false));
		var created = service.create("alice", request(720));
		await(service, "alice", created.id(), VideoDownloadService.State.READY);
		var transfer = service.claim("alice", created.id());
		CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicBoolean outputClosed = new AtomicBoolean();
		var output = new java.io.OutputStream() {
			@Override public void write(int value) throws IOException {
				writing.countDown();
				try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture not released"); }
				catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
			}
			@Override public void close() throws IOException {
				outputClosed.set(true);
				try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
			}
		};
		try (var executor = Executors.newSingleThreadExecutor()) {
			var stream = executor.submit(() -> { service.stream(transfer, output); return null; });
			try {
				assertTrue(writing.await(2, TimeUnit.SECONDS));
				clock.advance(Duration.ofMinutes(31));
				assertTimeoutPreemptively(Duration.ofSeconds(2), service::maintain);
				assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.cancel("alice", created.id()));
				assertFalse(outputClosed.get(), "Only the servlet container owns the output stream");
				assertEquals("CAPACITY_LIMIT", assertThrows(VideoDownloadFailure.class, () -> service.create("alice", request(720))).code());
				assertTrue(temporaryFileCount() > 0, "Files stay reserved until stream finally runs");
			} finally { release.countDown(); }
			assertThrows(ExecutionException.class, () -> stream.get(3, TimeUnit.SECONDS));
		}
		awaitEmpty(); service.create("alice", request(720));
	}

	@Test void aCancelledUnstartedCallbackCannotWriteAfterItsFilesAreRemoved() throws Exception {
		var service = service(new FakeRunner(640, 360, 8, false));
		var created = service.create("alice", request(720));
		await(service, "alice", created.id(), VideoDownloadService.State.READY);
		var transfer = service.claim("alice", created.id());
		service.cancel("alice", created.id()); awaitEmpty();
		var output = new ByteArrayOutputStream();
		assertThrows(IOException.class, () -> service.stream(transfer, output));
		assertEquals(0, output.size());
		assertEquals(VideoDownloadService.State.CANCELLED, service.status("alice", created.id()).state());
	}

	@Test void sizeMonitoringToleratesConcurrentFragmentRenamesButNotMissingRoot() throws Exception {
		Path directory = Files.createDirectory(temp.resolve("fragments"));
		Files.write(directory.resolve("stable"), new byte[8]);
		Path first = directory.resolve("fragment.part"), second = directory.resolve("fragment.done");
		Files.write(first, new byte[16]);
		AtomicBoolean stop = new AtomicBoolean();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var renamer = executor.submit(() -> {
				while (!stop.get()) {
					try { Files.move(first, second); } catch (IOException ignored) { }
					try { Files.move(second, first); } catch (IOException ignored) { }
				}
			});
			try {
				for (int i = 0; i < 300; i++) assertTrue(VideoDownloadService.directorySize(directory) >= 8);
			} finally { stop.set(true); renamer.get(3, TimeUnit.SECONDS); }
		}
		assertThrows(java.nio.file.NoSuchFileException.class, () -> VideoDownloadService.directorySize(temp.resolve("missing")));
		assertThrows(IOException.class, () -> VideoDownloadService.directorySize(directory.resolve("stable")));
	}

	@Test void invalidQualityAndMissingDependenciesDoNotCreateJobs() throws Exception {
		var runner = new FakeRunner(640, 360, 8, false);
		var service = service(runner);
		assertEquals(HttpStatus.BAD_REQUEST, assertThrows(VideoDownloadFailure.class, () -> service.create("alice", request(999))).status());
		runner.available = false;
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(VideoDownloadFailure.class, () -> service.create("alice", request(720))).status());
		assertEquals(0, temporaryFileCount());
	}

	private VideoDownloadService service(FakeRunner runner) {
		var service = new VideoDownloadService(runner, temp.resolve("service-" + services.size()), clock, POLICY); services.add(service); return service;
	}
	private static VideoDownloadRequest request(Integer quality) { return new VideoDownloadRequest(Platform.YOUTUBE, "https://youtu.be/BaW_jenozKc", quality); }
	private static VideoDownloadService.View await(VideoDownloadService service, String owner, String id, VideoDownloadService.State expected) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			var view = service.status(owner, id); if (view.state() == expected) return view; Thread.sleep(10);
		}
		fail("Expected " + expected + " but got " + service.status(owner, id)); return null;
	}
	private long temporaryFileCount() throws IOException { try (var paths = Files.walk(temp)) { return paths.filter(Files::isRegularFile).count(); } }
	private void waitForFiles() throws Exception { long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos(); while (temporaryFileCount() == 0 && System.nanoTime() < deadline) Thread.sleep(10); assertTrue(temporaryFileCount() > 0); }
	private void awaitEmpty() throws Exception { long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos(); while (temporaryFileCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10); assertEquals(0, temporaryFileCount()); }

	private static class FakeRunner extends VideoDownloadRunner {
		final int width, height, size; final boolean wait; volatile boolean available = true;
		FakeRunner(int width, int height, int size, boolean wait) { super(new ObjectMapper(), "unused", "unused", "unused", "unused"); this.width = width; this.height = height; this.size = size; this.wait = wait; }
		@Override boolean available() { return available; }
		@Override Media prepare(Control control) throws IOException, InterruptedException {
			Path file = control.directory.resolve("video.mp4"); Files.write(file, new byte[size]);
			control.check();
			while (wait) { control.check(); Thread.sleep(10); }
			return new Media(file, "video.mp4", "video/mp4", size, width, height);
		}
	}
	private static class CleanupFailureService extends VideoDownloadService {
		volatile boolean refuse;
		CleanupFailureService(VideoDownloadRunner runner, Path root, Clock clock) { super(runner, root, clock, POLICY); }
		@Override void removeTemporaryDirectory(Path directory) throws IOException { if (refuse) throw new IOException("held file"); super.removeTemporaryDirectory(directory); }
	}
	private static class MutableClock extends Clock {
		volatile Instant now = Instant.parse("2026-10-30T12:00:00Z");
		void advance(Duration duration) { now = now.plus(duration); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}

	/** Represents a child process that refuses termination until the OS later reports it dead. */
	private static class StubbornHandle implements ProcessHandle {
		volatile boolean alive = true;
		@Override public long pid() { return Long.MAX_VALUE; }
		@Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
		@Override public Stream<ProcessHandle> children() { return Stream.empty(); }
		@Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
		@Override public Info info() { return ProcessHandle.current().info(); }
		@Override public CompletableFuture<ProcessHandle> onExit() { return new CompletableFuture<>(); }
		@Override public boolean supportsNormalTermination() { return false; }
		@Override public boolean destroy() { return false; }
		@Override public boolean destroyForcibly() { return false; }
		@Override public boolean isAlive() { return alive; }
		@Override public int compareTo(ProcessHandle other) { return Long.compare(pid(), other.pid()); }
	}
}
