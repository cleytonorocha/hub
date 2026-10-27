package com.github.cleytonorocha.hub.security.google;

/** Supplies the user-scoped encryption key used for the persisted Google refresh token. */
public interface CredentialKeyProvider {
	byte[] loadKey();
	byte[] createKey();
}
