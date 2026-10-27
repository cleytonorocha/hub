package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.VideoDownloadRequest;
import com.github.cleytonorocha.hub.service.VideoDownloadService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/video-downloads")
public class VideoDownloadController {
	private final VideoDownloadService downloads;

	public VideoDownloadController(VideoDownloadService downloads) { this.downloads = downloads; }

	@PostMapping
	public ResponseEntity<StreamingResponseBody> download(@Valid @RequestBody VideoDownloadRequest request) {
		VideoDownloadService.DownloadedVideo video;
		try {
			video = downloads.download(VideoDownloadService.Platform.valueOf(request.platform().name()), request.url());
		} catch (IllegalArgumentException error) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
		} catch (IllegalStateException error) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, error.getMessage());
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "O download foi interrompido.");
		} catch (IOException error) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível baixar o vídeo desta plataforma.");
		}

		StreamingResponseBody body = output -> {
			try (video; InputStream input = Files.newInputStream(video.path())) {
				input.transferTo(output);
				output.flush();
			}
		};
		String extension = video.fileName().substring(video.fileName().lastIndexOf('.') + 1).toLowerCase();
		MediaType mediaType = switch (extension) {
			case "webm" -> MediaType.parseMediaType("video/webm");
			case "mkv" -> MediaType.parseMediaType("video/x-matroska");
			case "mov" -> MediaType.parseMediaType("video/quicktime");
			default -> MediaType.parseMediaType("video/mp4");
		};
		return ResponseEntity.ok()
			.contentType(mediaType)
			.contentLength(video.size())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(video.fileName()).build().toString())
			.body(body);
	}
}
