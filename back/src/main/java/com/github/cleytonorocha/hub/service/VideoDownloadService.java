package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.VideoDownloadRequest;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded ephemeral jobs. A capacity reservation lasts until every temporary file is removed. */
@Service
public class VideoDownloadService {
    static final long MAX_FILE_SIZE = 1024L * 1024 * 1024;
    static final long TEMP_BYTES_PER_JOB = 3 * MAX_FILE_SIZE;
    static final long DISK_MARGIN = MAX_FILE_SIZE / 2;
    public static final long TRANSFER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final Logger LOG = LoggerFactory.getLogger(VideoDownloadService.class);
    private static final Set<State> TERMINAL = Set.of(State.COMPLETED, State.FAILED, State.CANCELLED, State.EXPIRED);
    private final VideoDownloadRunner runner;
    private final Path root;
    private final Clock clock;
    private final Policy policy;
    private final ExecutorService workers;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Set<Path> orphanDirectories = ConcurrentHashMap.newKeySet();
    private volatile boolean accepting = true;
    private volatile boolean initialized;

    @Autowired
    public VideoDownloadService(VideoDownloadRunner runner,
            @Value("${hub.video-download.temp-root:${java.io.tmpdir}/hub-video-downloads}") String root) {
        this(runner, Path.of(root), Clock.systemUTC(), Policy.defaults());
    }

