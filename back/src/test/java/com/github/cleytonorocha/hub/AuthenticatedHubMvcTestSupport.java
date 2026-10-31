package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.security.HubPrincipal;
import com.github.cleytonorocha.hub.security.HubPermission;
import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/** Real persisted, migrated owner plus normal auth/CSRF request post-processors for MVC integration tests. */
abstract class AuthenticatedHubMvcTestSupport {
	static final String TEST_OWNER = "a0000000-0000-4000-8000-000000000001";
	private static final String TEST_PROFILE = "a0000000-0000-4000-8000-000000000002";
	private static final String PERMISSIONS = HubPermission.ADMIN_DEFAULT;

	@Autowired private MockMvc delegate;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private AuthOwnershipMigration migration;
	protected HubAuthenticatedMvc mvc;
	private HubPrincipal actor;
	private final List<String> temporaryOwners = new ArrayList<>();
	private final List<String> temporaryProfiles = new ArrayList<>();
	private final java.util.Map<String, String> ownerProfilesToRestore = new java.util.LinkedHashMap<>();
	private final java.util.Map<String, Boolean> ownerEnabledToRestore = new java.util.LinkedHashMap<>();

	@BeforeEach
	void seedAuthenticatedMigratedOwner() {
		if (jdbc.queryForObject("SELECT COUNT(*) FROM hub_permission_profile WHERE id=?", Integer.class, TEST_PROFILE) == 0) {
			jdbc.update("INSERT INTO hub_permission_profile(id,name,normalized_name,permissions,system_profile) VALUES(?,?,?,?,TRUE)",
				TEST_PROFILE, "Integration test", "integration-test-profile", PERMISSIONS);
		}
		jdbc.update("UPDATE hub_permission_profile SET permissions=?,system_profile=TRUE WHERE id=?", PERMISSIONS, TEST_PROFILE);
		if (jdbc.queryForObject("SELECT COUNT(*) FROM hub_user WHERE id=?", Integer.class, TEST_OWNER) == 0) {
			jdbc.update("INSERT INTO hub_user(id,display_name,email,normalized_email,password_hash,profile_id,enabled,created_at) VALUES(?,?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)",
				TEST_OWNER, "Integration test", "integration-test@example.invalid", "integration-test@example.invalid", "unused-test-hash", TEST_PROFILE);
		}
		jdbc.update("UPDATE hub_user SET profile_id=?,enabled=TRUE WHERE id=?", TEST_PROFILE, TEST_OWNER);
		for (String table : List.of("hub_task", "hub_task_series", "hub_reading_item", "hub_reading_author",
				"hub_reading_item_author", "hub_reading_item_tag", "hub_task_checklist_revision", "hub_task_checklist_item",
				"hub_task_collection_state", "hub_reading_collection_state", "hub_task_settings", "google_connection",
				"google_task_list_state", "google_task_state", "hub_task_notification", "hub_task_user_revision", "hub_reading_user_revision",
				"hub_note", "hub_note_tag", "hub_note_user_revision", "hub_habit", "hub_habit_schedule", "hub_habit_pause",
				"hub_habit_checkin", "hub_habit_user_revision", "hub_focus_session", "hub_focus_active_slot")) {
			jdbc.update("UPDATE " + table + " SET owner_id=? WHERE owner_id IS NULL", TEST_OWNER);
		}
		migration.migrate();
		actor = new HubPrincipal(TEST_OWNER, "Integration test", "integration-test@example.invalid", "unused-test-hash", true,
			TEST_PROFILE, "Integration test", PERMISSIONS);
		mvc = new HubAuthenticatedMvc(delegate, actor);
		setTestSecurityContext();
	}

