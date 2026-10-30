package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.TaskBackupDtos.Confirmation;
import com.github.cleytonorocha.hub.dto.TaskBackupDtos.Preview;
import com.github.cleytonorocha.hub.service.TaskBackupService;
import com.github.cleytonorocha.hub.service.TaskBackupService.PortableCopy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tasks/portable-copy")
public final class TaskBackupController {
	private final TaskBackupService backups;

	public TaskBackupController(TaskBackupService backups) { this.backups = backups; }

	@GetMapping("/export")
	public ResponseEntity<?> export() {
		PortableCopy copy = backups.exportPortableCopy();
		String filename = copy.zipped() ? "hub-portable-copy.zip" : "hub-portable-copy.json";
		MediaType mediaType = copy.zipped() ? MediaType.parseMediaType("application/zip") : MediaType.APPLICATION_JSON;
		var builder = ResponseEntity.ok().contentType(mediaType).contentLength(copy.size())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString());
		if (!copy.zipped()) return builder.body(copy.bytes());
		StreamingResponseBody body = output -> {
			try (var input = Files.newInputStream(copy.archivePath())) { input.transferTo(output); }
			finally { Files.deleteIfExists(copy.archivePath()); }
		};
		return builder.body(body);
	}

	@PostMapping(value = "/preview", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/zip"})
	public Preview preview(HttpServletRequest request) throws IOException {
		if (isJson(request)) return backups.preview(readBoundedJson(request));
		Path file = receivePackage(request);
		try { return backups.preview(file); }
		finally { Files.deleteIfExists(file); }
	}

	@PostMapping(value = "/import", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/zip"})
	public Confirmation confirm(@RequestHeader(name = "X-Task-Import-Preview", required = false) String token,
			HttpServletRequest request) throws IOException {
		if (token == null || token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revise o arquivo antes de importá-lo.");
		if (isJson(request)) return backups.confirm(readBoundedJson(request), token);
		Path file = receivePackage(request);
		try { return backups.confirm(file, token); }
		finally { Files.deleteIfExists(file); }
	}

	private byte[] readBoundedJson(HttpServletRequest request) throws IOException {
		long contentLength = request.getContentLengthLong();
		if (contentLength > TaskBackupService.MAX_FILE_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo excede o limite de 5 MiB.");
		}
		byte[] content = request.getInputStream().readNBytes(TaskBackupService.MAX_FILE_BYTES + 1);
		if (content.length > TaskBackupService.MAX_FILE_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo excede o limite de 5 MiB.");
		}
		return content;
	}

	private Path receivePackage(HttpServletRequest request) throws IOException {
		long contentLength = request.getContentLengthLong();
		if (contentLength > TaskBackupService.MAX_PACKAGE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O pacote excede 100 MiB.");
		Path file = Files.createTempFile("hub-copy-upload-", ".zip");
		long total = 0;
		try (var input = request.getInputStream(); var output = Files.newOutputStream(file)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				total += count;
				if (total > TaskBackupService.MAX_PACKAGE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O pacote excede 100 MiB.");
				output.write(buffer, 0, count);
			}
			return file;
		} catch (RuntimeException | IOException error) {
			Files.deleteIfExists(file);
			throw error;
		}
	}

	private boolean isJson(HttpServletRequest request) {
		return request.getContentType() != null && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE);
	}
}
