package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.security.HubPrincipal;
import com.github.cleytonorocha.hub.service.AuthService;
import com.github.cleytonorocha.hub.security.LoginAttemptLimiter;
import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
	private final AuthService auth;
	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository contexts;
	private final LoginAttemptLimiter limiter;
	private final AuthOwnershipMigration ownershipMigration;
	public AuthController(AuthService auth, AuthenticationManager authenticationManager, SecurityContextRepository contexts,
			LoginAttemptLimiter limiter, AuthOwnershipMigration ownershipMigration) {
		this.auth = auth; this.authenticationManager = authenticationManager; this.contexts = contexts; this.limiter = limiter; this.ownershipMigration = ownershipMigration;
	}

	@GetMapping("/csrf") public CsrfView csrf(CsrfToken token) { return new CsrfView(token.getHeaderName(), token.getToken()); }
	@GetMapping("/setup-state") public SetupState setupState() { return new SetupState(auth.setupRequired()); }
	@PostMapping("/setup") public AuthService.SetupResult setup(@Valid @RequestBody AuthService.SetupRequest request, HttpServletRequest servletRequest) {
		requireLoopbackOrigin(servletRequest);
		return auth.setup(request);
	}
	@PostMapping("/login") public AuthService.SessionView login(@Valid @RequestBody LoginRequest request,
			HttpServletRequest servletRequest, HttpServletResponse response) {
		ownershipMigration.requireReady();
		String key = servletRequest.getRemoteAddr() + ":" + request.email().strip().toLowerCase(java.util.Locale.ROOT);
		if (limiter.blocked(key)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde 15 minutos antes de tentar novamente.");
		try {
			Authentication result = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.email().strip(), request.password()));
			if (servletRequest.getSession(false) != null) servletRequest.changeSessionId();
			SecurityContext context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(result);
			SecurityContextHolder.setContext(context); contexts.saveContext(context, servletRequest, response);
			limiter.succeeded(key);
			return auth.current((HubPrincipal) result.getPrincipal());
		} catch (RuntimeException error) {
			if (error instanceof ResponseStatusException status) throw status;
			limiter.failed(key);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos.");
		}
	}
	@GetMapping("/session") public AuthService.SessionView session(Authentication authentication) {
		return auth.current((HubPrincipal) authentication.getPrincipal());
	}
	@PostMapping("/logout") public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		new SecurityContextLogoutHandler().logout(request, response, authentication);
	}
	private static void requireLoopbackOrigin(HttpServletRequest request) {
		String origin = request.getHeader("Origin");
		String host = request.getHeader("Host");
		try {
			URI uri = origin == null ? null : URI.create(origin);
			boolean validOrigin = uri != null && uri.isAbsolute() && !uri.isOpaque()
				&& ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
				&& uri.getRawUserInfo() == null && empty(uri.getRawPath())
				&& uri.getRawQuery() == null && uri.getRawFragment() == null
				&& validPort(uri.getPort()) && isLoopback(uri.getHost());
			if (!validOrigin || !isLoopbackAuthority(host)) throw new IllegalArgumentException();
		} catch (RuntimeException error) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A configuração inicial só pode ser feita pela máquina local.");
		}
	}
	private static boolean isLoopbackAuthority(String authority) {
		if (authority == null || authority.isBlank()) return false;
		URI parsed = URI.create("http://" + authority.strip());
		return parsed.getRawUserInfo() == null && empty(parsed.getRawPath())
			&& parsed.getRawQuery() == null && parsed.getRawFragment() == null
			&& validPort(parsed.getPort()) && isLoopback(parsed.getHost());
	}
	private static boolean isLoopback(String host) {
		if (host == null) return false;
		String normalized = host.replace("[", "").replace("]", "").toLowerCase(java.util.Locale.ROOT);
		return java.util.Set.of("localhost", "127.0.0.1", "::1").contains(normalized);
	}
	private static boolean validPort(int port) { return port == -1 || (port >= 0 && port <= 65535); }
	private static boolean empty(String value) { return value == null || value.isEmpty(); }
	public record LoginRequest(@jakarta.validation.constraints.NotBlank String email, @jakarta.validation.constraints.NotBlank String password) {}
	public record SetupState(boolean setupRequired) {}
	public record CsrfView(String headerName, String token) {}
}
