package com.github.cleytonorocha.hub.security;

import com.github.cleytonorocha.hub.entity.HubUser;
import com.github.cleytonorocha.hub.entity.PermissionProfile;
import com.github.cleytonorocha.hub.repository.HubUserRepository;
import com.github.cleytonorocha.hub.repository.PermissionProfileRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class HubUserDetailsService implements UserDetailsService {
	private final HubUserRepository users;
	private final PermissionProfileRepository profiles;
	public HubUserDetailsService(HubUserRepository users, PermissionProfileRepository profiles) { this.users = users; this.profiles = profiles; }
	@Override public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		HubUser user = users.findByNormalizedEmail(email.strip().toLowerCase(java.util.Locale.ROOT))
			.orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas."));
		PermissionProfile profile = profiles.findById(user.getProfileId()).orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas."));
		return new HubPrincipal(user.getId(), user.getDisplayName(), user.getEmail(), user.getPasswordHash(), user.isEnabled(),
			profile.getId(), profile.getName(), profile.getPermissions());
	}
}
