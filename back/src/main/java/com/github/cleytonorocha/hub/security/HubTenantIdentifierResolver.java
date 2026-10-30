package com.github.cleytonorocha.hub.security;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class HubTenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {
	public static final String NO_TENANT = "__anonymous__";
	@Override public String resolveCurrentTenantIdentifier() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof HubPrincipal principal) return principal.id();
		return NO_TENANT;
	}
	@Override public boolean validateExistingCurrentSessions() { return true; }
}
