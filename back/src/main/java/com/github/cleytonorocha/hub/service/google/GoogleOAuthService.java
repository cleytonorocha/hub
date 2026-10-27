package com.github.cleytonorocha.hub.service.google;

import com.github.cleytonorocha.hub.security.google.CredentialKeyProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public final class GoogleOAuthService {
	private static final String CONNECTION_ID = "primary";
	private static final String TASKS_SCOPE = "https://www.googleapis.com/auth/tasks";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final JdbcTemplate jdbc;
	private final RestClient client;
	private final CredentialKeyProvider keyProvider;
	private final String clientId;
	private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "google-tasks-oauth-callback");
		thread.setDaemon(true);
		return thread;
	});
	private volatile boolean flowInProgress;
	private volatile String cachedAccessToken;
	private volatile Instant accessTokenExpiresAt = Instant.EPOCH;

	public GoogleOAuthService(JdbcTemplate jdbc, RestClient.Builder builder, CredentialKeyProvider keyProvider,
			@Value("${hub.google.oauth.client-id:}") String clientId) {
		this.jdbc = jdbc;
		this.client = builder.build();
		this.keyProvider = keyProvider;
		this.clientId = clientId == null ? "" : clientId.trim();
	}

	public synchronized String startAuthorization() {
		if (clientId.isBlank()) throw new OAuthConfigurationException("Google OAuth client ID is not configured.");
		if (flowInProgress) throw new OAuthConfigurationException("Google authorization is already in progress.");
		try {
			String previousStatus = status().status();
			ServerSocket callback = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
			callback.setSoTimeout(240_000);
			String redirectUri = "http://127.0.0.1:" + callback.getLocalPort() + "/";
			String state = randomUrlSafe(32);
			String verifier = randomUrlSafe(64);
			String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
			URI authorizationUri = UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
				.queryParam("client_id", clientId)
				.queryParam("redirect_uri", redirectUri)
				.queryParam("response_type", "code")
				.queryParam("scope", TASKS_SCOPE)
				.queryParam("access_type", "offline")
				.queryParam("prompt", "consent")
				.queryParam("state", state)
				.queryParam("code_challenge", challenge)
				.queryParam("code_challenge_method", "S256")
				.build().encode().toUri();
			flowInProgress = true;
			setStatus("CONNECTING");
			callbackExecutor.execute(() -> awaitCallback(callback, redirectUri, state, verifier, previousStatus));
			return authorizationUri.toString();
		} catch (Exception exception) {
			flowInProgress = false;
			throw new OAuthConfigurationException("Could not start local Google authorization.");
		}
	}

	public ConnectionState status() {
		if (clientId.isBlank()) return new ConnectionState("CONFIGURATION_REQUIRED", false, null);
		return jdbc.query("SELECT status, last_synced_at FROM google_connection WHERE connection_id = ?", rs -> {
			if (!rs.next()) return new ConnectionState("NOT_CONNECTED", true, null);
			var lastSynced = rs.getTimestamp("last_synced_at");
			return new ConnectionState(rs.getString("status"), !clientId.isBlank(), lastSynced == null ? null : lastSynced.toInstant());
		}, CONNECTION_ID);
	}

	public synchronized String accessToken() {
		if (cachedAccessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minusSeconds(30))) return cachedAccessToken;
		String refreshToken;
		try {
			var encrypted = jdbc.query("SELECT refresh_token_ciphertext, refresh_token_nonce FROM google_connection WHERE connection_id = ? AND status = 'CONNECTED'",
				(rs, row) -> new EncryptedToken(rs.getString("refresh_token_ciphertext"), rs.getString("refresh_token_nonce")), CONNECTION_ID)
				.stream().findFirst().orElseThrow(() -> new GoogleAuthorizationRequiredException("Google Tasks is not connected."));
			refreshToken = decrypt(encrypted.ciphertext(), encrypted.nonce(), keyProvider.loadKey());
		} catch (Exception exception) {
			markReauthorizationRequired();
			throw new GoogleAuthorizationRequiredException("Google authorization needs renewal.");
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", clientId);
		form.add("grant_type", "refresh_token");
		form.add("refresh_token", refreshToken);
		Map<?, ?> response;
		try {
			response = client.post().uri("https://oauth2.googleapis.com/token").body(form).retrieve().body(Map.class);
		} catch (ResourceAccessException exception) {
			throw new GoogleOAuthUnavailableException("Google could not be reached. Try again when the connection is available.");
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 400 || exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
				markReauthorizationRequired();
				throw new GoogleAuthorizationRequiredException("Google authorization needs renewal.");
			}
			throw new GoogleOAuthUnavailableException("Google authorization could not be refreshed. Try again later.");
		}
		String accessToken = value(response, "access_token");
		if (accessToken == null) {
			markReauthorizationRequired();
			throw new GoogleAuthorizationRequiredException("Google authorization needs renewal.");
		}
		cachedAccessToken = accessToken;
		long expiresIn = number(response, "expires_in", 3600);
		accessTokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn));
		String rotatedRefreshToken = value(response, "refresh_token");
		if (rotatedRefreshToken != null) {
			try { storeEncryptedRefreshToken(rotatedRefreshToken, keyProvider.loadKey()); }
			catch (RuntimeException exception) {
				markReauthorizationRequired();
				throw new GoogleAuthorizationRequiredException("Google authorization needs renewal.");
			}
		}
		return accessToken;
	}

	public void markReauthorizationRequired() {
		cachedAccessToken = null;
		accessTokenExpiresAt = Instant.EPOCH;
		setStatus("REAUTH_REQUIRED");
	}

	public void markSynchronized() {
		jdbc.update("UPDATE google_connection SET last_synced_at = ?, updated_at = ? WHERE connection_id = ?",
			java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()), CONNECTION_ID);
	}

	private void awaitCallback(ServerSocket callback, String redirectUri, String state, String verifier, String previousStatus) {
		try (callback; Socket socket = callback.accept()) {
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
				String requestLine = reader.readLine();
				String header;
				while ((header = reader.readLine()) != null && !header.isEmpty()) { }
				if (requestLine == null) throw new IllegalArgumentException("Missing OAuth callback request.");
				String[] request = requestLine.split(" ");
				if (request.length < 2 || !request[1].startsWith("/?")) throw new IllegalArgumentException("Invalid OAuth callback.");
				Map<String, String> query = parseQuery(URI.create(request[1]).getRawQuery());
				if (!state.equals(query.get("state"))) throw new IllegalArgumentException("OAuth state did not match.");
				if (query.containsKey("error")) throw new IllegalArgumentException("Google authorization was declined.");
				String code = query.get("code");
				if (code == null || code.isBlank()) throw new IllegalArgumentException("Google did not return an authorization code.");
				exchangeAuthorizationCode(code, redirectUri, verifier);
				writeCallbackResponse(socket.getOutputStream(), true);
			} catch (Exception exception) {
				setStatus(previousStatus.equals("CONNECTED") ? "CONNECTED" : "NOT_CONNECTED");
				writeCallbackResponse(socket.getOutputStream(), false);
			}
		} catch (Exception ignored) {
			setStatus(previousStatus.equals("CONNECTED") ? "CONNECTED" : "NOT_CONNECTED");
		} finally {
			flowInProgress = false;
		}
	}

	private void exchangeAuthorizationCode(String code, String redirectUri, String verifier) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", clientId);
		form.add("code", code);
		form.add("code_verifier", verifier);
		form.add("grant_type", "authorization_code");
		form.add("redirect_uri", redirectUri);
		Map<?, ?> response = client.post().uri("https://oauth2.googleapis.com/token").body(form).retrieve().body(Map.class);
		String refreshToken = value(response, "refresh_token");
		byte[] key;
		if (refreshToken == null) {
			var encrypted = jdbc.query("SELECT refresh_token_ciphertext, refresh_token_nonce FROM google_connection WHERE connection_id = ?",
				(rs, row) -> new EncryptedToken(rs.getString(1), rs.getString(2)), CONNECTION_ID).stream().findFirst()
				.orElseThrow(() -> new GoogleAuthorizationRequiredException("Google did not return a refresh token; reconnect and approve access."));
			key = keyProvider.loadKey();
			refreshToken = decrypt(encrypted.ciphertext(), encrypted.nonce(), key);
		} else {
			try { key = keyProvider.loadKey(); }
			catch (RuntimeException missingKey) { key = keyProvider.createKey(); }
		}
		storeEncryptedRefreshToken(refreshToken, key);
		setStatus("CONNECTED");
		cachedAccessToken = null;
		accessTokenExpiresAt = Instant.EPOCH;
	}

	private void storeEncryptedRefreshToken(String refreshToken, byte[] key) {
		byte[] nonce = new byte[12];
		RANDOM.nextBytes(nonce);
		String encrypted = Base64.getEncoder().encodeToString(encrypt(refreshToken, nonce, key));
		String encodedNonce = Base64.getEncoder().encodeToString(nonce);
		int updated = jdbc.update("UPDATE google_connection SET refresh_token_ciphertext = ?, refresh_token_nonce = ?, scopes = ?, status = 'CONNECTED', updated_at = ? WHERE connection_id = ?",
			encrypted, encodedNonce, TASKS_SCOPE, java.sql.Timestamp.from(Instant.now()), CONNECTION_ID);
		if (updated == 0) jdbc.update("INSERT INTO google_connection (connection_id, refresh_token_ciphertext, refresh_token_nonce, scopes, status, updated_at) VALUES (?, ?, ?, ?, 'CONNECTED', ?)",
			CONNECTION_ID, encrypted, encodedNonce, TASKS_SCOPE, java.sql.Timestamp.from(Instant.now()));
	}

	private byte[] encrypt(String token, byte[] nonce, byte[] key) {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			return cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
		} catch (Exception exception) { throw new IllegalStateException("Could not protect Google refresh token."); }
	}

	private String decrypt(String encrypted, String encodedNonce, byte[] key) {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(encodedNonce)));
			return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
		} catch (Exception exception) { throw new IllegalStateException("Google credential key is unavailable; reconnect the Google account."); }
	}

	private void setStatus(String status) {
		java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
		int updated = jdbc.update("UPDATE google_connection SET status = ?, updated_at = ? WHERE connection_id = ?", status, now, CONNECTION_ID);
		if (updated == 0) jdbc.update("INSERT INTO google_connection (connection_id, status, updated_at) VALUES (?, ?, ?)", CONNECTION_ID, status, now);
	}

	private Map<String, String> parseQuery(String query) {
		Map<String, String> values = new HashMap<>();
		if (query == null) return values;
		for (String pair : query.split("&")) {
			int separator = pair.indexOf('=');
			String key = separator < 0 ? pair : pair.substring(0, separator);
			String value = separator < 0 ? "" : pair.substring(separator + 1);
			values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
		}
		return values;
	}

	private void writeCallbackResponse(OutputStream output, boolean success) throws Exception {
		String body = success
			? "<main><h1>Google Tasks conectado</h1><p>Retorne ao Hub para continuar.</p></main>"
			: "<main><h1>Não foi possível conectar</h1><p>Retorne ao Hub e tente novamente.</p></main>";
		byte[] bytes = ("<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>Hub</title><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
		String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n";
		output.write(headers.getBytes(StandardCharsets.US_ASCII));
		output.write(bytes);
		output.flush();
	}

	private String randomUrlSafe(int size) {
		byte[] bytes = new byte[size];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String value(Map<?, ?> values, String key) {
		Object value = values == null ? null : values.get(key);
		return value instanceof String text ? text : null;
	}

	private long number(Map<?, ?> values, String key, long fallback) {
		Object value = values == null ? null : values.get(key);
		return value instanceof Number number ? number.longValue() : fallback;
	}

	public record ConnectionState(String status, boolean configured, Instant lastSyncedAt) {}
	private record EncryptedToken(String ciphertext, String nonce) {}

	public static final class OAuthConfigurationException extends RuntimeException {
		public OAuthConfigurationException(String message) { super(message); }
	}
	public static final class GoogleAuthorizationRequiredException extends RuntimeException {
		public GoogleAuthorizationRequiredException(String message) { super(message); }
	}
	public static final class GoogleOAuthUnavailableException extends RuntimeException {
		public GoogleOAuthUnavailableException(String message) { super(message); }
	}
}
