package com.github.cleytonorocha.hub.security.google;

import com.sun.jna.platform.win32.Crypt32Util;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;

@Component
public final class LocalUserCredentialKeyProvider implements CredentialKeyProvider {
	private static final SecureRandom RANDOM = new SecureRandom();
	private final Path keyFile = keyPath();

	@Override
	public synchronized byte[] loadKey() {
		try {
			byte[] protectedKey = Files.readAllBytes(keyFile);
			byte[] key = Crypt32Util.cryptUnprotectData(protectedKey);
			if (key.length != 32) throw new IllegalStateException("Stored Google credential key has invalid length.");
			return key;
		} catch (IOException exception) {
			throw new IllegalStateException("Google credential key is unavailable; reconnect the Google account.", exception);
		}
	}

	@Override
	public synchronized byte[] createKey() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
			throw new IllegalStateException("Google Tasks connection requires Windows DPAPI CurrentUser.");
		}
		byte[] key = new byte[32];
		RANDOM.nextBytes(key);
		byte[] protectedKey = Crypt32Util.cryptProtectData(key);
		try {
			Files.createDirectories(keyFile.getParent());
			Path temporaryFile = Files.createTempFile(keyFile.getParent(), "google-tasks-key-", ".tmp");
			try {
				Files.write(temporaryFile, protectedKey);
				Files.move(temporaryFile, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.deleteIfExists(temporaryFile);
				throw new IllegalStateException("Atomic credential-key storage is unavailable.", exception);
			} catch (IOException exception) {
				Files.deleteIfExists(temporaryFile);
				throw exception;
			}
			return key;
		} catch (IOException exception) {
			throw new IllegalStateException("Could not store the user-protected Google credential key.", exception);
		}
	}

	private Path keyPath() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path root = localAppData == null || localAppData.isBlank()
			? Path.of(System.getProperty("user.home"), "AppData", "Local")
			: Path.of(localAppData);
		return root.resolve("Hub").resolve("google-tasks.key.dpapi");
	}
}
