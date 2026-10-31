package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.security.AuthContext;
import com.github.cleytonorocha.hub.service.UserPreferenceService;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/user-preferences")
@PreAuthorize("isAuthenticated()")
public class UserPreferenceController {
	private final UserPreferenceService preferences;
	private final ObjectMapper mapper;

	public UserPreferenceController(UserPreferenceService preferences, ObjectMapper mapper) {
		this.preferences = preferences;
		this.mapper = mapper;
	}

	@GetMapping
	public ResponseEntity<UserPreferenceService.PreferenceSnapshot> get() {
		try { return response(preferences.get(AuthContext.userId())); }
		catch (UserPreferenceService.PreferenceIntegrityException error) { throw integrityError(); }
	}

	@PostMapping("/initialize")
	public ResponseEntity<UserPreferenceService.PreferenceSnapshot> initialize(@RequestBody byte[] body) {
		Map<String, Object> input = parse(body);
		try { return response(preferences.initialize(AuthContext.userId(), input)); }
		catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
		catch (UserPreferenceService.PreferenceIntegrityException error) { throw integrityError(); }
	}

	@PutMapping
	public ResponseEntity<UserPreferenceService.PreferenceSnapshot> save(
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestBody byte[] body) {
		long expectedRevision = parseEtag(ifMatch);
		Map<String, Object> input = parse(body);
		try {
			UserPreferenceService.SaveResult result = preferences.save(AuthContext.userId(), expectedRevision, input);
			return response(result.snapshot(), result.updated() ? HttpStatus.OK : HttpStatus.CONFLICT);
		} catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
		catch (UserPreferenceService.PreferenceIntegrityException error) { throw integrityError(); }
	}

	private Map<String, Object> parse(byte[] body) {
		if (body == null || body.length == 0 || body.length > UserPreferenceService.MAX_DOCUMENT_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O documento de preferências excede 16 KiB ou está vazio.");
		}
		try { return mapper.readValue(body, new TypeReference<Map<String, Object>>() {}); }
		catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O documento de preferências não é um JSON válido."); }
	}

	private long parseEtag(String value) {
		if (value == null || !value.matches("\\\"prefs-[0-9]+\\\"")) {
			throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "Envie o ETag atual em If-Match para salvar preferências.");
		}
		try { return Long.parseLong(value.substring(7, value.length() - 1)); }
		catch (NumberFormatException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O ETag de preferências é inválido."); }
	}

	private ResponseEntity<UserPreferenceService.PreferenceSnapshot> response(UserPreferenceService.PreferenceSnapshot snapshot) {
		return response(snapshot, HttpStatus.OK);
	}
	private ResponseEntity<UserPreferenceService.PreferenceSnapshot> response(UserPreferenceService.PreferenceSnapshot snapshot, HttpStatus status) {
		return ResponseEntity.status(status).eTag("\"prefs-" + snapshot.revision() + "\"").body(snapshot);
	}
	private ResponseStatusException integrityError() {
		return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "As preferências armazenadas estão corrompidas e foram preservadas para revisão operacional.");
	}
}
