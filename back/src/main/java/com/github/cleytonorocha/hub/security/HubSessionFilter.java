package com.github.cleytonorocha.hub.security;

import com.github.cleytonorocha.hub.entity.HubUser;
import com.github.cleytonorocha.hub.entity.PermissionProfile;
import com.github.cleytonorocha.hub.repository.HubUserRepository;
import com.github.cleytonorocha.hub.repository.PermissionProfileRepository;
import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

public class HubSessionFilter extends OncePerRequestFilter {
	private final HubUserRepository users;
	private final PermissionProfileRepository profiles;
	private final AuthOwnershipMigration ownershipMigration;
	public HubSessionFilter(HubUserRepository users, PermissionProfileRepository profiles, AuthOwnershipMigration ownershipMigration) { this.users = users; this.profiles = profiles; this.ownershipMigration = ownershipMigration; }
	@Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof HubPrincipal principal) {
			if (!ownershipMigration.isReady()) { response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE); return; }
			HubUser user = users.findById(principal.id()).orElse(null);
			PermissionProfile profile = user == null ? null : profiles.findById(user.getProfileId()).orElse(null);
			if (user == null || !user.isEnabled() || profile == null) {
				new SecurityContextLogoutHandler().logout(request, response, authentication);
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}
			HubPrincipal refreshed = new HubPrincipal(user.getId(), user.getDisplayName(), user.getEmail(), user.getPasswordHash(), true,
				profile.getId(), profile.getName(), profile.getPermissions());
			SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(refreshed, null, refreshed.getAuthorities()));
		}
		chain.doFilter(request, response);
	}
}
