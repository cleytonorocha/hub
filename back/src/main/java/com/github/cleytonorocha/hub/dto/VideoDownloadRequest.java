package com.github.cleytonorocha.hub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VideoDownloadRequest(
	@NotNull Platform platform,
	@NotBlank @Size(max = 2048) String url,
	Integer preferredQuality,
	boolean reduceFileSize
) {
	public VideoDownloadRequest(Platform platform, String url, Integer preferredQuality) {
		this(platform, url, preferredQuality, false);
	}
	public int quality() { return preferredQuality == null ? 720 : preferredQuality; }
	public enum Platform {
		YOUTUBE,
		INSTAGRAM
	}
}