    VideoDownloadService(VideoDownloadRunner runner, Path root, Clock clock, Policy policy) {
        this.runner = runner; this.root = root.toAbsolutePath().normalize(); this.clock = clock; this.policy = policy;
        this.workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).name("video-download-", 0).factory());
        initializeStorage();
    }

    public synchronized Created create(String ownerId, VideoDownloadRequest request) {
        if (request == null || !List.of(480, 720, 1080).contains(request.quality())) throw VideoDownloadFailure.invalid("Escolha uma qualidade de 480p, 720p ou 1080p.");
        URI url = VideoDownloadUrl.canonicalize(request.platform(), request.url());
        if (ownerId == null || ownerId.isBlank()) throw new VideoDownloadFailure("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "Entre na sua conta para continuar.");
        if (!accepting || !initialized || !orphanDirectories.isEmpty() || !runner.available()) throw VideoDownloadFailure.unavailable();
        pruneRecords();
        long reserved = jobs.values().stream().filter(job -> job.reserved).count();
        if (reserved >= 2 || jobs.values().stream().anyMatch(job -> job.reserved && job.ownerId.equals(ownerId)))
            throw new VideoDownloadFailure("CAPACITY_LIMIT", HttpStatus.TOO_MANY_REQUESTS, "Já existe uma preparação sua em andamento ou o servidor está ocupado. Tente novamente em instantes.");
        try {
            if (Files.getFileStore(root).getUsableSpace() < policy.tempBytes * (reserved + 1) + policy.diskMargin)
                throw new VideoDownloadFailure("STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Não há espaço temporário suficiente para preparar outro vídeo.");
            String id = UUID.randomUUID().toString();
            Path directory = Files.createDirectory(root.resolve("job-" + id));
            Job job = new Job(id, ownerId, directory, request.quality(), clock.instant());
            job.control = new VideoDownloadRunner.Control(directory, url, request.quality(), request.reduceFileSize(), job.createdAt.plus(policy.preparation), clock,
                () -> checkResources(job), phase -> { synchronized (job) { if (job.state == State.RUNNING) job.phase = phase; } });
            jobs.put(id, job);
            pruneRecords();
            try { workers.execute(() -> prepare(job)); }
            catch (RuntimeException error) { job.workerFinished = true; fail(job, VideoDownloadFailure.unavailable()); cleanup(job); throw VideoDownloadFailure.unavailable(); }
            return new Created(id, State.RUNNING, statusUrl(id));
        } catch (IOException error) { throw VideoDownloadFailure.unavailable(); }
    }

    public View status(String ownerId, String id) {
        Job job = own(ownerId, id);
        expire(job);
        synchronized (job) {
            VideoDownloadRunner.Media media = job.media;
            Integer actual = media == null ? null : media.effectiveQuality();
            return new View(job.id, job.state, job.phase, job.quality, job.createdAt, job.expiresAt,
                media == null ? null : media.fileName(), media == null ? null : media.contentType(), media == null ? null : media.sizeBytes(),
                media == null ? null : media.width(), media == null ? null : media.height(), actual,
                actual != null && actual > job.quality, job.state == State.READY ? statusUrl(id) + "/file" : null, job.error,
                job.transfer == null ? 0L : job.transfer.bytesTransferred(), job.control.reduceFileSize, media != null && media.sizeReduced());
        }
    }

    /** Reserve one stream before dispatching an async servlet callback. */
    public Transfer claim(String ownerId, String id) {
        Job job = own(ownerId, id);
        expire(job);
        synchronized (job) {
            if (job.state == State.EXPIRED) throw new VideoDownloadFailure("EXPIRED", HttpStatus.GONE, "Este arquivo expirou. Prepare o vídeo novamente.");
            if (job.state != State.READY) throw new VideoDownloadFailure("NOT_READY", HttpStatus.CONFLICT, "O arquivo não está disponível para iniciar uma nova transferência.");
            try {
                Path file = VideoDownloadRunner.validateFinalPath(job.directory, job.media.path().toString());
                if (Files.size(file) != job.media.sizeBytes()) throw new IOException("File changed");
                Transfer transfer = new Transfer(job, Files.newInputStream(file));
                job.transfer = transfer; job.state = State.TRANSFERRING; job.phase = null;
                job.transferStartedAt = clock.instant(); job.expiresAt = job.transferStartedAt.plus(policy.transfer);
                return transfer;
            } catch (IOException | VideoDownloadFailure error) {
                finish(job, State.FAILED, new PublicError("INVALID_OUTPUT", "O arquivo temporário não está mais disponível."));
                cleanup(job); throw new VideoDownloadFailure("EXPIRED", HttpStatus.GONE, "O arquivo temporário não está mais disponível. Prepare o vídeo novamente.");
            }
        }
    }

    public void stream(Transfer transfer, OutputStream output) throws IOException {
        Job job = transfer.job;
        try {
            synchronized (transfer) {
                if (transfer.stopped.get()) throw new IOException("Transfer stopped before dispatch");
                transfer.started = true;
            }
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = transfer.input.read(buffer)) != -1) {
                if (transfer.stopped.get() || !clock.instant().isBefore(job.expiresAt)) throw new IOException("Transfer stopped");
                output.write(buffer, 0, count);
                transfer.bytesTransferred.addAndGet(count);
            }
            if (transfer.stopped.get()) throw new IOException("Transfer stopped");
            output.flush();
            synchronized (job) { if (job.state == State.TRANSFERRING) finish(job, State.COMPLETED, null); }
        } catch (IOException error) {
            synchronized (job) { if (job.state == State.TRANSFERRING) finish(job, State.FAILED, new PublicError("TRANSFER_FAILED", "A transferência foi interrompida. Prepare o vídeo novamente.")); }
            throw error;
        } finally {
            stopTransferInput(transfer);
            transfer.streamFinished = true;
            cleanup(job);
        }
    }

    /** Handles request timeout, dispatch failure and callbacks that never started. */
    public void abortTransfer(Transfer transfer) {
        Job job = transfer.job;
        synchronized (job) { if (job.state == State.TRANSFERRING) finish(job, State.FAILED, new PublicError("TRANSFER_FAILED", "A transferência foi interrompida. Prepare o vídeo novamente.")); }
        stopTransferInput(transfer); cleanup(job);
    }

    public void cancel(String ownerId, String id) {
        Job job = own(ownerId, id);
        synchronized (job) { if (!TERMINAL.contains(job.state)) finish(job, State.CANCELLED, null); }
        job.control.cancel();
        Transfer transfer = job.transfer; if (transfer != null) stopTransferInput(transfer);
        try { job.finished.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        cleanup(job);
    }

    private void prepare(Job job) {
        try {
            VideoDownloadRunner.Media media = runner.prepare(job.control);
            if (media.sizeBytes() <= 0 || media.sizeBytes() > policy.maxFileSize) throw VideoDownloadFailure.failed("SIZE_LIMIT", "O arquivo final deve ter até 1 GiB.");
            synchronized (job) {
                if (job.state == State.RUNNING) {
                    job.media = media; job.state = State.READY; job.phase = null; job.expiresAt = clock.instant().plus(policy.ready);
                }
            }
        } catch (VideoDownloadFailure error) { fail(job, error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); synchronized (job) { if (job.state == State.RUNNING) finish(job, State.CANCELLED, null); } }
        catch (Exception error) { fail(job, VideoDownloadFailure.failed("EXTRACTION_FAILED", "Não foi possível preparar este vídeo. Confira o link e tente novamente.")); }
        finally { job.workerFinished = true; job.finished.countDown(); cleanup(job); }
    }

    private void checkResources(Job job) {
        try {
            if (directorySize(job.directory) > policy.tempBytes) throw VideoDownloadFailure.failed("TEMP_STORAGE_LIMIT", "O vídeo excedeu o espaço temporário permitido.");
            if (Files.getFileStore(root).getUsableSpace() < policy.diskMargin) throw new VideoDownloadFailure("STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "O armazenamento temporário ficou sem espaço disponível.");
        } catch (IOException error) { throw VideoDownloadFailure.unavailable(); }
    }

    private void fail(Job job, VideoDownloadFailure error) {
        synchronized (job) { if (job.state == State.RUNNING) { finish(job, State.FAILED, new PublicError(error.code(), error.getMessage())); LOG.info("Video preparation {} failed ({})", job.id, error.code()); } }
    }

    private void finish(Job job, State state, PublicError error) {
        job.state = state; job.phase = null; job.error = error; job.terminalAt = clock.instant(); job.expiresAt = job.terminalAt.plus(policy.records);
    }

    private Job own(String ownerId, String id) {
        Job job = jobs.get(id);
        if (job == null || !job.ownerId.equals(ownerId)) throw new VideoDownloadFailure("NOT_FOUND", HttpStatus.NOT_FOUND, "Preparação não encontrada.");
        return job;
    }

    private void expire(Job job) {
        boolean stop = false; Transfer transfer = null;
        synchronized (job) {
            Instant now = clock.instant();
            if (job.state == State.RUNNING && !now.isBefore(job.createdAt.plus(policy.preparation))) {
                finish(job, State.FAILED, new PublicError("TIME_LIMIT", "A preparação excedeu o limite de dez minutos.")); stop = true;
            } else if (job.state == State.READY && !now.isBefore(job.expiresAt)) finish(job, State.EXPIRED, new PublicError("EXPIRED", "Este arquivo expirou. Prepare o vídeo novamente."));
            else if (job.state == State.TRANSFERRING && (!now.isBefore(job.expiresAt)
                    || (job.transfer != null && !job.transfer.started && !now.isBefore(job.transferStartedAt.plusSeconds(30))))) {
                finish(job, State.FAILED, new PublicError("TRANSFER_TIMEOUT", "A transferência excedeu o tempo permitido.")); transfer = job.transfer;
            }
        }
        if (stop) job.control.cancel();
        if (transfer != null) stopTransferInput(transfer);
        cleanup(job);
    }

    private void stopTransferInput(Transfer transfer) {
        synchronized (transfer) {
            transfer.stopped.set(true);
            if (!transfer.inputClosed) try { transfer.input.close(); transfer.inputClosed = true; } catch (IOException ignored) { }
        }
        // Servlet output belongs to the callable/container. Closing it here can flush and
        // indefinitely block the janitor, status endpoint or cancellation request.
    }

    private void cleanup(Job job) {
        synchronized (job) {
            if (!job.reserved || !TERMINAL.contains(job.state) || !job.workerFinished || job.control.hasLiveProcesses()) return;
            Transfer transfer = job.transfer;
            if (transfer != null) synchronized (transfer) {
                if (!transfer.inputClosed || (transfer.started ? !transfer.streamFinished : !transfer.stopped.get())) return;
            }
            try { removeTemporaryDirectory(job.directory); job.reserved = false; }
            catch (IOException error) {
                if (!job.cleanupReported) LOG.warn("Temporary video cleanup will be retried for job {}", job.id);
                job.cleanupReported = true;
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void maintain() {
        if (!initialized) initializeStorage();
        for (Path directory : orphanDirectories) try { deleteDirectory(directory); orphanDirectories.remove(directory); } catch (IOException ignored) { }
        for (Job job : jobs.values()) {
            try {
                expire(job);
                if (job.state == State.RUNNING) checkResources(job);
                else if (job.reserved && TERMINAL.contains(job.state)) {
                    job.control.terminateProcesses();
                    if (job.transfer != null) stopTransferInput(job.transfer);
                    cleanup(job);
                }
            }
            catch (VideoDownloadFailure error) { fail(job, error); job.control.cancel(); cleanup(job); }
        }
        pruneRecords();
    }

    private synchronized void pruneRecords() {
        Instant cutoff = clock.instant().minus(policy.records);
        List<Job> terminal = jobs.values().stream().filter(job -> !job.reserved && job.terminalAt != null)
            .sorted(Comparator.comparing(job -> job.terminalAt)).toList();
        int excess = Math.max(0, jobs.size() - 128);
        for (Job job : terminal) if (job.terminalAt.isBefore(cutoff) || excess-- > 0) jobs.remove(job.id, job);
    }

    private synchronized void initializeStorage() {
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
            try (Stream<Path> entries = Files.list(root)) {
                for (Path path : entries.filter(path -> path.getFileName().toString().matches("job-[0-9a-fA-F-]{36}")).toList()) {
                    if (jobs.values().stream().noneMatch(job -> job.directory.equals(path))) {
                        try { deleteDirectory(path); } catch (IOException error) { orphanDirectories.add(path); }
                    }
                }
            }
            initialized = true;
        } catch (IOException | RuntimeException error) { initialized = false; LOG.warn("Video temporary storage is unavailable; the rest of the Hub remains active"); }
    }

    static long directorySize(Path directory) throws IOException {
        requireDirectory(directory);
        long[] total = { 0 };
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) throw new IOException("Unexpected temporary symlink");
                if (attributes.isRegularFile()) total[0] = Math.addExact(total[0], attributes.size());
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                if (error instanceof NoSuchFileException && !file.equals(directory)) return FileVisitResult.CONTINUE;
                throw error;
            }
            @Override public FileVisitResult postVisitDirectory(Path file, IOException error) throws IOException {
                if (error != null && (!(error instanceof NoSuchFileException) || file.equals(directory))) throw error;
                return FileVisitResult.CONTINUE;
            }
        });
        requireDirectory(directory);
        return total[0];
    }

    private static void requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new IOException("Invalid temporary directory");
    }

    static void deleteDirectory(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    void removeTemporaryDirectory(Path directory) throws IOException { deleteDirectory(directory); }

    @PreDestroy
    public void shutdown() {
        accepting = false;
        for (Job job : jobs.values()) {
            synchronized (job) { if (!TERMINAL.contains(job.state)) finish(job, State.CANCELLED, null); }
            job.control.cancel(); if (job.transfer != null) stopTransferInput(job.transfer);
        }
        workers.shutdown();
        try { if (!workers.awaitTermination(3, TimeUnit.SECONDS)) workers.shutdownNow(); } catch (InterruptedException error) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
        for (Job job : jobs.values()) cleanup(job);
    }

    private static String statusUrl(String id) { return "/api/video-downloads/" + id; }
    public enum State { RUNNING, READY, TRANSFERRING, COMPLETED, FAILED, CANCELLED, EXPIRED }
    public record Created(String id, State state, String statusUrl) {}
    public record PublicError(String code, String message) {}
    public record View(String id, State state, String phase, int requestedQuality, Instant createdAt, Instant expiresAt,
        String fileName, String contentType, Long sizeBytes, Integer width, Integer height, Integer effectiveQuality,
        boolean fallbackToOriginal, String downloadUrl, PublicError error, long bytesTransferred,
        boolean reduceFileSize, boolean sizeReduced) {}

    public static final class Transfer {
        private final Job job; private final InputStream input;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicLong bytesTransferred = new AtomicLong();
        private volatile boolean started, streamFinished, inputClosed;
        private Transfer(Job job, InputStream input) { this.job = job; this.input = input; }
        public String fileName() { return job.media.fileName(); }
        public String contentType() { return job.media.contentType(); }
        public long sizeBytes() { return job.media.sizeBytes(); }
        public long bytesTransferred() { return Math.min(bytesTransferred.get(), sizeBytes()); }
    }

    private static final class Job {
        final String id, ownerId; final Path directory; final int quality; final Instant createdAt;
        final CountDownLatch finished = new CountDownLatch(1);
        volatile State state = State.RUNNING;
        volatile boolean reserved = true, workerFinished, cleanupReported;
        volatile Instant expiresAt, terminalAt, transferStartedAt;
        String phase = "ANALYZING"; PublicError error;
        VideoDownloadRunner.Control control; VideoDownloadRunner.Media media; volatile Transfer transfer;
        Job(String id, String ownerId, Path directory, int quality, Instant createdAt) {
            this.id = id; this.ownerId = ownerId; this.directory = directory; this.quality = quality; this.createdAt = createdAt;
        }
    }

    record Policy(Duration preparation, Duration ready, Duration transfer, Duration records, long maxFileSize, long tempBytes, long diskMargin) {
        static Policy defaults() { return new Policy(Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(10), MAX_FILE_SIZE, TEMP_BYTES_PER_JOB, DISK_MARGIN); }
    }
}
