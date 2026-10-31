package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.config.LocalApiSecurityConfiguration;
import com.github.cleytonorocha.hub.controller.UserPreferenceController;
import com.github.cleytonorocha.hub.repository.HubUserRepository;
import com.github.cleytonorocha.hub.repository.PermissionProfileRepository;
import com.github.cleytonorocha.hub.security.HubUserDetailsService;
import com.github.cleytonorocha.hub.security.LoginAttemptLimiter;
import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import com.github.cleytonorocha.hub.service.UserPreferenceService;
import com.github.cleytonorocha.hub.service.google.GoogleOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit negative boundary: authenticated preference tests use migrated-owner fixtures elsewhere. */
@WebMvcTest(controllers = UserPreferenceController.class)
@Import(LocalApiSecurityConfiguration.class)
class HubSessionMigrationBoundaryTests {
	@Autowired MockMvc mvc;
	@MockitoBean AuthOwnershipMigration migration;
	@MockitoBean HubUserDetailsService userDetails;
	@MockitoBean HubUserRepository users;
	@MockitoBean PermissionProfileRepository profiles;
	@MockitoBean LoginAttemptLimiter limiter;
	@MockitoBean UserPreferenceService preferences;
	@MockitoBean GoogleOAuthService googleOAuth;

	@Test
	void privatePreferenceApiRemainsUnavailableUntilOwnershipMigrationCompletes() throws Exception {
		when(migration.isReady()).thenReturn(false);
		mvc.perform(get("/api/user-preferences")).andExpect(status().isServiceUnavailable());
	}
}
