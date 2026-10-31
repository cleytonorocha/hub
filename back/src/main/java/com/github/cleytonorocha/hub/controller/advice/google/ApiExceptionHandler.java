package com.github.cleytonorocha.hub.controller.advice.google;

import com.github.cleytonorocha.hub.service.google.GoogleOAuthService;
import com.github.cleytonorocha.hub.service.google.GoogleOAuthService.GoogleAuthorizationRequiredException;
import com.github.cleytonorocha.hub.service.google.GoogleOAuthService.GoogleOAuthUnavailableException;
import com.github.cleytonorocha.hub.service.google.GoogleOAuthService.OAuthConfigurationException;
import com.github.cleytonorocha.hub.service.google.GoogleTasksService.GoogleSyncException;
import com.github.cleytonorocha.hub.service.google.GoogleTasksService.TaskConflictException;
import com.github.cleytonorocha.hub.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.HashMap;

@RestControllerAdvice
public final class ApiExceptionHandler {
	private final GoogleOAuthService oauth;

	public ApiExceptionHandler(GoogleOAuthService oauth) { this.oauth = oauth; }

	@ExceptionHandler(OAuthConfigurationException.class)
	public ResponseEntity<Map<String, String>> oauthConfiguration(OAuthConfigurationException exception) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler(GoogleAuthorizationRequiredException.class)
	public ResponseEntity<Map<String, String>> authorizationRequired(GoogleAuthorizationRequiredException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler(TaskConflictException.class)
	public ResponseEntity<Map<String, Object>> conflict(TaskConflictException exception) {
		Map<String, Object> body = new HashMap<>();
		body.put("message", exception.currentTask() == null ? "A Task foi removida no Google; não há alteração para reaplicar." : "A Task mudou no Google. Revise os dados atuais antes de reaplicar.");
		body.put("currentTask", exception.currentTask());
		return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
	}

	@ExceptionHandler(GoogleSyncException.class)
	public ResponseEntity<Map<String, String>> syncFailure(GoogleSyncException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler(GoogleOAuthUnavailableException.class)
	public ResponseEntity<Map<String, String>> oauthUnavailable(GoogleOAuthUnavailableException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler(RestClientResponseException.class)
	public ResponseEntity<Map<String, String>> googleError(RestClientResponseException exception) {
		if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) oauth.markReauthorizationRequired(AuthContext.userId());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "Google Tasks não respondeu à solicitação. Atualize a conexão e tente novamente."));
	}

	@ExceptionHandler(RestClientException.class)
	public ResponseEntity<Map<String, String>> googleUnavailable(RestClientException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "Google Tasks está indisponível. Tente atualizar novamente."));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> localCredentialUnavailable(IllegalStateException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "A chave local do Google não está disponível. Reconecte a conta Google."));
	}
}
