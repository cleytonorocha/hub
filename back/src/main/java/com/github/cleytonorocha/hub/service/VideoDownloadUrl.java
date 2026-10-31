package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.VideoDownloadRequest.Platform;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Accept individual media links only, never arbitrary extractor URLs or subdomains. */
final class VideoDownloadUrl {
	private static final Set<String> YOUTUBE = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com");
	private static final Set<String> INSTAGRAM = Set.of("instagram.com", "www.instagram.com");
	private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
	private static final Pattern INSTAGRAM_PATH = Pattern.compile("^/(reel|reels|p|tv)/([A-Za-z0-9_-]{1,100})/?$");
	private VideoDownloadUrl() {}

	static URI canonicalize(Platform platform, String value) {
		if (platform == null || value == null || value.isBlank() || value.length() > 2048) throw VideoDownloadFailure.invalid("Informe a plataforma e um link HTTPS de vídeo individual.");
		try {
			URI uri = URI.create(value.strip());
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
			String path = uri.getRawPath();
			if (platform == Platform.YOUTUBE) {
				String id = null;
				if (host.equals("youtu.be")) id = path != null && path.matches("/[A-Za-z0-9_-]{11}/?") ? path.substring(1).replace("/", "") : null;
				else if (YOUTUBE.contains(host)) {
					if ("/watch".equals(path) && uri.getRawQuery() != null) {
						for (String part : uri.getRawQuery().split("&")) {
							String[] pair = part.split("=", 2);
							if (pair[0].equals("v") && pair.length == 2) { if (id != null) throw new IllegalArgumentException(); id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8); }
						}
					} else if (path != null && path.matches("/(shorts|embed|v)/[A-Za-z0-9_-]{11}/?")) id = path.split("/")[2];
				}
				if (id != null && YOUTUBE_ID.matcher(id).matches()) return URI.create("https://www.youtube.com/watch?v=" + id);
			} else if (platform == Platform.INSTAGRAM && INSTAGRAM.contains(host) && path != null) {
				var match = INSTAGRAM_PATH.matcher(path);
				if (match.matches()) return URI.create("https://www.instagram.com/" + (match.group(1).equals("reels") ? "reel" : match.group(1)) + "/" + match.group(2) + "/");
			}
		} catch (IllegalArgumentException ignored) { }
		throw VideoDownloadFailure.invalid("Use um link HTTPS de vídeo individual da plataforma selecionada, sem credenciais ou porta alternativa.");
	}

	static void requirePublicDns(URI uri) {
		try {
			InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
			if (addresses.length == 0) throw new IllegalArgumentException();
			for (InetAddress address : addresses) if (!isPublic(address)) throw new IllegalArgumentException();
		} catch (Exception error) {
			throw VideoDownloadFailure.failed("SOURCE_UNAVAILABLE", "Não foi possível acessar um endereço público da plataforma.");
		}
	}

	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
		byte[] bytes = address.getAddress();
		if (bytes.length == 16) {
			if ((bytes[0] & 0xe0) != 0x20) return false;
			int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
			// Special-use, benchmark, documentation and deprecated 6to4 space are not public origins.
			return !(a == 0x20 && b == 0x01 && ((c & 0xfe) == 0 || (c == 0x0d && d == 0xb8)))
				&& !(a == 0x20 && b == 0x02) && !(a == 0x3f && b == 0xff && c < 0x10);
		}
		int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
		return a != 0 && a != 10 && a != 127 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
			&& !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
			&& !(a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99)))
			&& !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
			&& !(a == 203 && b == 0 && c == 113);
	}
}
