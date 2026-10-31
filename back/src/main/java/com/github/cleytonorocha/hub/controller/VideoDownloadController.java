package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.VideoDownloadRequest;
import com.github.cleytonorocha.hub.security.AuthContext;
import com.github.cleytonorocha.hub.service.VideoDownloadFailure;
import com.github.cleytonorocha.hub.service.VideoDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

@RestController
@RequestMapping("/api/video-downloads")
@PreAuthorize("hasAuthority('VIDEO_DOWNLOAD')")
public class VideoDownloadController {
    private final VideoDownloadService downloads;
    public VideoDownloadController(VideoDownloadService downloads) { this.downloads = downloads; }

    @PostMapping
    public ResponseEntity<VideoDownloadService.Created> create(@Valid @RequestBody VideoDownloadRequest request) {
        VideoDownloadService.Created created = downloads.create(AuthContext.userId(), request);
        return ResponseEntity.accepted().location(URI.create(created.statusUrl())).cacheControl(CacheControl.noStore()).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VideoDownloadService.View> status(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(downloads.status(AuthContext.userId(), id));
    }

    @GetMapping("/{id}/file")
    public WebAsyncTask<Void> file(@PathVariable String id, HttpServletResponse response) {
        VideoDownloadService.Transfer transfer = downloads.claim(AuthContext.userId(), id);
        response.setContentType(transfer.contentType());
        response.setContentLengthLong(transfer.sizeBytes());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(transfer.fileName(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        WebAsyncTask<Void> task = new WebAsyncTask<>(VideoDownloadService.TRANSFER_TIMEOUT_MILLIS, () -> {
            downloads.stream(transfer, response.getOutputStream());
            return null;
        });
        task.onTimeout(() -> { downloads.abortTransfer(transfer); return null; });
        task.onError(() -> { downloads.abortTransfer(transfer); return null; });
        task.onCompletion(() -> downloads.abortTransfer(transfer));
        return task;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        downloads.cancel(AuthContext.userId(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(VideoDownloadFailure.class)
    public ResponseEntity<VideoDownloadService.PublicError> failure(VideoDownloadFailure failure) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore());
        if (failure.status() == HttpStatus.TOO_MANY_REQUESTS) response.header(HttpHeaders.RETRY_AFTER, "5");
        return response.body(new VideoDownloadService.PublicError(failure.code(), failure.getMessage()));
    }
}
