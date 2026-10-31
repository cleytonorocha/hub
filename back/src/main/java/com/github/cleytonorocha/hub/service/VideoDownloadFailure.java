package com.github.cleytonorocha.hub.service;

import org.springframework.http.HttpStatus;

/** A safe, stable error that may be returned to the downloader UI. */
public final class VideoDownloadFailure extends RuntimeException {
	private final String code;
	private final HttpStatus status;
	public VideoDownloadFailure(String code, HttpStatus status, String message) {
		super(message); this.code = code; this.status = status;
	}
	public String code() { return code; }
	public HttpStatus status() { return status; }
	static VideoDownloadFailure invalid(String message) { return new VideoDownloadFailure("INVALID_INPUT", HttpStatus.BAD_REQUEST, message); }
	static VideoDownloadFailure unavailable() { return new VideoDownloadFailure("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "O serviço de download está indisponível. Verifique as dependências e tente novamente."); }
	static VideoDownloadFailure failed(String code, String message) { return new VideoDownloadFailure(code, HttpStatus.BAD_GATEWAY, message); }
}