	@AfterEach
	void clearTestSecurityContext() {
		for (var entry : ownerProfilesToRestore.entrySet()) jdbc.update("UPDATE hub_user SET profile_id=?,enabled=? WHERE id=?",
			entry.getValue(), ownerEnabledToRestore.get(entry.getKey()), entry.getKey());
		for (String owner : temporaryOwners) jdbc.update("DELETE FROM hub_user WHERE id=?", owner);
		for (String profile : temporaryProfiles) jdbc.update("DELETE FROM hub_permission_profile WHERE id=?", profile);
		SecurityContextHolder.clearContext();
	}

	private void setTestSecurityContext() {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities()));
	}

	protected static final class HubAuthenticatedMvc {
		private final MockMvc delegate;
		private final HubPrincipal actor;
		private final MockHttpSession session = new MockHttpSession();
		private final Runnable before;
		private final Runnable after;
		private HubAuthenticatedMvc(MockMvc delegate, HubPrincipal actor) { this(delegate, actor, () -> {}, () -> {}); }
		private HubAuthenticatedMvc(MockMvc delegate, HubPrincipal actor, Runnable before, Runnable after) {
			this.delegate = delegate; this.actor = actor; this.before = before; this.after = after;
		}
		ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
			RequestPostProcessor signedIn = authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities()));
			try {
				before.run();
				return delegate.perform(request.session(session).with(signedIn).with(csrf()));
			} finally {
				SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities()));
				after.run();
			}
		}
		ResultActions performAsyncDispatch(MvcResult result) throws Exception {
			return delegate.perform(asyncDispatch(result));
		}
	}

	protected HubAuthenticatedMvc mvcWithPermissions(String permissions) {
		String previousProfile = jdbc.queryForObject("SELECT profile_id FROM hub_user WHERE id=?", String.class, TEST_OWNER);
		String profileId = createProfile(permissions);
		HubPrincipal limitedActor = new HubPrincipal(TEST_OWNER, "Integration test", "integration-test@example.invalid", "unused-test-hash",
			true, "test-profile", "Test profile", permissions);
		return new HubAuthenticatedMvc(delegate, limitedActor,
			() -> jdbc.update("UPDATE hub_user SET profile_id=? WHERE id=?", profileId, TEST_OWNER),
			() -> {
				jdbc.update("UPDATE hub_user SET profile_id=? WHERE id=?", previousProfile, TEST_OWNER);
				jdbc.update("DELETE FROM hub_permission_profile WHERE id=?", profileId);
			});
	}

	protected HubAuthenticatedMvc mvcAsOwnerWithPermissions(String owner, String permissions) {
		String profileId = createProfile(permissions);
		if (jdbc.queryForObject("SELECT COUNT(*) FROM hub_user WHERE id=?", Integer.class, owner) == 0) {
			String email = owner + "@integration.invalid";
			jdbc.update("INSERT INTO hub_user(id,display_name,email,normalized_email,password_hash,profile_id,enabled,created_at) VALUES(?,?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)",
				owner, "Other integration user", email, email, "unused-test-hash", profileId);
			temporaryOwners.add(owner);
		} else {
			ownerProfilesToRestore.putIfAbsent(owner, jdbc.queryForObject("SELECT profile_id FROM hub_user WHERE id=?", String.class, owner));
			ownerEnabledToRestore.putIfAbsent(owner, jdbc.queryForObject("SELECT enabled FROM hub_user WHERE id=?", Boolean.class, owner));
			jdbc.update("UPDATE hub_user SET profile_id=?,enabled=TRUE WHERE id=?", profileId, owner);
		}
		HubPrincipal limitedActor = new HubPrincipal(owner, "Integration test", owner + "@example.invalid", "unused-test-hash",
			true, "test-profile", "Test profile", permissions);
		return new HubAuthenticatedMvc(delegate, limitedActor);
	}

	private String createProfile(String permissions) {
		String id = UUID.randomUUID().toString();
		String normalizedName = "test-profile-" + id;
		jdbc.update("INSERT INTO hub_permission_profile(id,name,normalized_name,permissions,system_profile) VALUES(?,?,?,?,FALSE)",
			id, "Test profile", normalizedName, permissions);
		temporaryProfiles.add(id);
		return id;
	}
}
