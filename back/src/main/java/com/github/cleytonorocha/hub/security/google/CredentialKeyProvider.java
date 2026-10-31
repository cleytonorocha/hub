package com.github.cleytonorocha.hub.security.google;

/** Supplies the installation key and, on Windows, the legacy DPAPI key used during migration. */
public interface CredentialKeyProvider {
	byte[] loadKey();
	byte[] loadLegacyKey();
	boolean isConfigured();
	boolean isLegacyKeyAvailable();
}
