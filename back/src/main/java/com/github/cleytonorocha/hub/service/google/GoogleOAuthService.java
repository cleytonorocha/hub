package com.github.cleytonorocha.hub.service.google;

import com.github.cleytonorocha.hub.security.google.CredentialKeyProvider;
import com.github.cleytonorocha.hub.security.AuthContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class GoogleOAuthService {
	private static final String SCOPE = "https://www.googleapis.com/auth/tasks";
	private static final String KEY_VERSION = "INSTALLATION_V1";
	private static final Duration FLOW_TTL = Duration.ofMinutes(10);
	private static final SecureRandom RANDOM = new SecureRandom();
	private final JdbcTemplate jdbc;
	private final RestClient client;
	private final CredentialKeyProvider keys;
	private final String clientId;
	private final String clientSecretFile;
	private final String callbackUri;
	private final ConcurrentHashMap<String, Flow> flows = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CachedToken> tokens = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String,Object> tokenLocks = new ConcurrentHashMap<>();

	public GoogleOAuthService(JdbcTemplate jdbc, RestClient.Builder builder, CredentialKeyProvider keys,
			@Value("${hub.google.oauth.client-id:}") String clientId,
			@Value("${hub.google.oauth.client-secret-file:}") String clientSecretFile,
			@Value("${hub.google.oauth.callback-uri:http://localhost:8081/api/google-tasks/oauth/callback}") String callbackUri) {
		this.jdbc = jdbc; this.client = builder.build(); this.keys = keys;
		this.clientId = clientId == null ? "" : clientId.strip();
		this.clientSecretFile = clientSecretFile == null ? "" : clientSecretFile.strip();
		this.callbackUri = callbackUri;
	}

	public boolean configured() { return !clientId.isBlank() && !secret().isBlank() && keys.isConfigured(); }

	public ConnectionState status(String owner) {
		if (!configured()) return new ConnectionState("CONFIGURATION_REQUIRED", false, null);
		String id = existingConnectionId(owner);
		if (id == null) return new ConnectionState("NOT_CONNECTED", true, null);
		return jdbc.query("SELECT status,last_synced_at FROM google_connection WHERE owner_id=? AND connection_id=?",
			rs -> { if (!rs.next()) return new ConnectionState("NOT_CONNECTED", true, null);
				var ts = rs.getTimestamp(2); return new ConnectionState(rs.getString(1), true, ts == null ? null : ts.toInstant()); }, owner, id);
	}

	public String startAuthorization(String owner, String sessionId) {
		if (!configured()) throw new OAuthConfigurationException("Google OAuth client, segredo e chave de instalação são obrigatórios.");
		flows.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
		String connection = ensureConnection(owner);
		String priorStatus=currentStatus(owner,connection);
		String state = random(32), verifier = random(64);
		try {
			String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
			flows.put(state, new Flow(owner, sessionId, connection, verifier, priorStatus, Instant.now().plus(FLOW_TTL)));
			setStatus(owner,connection,"CONNECTING");
			return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
				.queryParam("client_id", clientId).queryParam("redirect_uri", callbackUri).queryParam("response_type", "code")
				.queryParam("scope", SCOPE).queryParam("access_type", "offline").queryParam("prompt", "consent")
				.queryParam("state", state).queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256")
				.build().encode().toUriString();
		} catch (Exception e) { flows.remove(state); throw new OAuthConfigurationException("Não foi possível iniciar OAuth Google."); }
	}

	public boolean completeCallback(String owner, String sessionId, String state, String code, String error) {
		Flow flow = state == null ? null : flows.remove(state); // one use, including failed attempts
		if (flow == null) return false;
		if (!flow.owner().equals(owner) || !flow.sessionId().equals(sessionId)) { setStatus(flow.owner(),flow.connectionId(),flow.priorStatus()); return false; }
		if(flow.expiresAt().isBefore(Instant.now())||error!=null||code==null||code.isBlank()){setStatus(owner,flow.connectionId(),flow.priorStatus());return false;}
		try {
			MultiValueMap<String,String> form = tokenForm();
			form.add("code", code); form.add("code_verifier", flow.verifier()); form.add("grant_type", "authorization_code"); form.add("redirect_uri", callbackUri);
			Map<?,?> response = client.post().uri("https://oauth2.googleapis.com/token").body(form).retrieve().body(Map.class);
			String refresh = string(response,"refresh_token");
			if (refresh == null) refresh = storedRefreshToken(owner, flow.connectionId());
			if (refresh == null) throw new IllegalStateException("Google did not return a refresh token.");
			storeToken(owner, flow.connectionId(), refresh);
			setStatus(owner, flow.connectionId(), "CONNECTED"); tokens.remove(owner); return true;
		} catch (RuntimeException ex) { setStatus(owner, flow.connectionId(), flow.priorStatus()); return false; }
	}

	public String accessToken(String owner) { synchronized(tokenLocks.computeIfAbsent(owner,k->new Object())) { return refreshAccessToken(owner); } }
	private String refreshAccessToken(String owner) {
		CachedToken cached = tokens.get(owner);
		if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) return cached.value();
		String connection = existingConnectionId(owner);
		if (connection == null) throw new GoogleAuthorizationRequiredException("Google Tasks is not connected.");
		String refresh;
		try { refresh = storedRefreshToken(owner, connection); }
		catch (RuntimeException ex) { setStatus(owner, connection, "REAUTH_REQUIRED"); throw new GoogleAuthorizationRequiredException("A credencial Google precisa ser reconectada."); }
		if (refresh == null) throw new GoogleAuthorizationRequiredException("Google Tasks is not connected.");
		try {
			MultiValueMap<String,String> form = tokenForm(); form.add("grant_type","refresh_token"); form.add("refresh_token",refresh);
			Map<?,?> response = client.post().uri("https://oauth2.googleapis.com/token").body(form).retrieve().body(Map.class);
			String access = string(response,"access_token"); if (access == null) throw new IllegalStateException();
			String rotated = string(response,"refresh_token"); if (rotated != null) storeToken(owner, connection, rotated);
			tokens.put(owner,new CachedToken(access,Instant.now().plusSeconds(number(response,"expires_in",3600)))); return access;
		} catch (ResourceAccessException ex) { throw new GoogleOAuthUnavailableException("Google não está acessível agora."); }
		catch (RestClientResponseException ex) { if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) { setStatus(owner,connection,"REAUTH_REQUIRED"); throw new GoogleAuthorizationRequiredException("A credencial Google precisa ser reconectada."); } throw new GoogleOAuthUnavailableException("Não foi possível atualizar a autorização Google."); }
	}

	public void markReauthorizationRequired(String owner) { synchronized(tokenLocks.computeIfAbsent(owner,k->new Object())) { String id=existingConnectionId(owner); if(id!=null) setStatus(owner,id,"REAUTH_REQUIRED"); tokens.remove(owner); } }
	public void markSynchronized(String owner) { String id=existingConnectionId(owner); if(id!=null) jdbc.update("UPDATE google_connection SET last_synced_at=?,updated_at=? WHERE owner_id=? AND connection_id=?", java.sql.Timestamp.from(Instant.now()),java.sql.Timestamp.from(Instant.now()),owner,id); }
	public void disconnect(String owner) {
		synchronized(tokenLocks.computeIfAbsent(owner,k->new Object())) { String id=existingConnectionId(owner); tokens.remove(owner); flows.entrySet().removeIf(e -> e.getValue().owner().equals(owner));
		if(id==null) return;
		jdbc.update("UPDATE google_connection SET refresh_token_ciphertext=NULL,refresh_token_nonce=NULL,scopes=NULL,status='NOT_CONNECTED',updated_at=? WHERE owner_id=? AND connection_id=?",java.sql.Timestamp.from(Instant.now()),owner,id);
		jdbc.update("UPDATE hub_task_notification SET state='CANCELLED' WHERE owner_id=? AND connection_id=? AND state='PENDING'",owner,id);
		}
	}
	public void clearSession(String owner) { synchronized(tokenLocks.computeIfAbsent(owner,k->new Object())) { tokens.remove(owner); flows.entrySet().removeIf(e -> e.getValue().owner().equals(owner)); } }
	public String connectionId(String owner) { return ensureConnection(owner); }

	private String ensureConnection(String owner) {
		String existing=existingConnectionId(owner); if(existing!=null) return existing;
		boolean administrator=false; try { administrator=owner.equals(AuthContext.userId())&&AuthContext.principal().permissionSet().contains("USER_MANAGE"); } catch(RuntimeException ignored) { }
		boolean primaryAvailable=jdbc.query("SELECT COUNT(*) FROM google_connection WHERE connection_id='primary'",rs->rs.next()&&rs.getLong(1)==0);
		String id = administrator&&primaryAvailable ? "primary" : owner;
		jdbc.update("INSERT INTO google_connection(connection_id,owner_id,status,updated_at) VALUES(?,?,?,?)",id,owner,"NOT_CONNECTED",java.sql.Timestamp.from(Instant.now())); return id;
	}
	private String existingConnectionId(String owner) { return jdbc.query("SELECT connection_id FROM google_connection WHERE owner_id=?",rs -> rs.next()?rs.getString(1):null,owner); }
	private String currentStatus(String owner,String id){return jdbc.query("SELECT status FROM google_connection WHERE owner_id=? AND connection_id=?",rs->rs.next()?rs.getString(1):"NOT_CONNECTED",owner,id);}
	private void setStatus(String owner,String id,String status) { if(id!=null) jdbc.update("UPDATE google_connection SET status=?,updated_at=? WHERE owner_id=? AND connection_id=?",status,java.sql.Timestamp.from(Instant.now()),owner,id); }
	private MultiValueMap<String,String> tokenForm() { LinkedMultiValueMap<String,String> f=new LinkedMultiValueMap<>(); f.add("client_id",clientId); f.add("client_secret",secret()); return f; }
	private String secret() { try { Path p=Path.of(clientSecretFile).toAbsolutePath().normalize(); if(Files.isSymbolicLink(p)||!Files.isRegularFile(p)) return ""; return Files.readString(p,StandardCharsets.UTF_8).strip(); } catch(Exception e) { return ""; } }
	private String storedRefreshToken(String owner,String connection) {
		List<EncryptedToken> rows=jdbc.query("SELECT refresh_token_ciphertext,refresh_token_nonce,credential_key_version FROM google_connection WHERE owner_id=? AND connection_id=?",(rs,n)->new EncryptedToken(rs.getString(1),rs.getString(2),rs.getString(3)),owner,connection);
		if(rows.isEmpty()||rows.getFirst().ciphertext()==null)return null;
		EncryptedToken row=rows.getFirst(); String version=row.version(); if(version!=null&&!KEY_VERSION.equals(version))throw new IllegalStateException("Unsupported Google credential key version.");
		String value=decrypt(row.ciphertext(),row.nonce(),version==null?keys.loadLegacyKey():keys.loadKey(),version==null?null:aad(owner,connection));
		if(version==null)storeToken(owner,connection,value); return value;
	}
	private void storeToken(String owner,String connection,String value) {
		byte[] nonce=new byte[12]; RANDOM.nextBytes(nonce); String cipher=Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE,value,nonce,keys.loadKey(),aad(owner,connection)));
		jdbc.update("UPDATE google_connection SET refresh_token_ciphertext=?,refresh_token_nonce=?,credential_key_version=?,scopes=?,status='CONNECTED',updated_at=? WHERE owner_id=? AND connection_id=?",cipher,Base64.getEncoder().encodeToString(nonce),KEY_VERSION,SCOPE,java.sql.Timestamp.from(Instant.now()),owner,connection);
	}
	private byte[] aad(String owner,String connection) { return (owner+"\0"+connection).getBytes(StandardCharsets.UTF_8); }
	private byte[] crypt(int mode,String value,byte[] nonce,byte[] key,byte[] aad) { try { Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce)); if(aad!=null)c.updateAAD(aad); return mode==Cipher.ENCRYPT_MODE?c.doFinal(value.getBytes(StandardCharsets.UTF_8)):c.doFinal(Base64.getDecoder().decode(value)); } catch(Exception e){throw new IllegalStateException("Google credential cannot be decrypted with the available installation key.");} }
	private String decrypt(String value,String nonce,byte[] key,byte[] aad) { return new String(crypt(Cipher.DECRYPT_MODE,value,Base64.getDecoder().decode(nonce),key,aad),StandardCharsets.UTF_8); }
	private String random(int n) { byte[] b=new byte[n]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
	private String string(Map<?,?> m,String k) { Object v=m==null?null:m.get(k); return v instanceof String s?s:null; }
	private long number(Map<?,?> m,String k,long d) { Object v=m==null?null:m.get(k); return v instanceof Number n?Math.max(60,n.longValue()):d; }
	public record ConnectionState(String status,boolean configured,Instant lastSyncedAt) {}
	private record Flow(String owner,String sessionId,String connectionId,String verifier,String priorStatus,Instant expiresAt) {}
	private record EncryptedToken(String ciphertext,String nonce,String version) {}
	private record CachedToken(String value,Instant expiresAt) {}
	public static class OAuthConfigurationException extends RuntimeException { public OAuthConfigurationException(String m){super(m);} }
	public static class GoogleAuthorizationRequiredException extends RuntimeException { public GoogleAuthorizationRequiredException(String m){super(m);} }
	public static class GoogleOAuthUnavailableException extends RuntimeException { public GoogleOAuthUnavailableException(String m){super(m);} }
}
