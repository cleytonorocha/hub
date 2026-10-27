package com.github.cleytonorocha.hub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VideoDownloadRequest(
	@NotNull Platform platform,
	@NotBlank @Size(max = 2048) String url
) {
	public enum Platform {
		YOUTUBE,
		INSTAGRAM,
		TIKTOK,
		FACEBOOK,
		X,
		TWITCH,
		REDDIT
	}
}
