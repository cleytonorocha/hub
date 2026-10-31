package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.entity.HubUser;
import com.github.cleytonorocha.hub.entity.PermissionProfile;
import com.github.cleytonorocha.hub.repository.HubUserRepository;
import com.github.cleytonorocha.hub.repository.PermissionProfileRepository;
import com.github.cleytonorocha.hub.security.AuthContext;
import com.github.cleytonorocha.hub.security.HubPermission;
import com.github.cleytonorocha.hub.security.HubPrincipal;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
	private final JdbcTemplate jdbc;
	private final HubUserRepository users;
	private final PermissionProfileRepository profiles;
	private final PasswordEncoder passwords;
	private final AuthOwnershipMigration ownershipMigration;

	public AuthService(JdbcTemplate jdbc, HubUserRepository users, PermissionProfileRepository profiles, PasswordEncoder passwords, AuthOwnershipMigration ownershipMigration) {
		this.jdbc = jdbc; this.users = users; this.profiles = profiles; this.passwords = passwords; this.ownershipMigration = ownershipMigration;
	}

	@Transactional(readOnly = true)
	public boolean setupRequired() { return users.count() == 0; }

	@Transactional
	public SetupResult setup(SetupRequest request) {
		validatePassword(request.password());
		if (request.displayName().isBlank() || request.displayName().strip().length() > 120) throw badRequest("Informe um nome com até 120 caracteres.");
		String email = normalizeEmail(request.email());
		Long locked = jdbc.queryForObject("SELECT bootstrap_id FROM hub_auth_bootstrap WHERE bootstrap_id = 1 FOR UPDATE", Long.class);
		if (locked == null || users.count() != 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "A configuração inicial já foi concluída.");
		PermissionProfile admin = profiles.saveAndFlush(new PermissionProfile("Administrador", HubPermission.ADMIN_DEFAULT, true));
		profiles.saveAndFlush(new PermissionProfile("Usuário", HubPermission.USER_DEFAULT, true));
		HubUser owner = users.saveAndFlush(new HubUser(request.displayName(), request.email(), passwords.encode(request.password()), admin.getId()));
		Map<String, Long> migrated = backfillLegacyData(owner.getId());
		jdbc.update("INSERT INTO hub_task_user_revision(owner_id, revision) VALUES (?, 0)", owner.getId());
		jdbc.update("INSERT INTO hub_reading_user_revision(owner_id, revision) VALUES (?, 0)", owner.getId());
		jdbc.update("INSERT INTO hub_task_user_settings(owner_id, reminder_time) SELECT ?, reminder_time FROM hub_task_settings WHERE settings_id = 1", owner.getId());
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override public void afterCommit() { ownershipMigration.migrate(); }
		});
		return new SetupResult(SessionView.from(owner, admin), migrated);
	}

	@Transactional(readOnly = true)
	public SessionView current(HubPrincipal principal) {
		HubUser user = users.findById(principal.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		PermissionProfile profile = profiles.findById(user.getProfileId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		return SessionView.from(user, profile);
	}

	@Transactional(readOnly = true)
	public List<SessionView> listUsers() {
		return users.findAllByOrderByEmailAsc().stream().map(user -> SessionView.from(user,
			profiles.findById(user.getProfileId()).orElseThrow())).toList();
	}

	@Transactional
	public SessionView createUser(CreateUserRequest request) {
		validatePassword(request.password());
		String normalizedEmail = normalizeEmail(request.email());
		if (users.existsByNormalizedEmail(normalizedEmail)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma conta com este e-mail.");
		PermissionProfile profile = profiles.findById(request.profileId()).orElseThrow(() -> badRequest("Selecione um perfil válido."));
		HubUser user = users.saveAndFlush(new HubUser(request.displayName(), request.email(), passwords.encode(request.password()), profile.getId()));
		jdbc.update("INSERT INTO hub_task_user_revision(owner_id, revision) VALUES (?, 0)", user.getId());
		jdbc.update("INSERT INTO hub_reading_user_revision(owner_id, revision) VALUES (?, 0)", user.getId());
		jdbc.update("INSERT INTO hub_task_user_settings(owner_id, reminder_time) VALUES (?, TIME '09:00')", user.getId());
		return SessionView.from(user, profile);
	}

	@Transactional
	public SessionView updateUser(String id, UpdateUserRequest request) {
		lockAdminChanges();
		HubUser user = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		PermissionProfile prior = profiles.findById(user.getProfileId()).orElseThrow();
		PermissionProfile next = profiles.findById(request.profileId()).orElseThrow(() -> badRequest("Selecione um perfil válido."));
		boolean wasAdmin = hasPermission(prior, "USER_MANAGE");
		boolean willBeAdmin = hasPermission(next, "USER_MANAGE");
		if (user.isEnabled() && wasAdmin && (!request.enabled() || !willBeAdmin) && activeAdminCount() <= 1)
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Mantenha pelo menos uma conta Administrador ativa.");
		if (id.equals(AuthContext.userId()) && !request.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Você não pode desativar sua própria conta.");
		user.setEnabled(request.enabled()); user.setProfileId(next.getId());
		if (request.newPassword() != null && !request.newPassword().isBlank()) { validatePassword(request.newPassword()); user.setPasswordHash(passwords.encode(request.newPassword())); }
		return SessionView.from(users.save(user), next);
	}

	@Transactional(readOnly = true)
	public List<ProfileView> listProfiles() {
		return profiles.findAllByOrderByNameAsc().stream().map(ProfileView::from).toList();
	}

	@Transactional
	public ProfileView createProfile(ProfileRequest request) {
		Set<String> permissions = validatePermissions(request.permissions());
		String normalized = normalizeProfileName(request.name());
		if (profiles.findByNormalizedName(normalized).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um perfil com este nome.");
		return ProfileView.from(profiles.save(new PermissionProfile(request.name(), String.join(",", permissions), false)));
	}

	@Transactional
	public ProfileView updateProfile(String id, ProfileRequest request) {
		lockAdminChanges();
		PermissionProfile profile = profiles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Set<String> permissions = validatePermissions(request.permissions());
		String normalizedName = normalizeProfileName(request.name());
		profiles.findByNormalizedName(normalizedName).filter(existing -> !existing.getId().equals(id)).ifPresent(existing -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um perfil com este nome."); });
		boolean adminProfile = profile.isSystemProfile() && profile.getNormalizedName().equals(normalizeProfileName("Administrador"));
		boolean wasAdmin = hasPermission(profile, "USER_MANAGE");
		boolean willBeAdmin = permissions.contains("USER_MANAGE");
		if (wasAdmin && !willBeAdmin) {
			long adminsLost = users.findAll().stream().filter(HubUser::isEnabled).filter(user -> id.equals(user.getProfileId())).count();
			if (activeAdminCount() - adminsLost < 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Este perfil é o último acesso Administrador ativo.");
		}
		if (adminProfile && !permissions.contains("USER_MANAGE"))
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O perfil Administrador precisa manter a permissão de gestão de usuários.");
		if (adminProfile && !permissions.contains("PROFILE_MANAGE"))
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O perfil Administrador precisa manter a permissão de gestão de perfis.");
		profile.update(profile.isSystemProfile() ? profile.getName() : request.name(), String.join(",", permissions));
		return ProfileView.from(profiles.save(profile));
	}

	private Map<String, Long> backfillLegacyData(String ownerId) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : List.of("hub_task", "hub_task_series", "hub_reading_item", "hub_reading_author", "hub_reading_item_author", "hub_reading_item_tag", "hub_task_checklist_revision", "hub_task_checklist_item", "hub_task_collection_state", "hub_reading_collection_state", "hub_task_settings", "google_connection", "google_task_list_state", "google_task_state", "hub_task_notification")) {
			Long unassigned = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id IS NULL", Long.class);
			int changed = jdbc.update("UPDATE " + table + " SET owner_id = ? WHERE owner_id IS NULL", ownerId);
			Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id IS NULL", Long.class);
			if (unassigned == null || changed != unassigned || remaining != 0) throw new IllegalStateException("A atribuição dos registros legados falhou em " + table + ".");
			counts.put(table, unassigned);
		}
		return Map.copyOf(counts);
	}
	private long activeAdminCount() {
		return users.findAll().stream().filter(HubUser::isEnabled).filter(user -> profiles.findById(user.getProfileId()).map(p -> hasPermission(p, "USER_MANAGE")).orElse(false)).count();
	}
	private void lockAdminChanges() {
		jdbc.queryForObject("SELECT bootstrap_id FROM hub_auth_bootstrap WHERE bootstrap_id = 1 FOR UPDATE", Integer.class);
	}
	private static boolean hasPermission(PermissionProfile profile, String permission) { return Arrays.asList(profile.getPermissions().split(",")).contains(permission); }
	private static Set<String> validatePermissions(List<String> values) {
		if (values == null) throw badRequest("Selecione as permissões do perfil.");
		Set<String> result = values.stream().filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
		if (result.stream().anyMatch(value -> !HubPermission.ALL.contains(value))) throw badRequest("O perfil contém uma permissão não reconhecida.");
		return result;
	}
	private static String normalizeEmail(String value) {
		if (value == null || value.isBlank() || value.strip().length() > 254 || !value.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw badRequest("Informe um e-mail válido.");
		return value.strip().toLowerCase(java.util.Locale.ROOT);
	}
	private static String normalizeProfileName(String name) {
		if (name == null || name.isBlank() || name.strip().length() > 80) throw badRequest("Informe um nome de perfil com até 80 caracteres.");
		return name.strip().toLowerCase(java.util.Locale.ROOT);
	}
	private static void validatePassword(String value) {
		if (value == null || value.length() < 12 || value.length() > 128) throw badRequest("Use uma senha entre 12 e 128 caracteres.");
	}
	private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

	public record SetupRequest(@NotBlank @Size(max=120) String displayName, @NotBlank @Email @Size(max=254) String email, @NotBlank @Size(min=12,max=128) String password) {}
	public record SetupResult(SessionView owner, Map<String, Long> migratedLegacyRows) {}
	public record CreateUserRequest(@NotBlank @Size(max=120) String displayName, @NotBlank @Email @Size(max=254) String email, @NotBlank @Size(min=12,max=128) String password, @NotBlank String profileId) {}
	public record UpdateUserRequest(@NotBlank String profileId, boolean enabled, @Size(min=12,max=128) String newPassword) {}
	public record ProfileRequest(@NotBlank @Size(max=80) String name, @NotNull List<String> permissions) {}
	public record SessionView(String id, String displayName, String email, boolean enabled, String profileId, String profileName, List<String> permissions) {
		static SessionView from(HubUser user, PermissionProfile profile) { return new SessionView(user.getId(), user.getDisplayName(), user.getEmail(), user.isEnabled(), profile.getId(), profile.getName(), Arrays.stream(profile.getPermissions().split(",")).filter(s -> !s.isBlank()).toList()); }
	}
	public record ProfileView(String id, String name, List<String> permissions, boolean systemProfile) {
		static ProfileView from(PermissionProfile profile) { return new ProfileView(profile.getId(), profile.getName(), Arrays.stream(profile.getPermissions().split(",")).filter(s -> !s.isBlank()).toList(), profile.isSystemProfile()); }
	}
}
