package com.github.cleytonorocha.hub.security.google;

import com.sun.jna.platform.win32.Crypt32Util;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Component
public final class LocalUserCredentialKeyProvider implements CredentialKeyProvider {
	private final Path keyFile;
	private final Path legacyKeyFile = legacyKeyPath();

	public LocalUserCredentialKeyProvider(@Value("${hub.google.oauth.credential-key-file:}") String keyFile) {
		this.keyFile = keyFile == null || keyFile.isBlank() ? null : Path.of(keyFile).toAbsolutePath().normalize();
	}

	@Override
	public byte[] loadKey() {
		if (keyFile == null) throw new IllegalStateException("Google credential key is not configured.");
		try {
			if (Files.isSymbolicLink(keyFile) || !Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS) || Files.size(keyFile) != 32) {
				throw new IllegalStateException("Google credential key file must contain exactly 32 bytes.");
			}
			byte[] key = Files.readAllBytes(keyFile);
			if (key.length != 32) throw new IllegalStateException("Stored Google credential key has invalid length.");
			return key;
		} catch (IOException exception) {
			throw new IllegalStateException("Google credential key is unavailable.", exception);
		}
	}

	@Override
	public byte[] loadLegacyKey() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
			throw new IllegalStateException("The legacy Google credential key is only available to the original Windows user.");
		}
		try {
			if (Files.isSymbolicLink(legacyKeyFile) || !Files.isRegularFile(legacyKeyFile, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalStateException("The legacy Google credential key is unavailable.");
			}
			byte[] key = Crypt32Util.cryptUnprotectData(Files.readAllBytes(legacyKeyFile));
			if (key.length != 32) throw new IllegalStateException("The legacy Google credential key has an invalid length.");
			return key;
		} catch (IOException exception) {
			throw new IllegalStateException("The legacy Google credential key is unavailable.", exception);
		}
	}

	@Override public boolean isConfigured() {
		try { loadKey(); return true; }
		catch (RuntimeException error) { return false; }
	}

	@Override public boolean isLegacyKeyAvailable() {
		try { loadLegacyKey(); return true; }
		catch (RuntimeException error) { return false; }
	}

	private static Path legacyKeyPath() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path root = localAppData == null || localAppData.isBlank()
			? Path.of(System.getProperty("user.home"), "AppData", "Local")
			: Path.of(localAppData);
		return root.resolve("Hub").resolve("google-tasks.key.dpapi");
	}
}
