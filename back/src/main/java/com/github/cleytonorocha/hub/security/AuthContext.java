package com.github.cleytonorocha.hub.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public final class AuthContext {
	private AuthContext() {}
	public static String userId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof HubPrincipal principal) return principal.id();
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entre na sua conta para continuar.");
	}
	public static HubPrincipal principal() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof HubPrincipal principal) return principal;
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entre na sua conta para continuar.");
	}
	public static boolean hasPermission(String permission) { return principal().permissionSet().contains(permission); }
}
