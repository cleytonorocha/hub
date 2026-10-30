package com.github.cleytonorocha.hub.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record HubPrincipal(String id, String displayName, String email, String password, boolean enabled,
		String profileId, String profileName, String permissions) implements UserDetails {
	@Override public Collection<? extends GrantedAuthority> getAuthorities() {
		return Arrays.stream(permissions.split(",")).filter(HubPermission.ALL::contains).map(SimpleGrantedAuthority::new).collect(Collectors.toUnmodifiableSet());
	}
	@Override public String getPassword() { return password; }
	@Override public String getUsername() { return email; }
	@Override public boolean isAccountNonExpired() { return true; }
	@Override public boolean isAccountNonLocked() { return enabled; }
	@Override public boolean isCredentialsNonExpired() { return true; }
	@Override public boolean isEnabled() { return enabled; }
	public Set<String> permissionSet() { return Arrays.stream(permissions.split(",")).collect(Collectors.toUnmodifiableSet()); }
}
