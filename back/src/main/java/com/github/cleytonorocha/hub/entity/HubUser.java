package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hub_user")
public class HubUser {
	@Id @Column(length = 36, nullable = false) private String id;
	@Column(name = "display_name", length = 120, nullable = false) private String displayName;
	@Column(length = 254, nullable = false) private String email;
	@Column(name = "normalized_email", length = 254, nullable = false, unique = true) private String normalizedEmail;
	@Column(name = "password_hash", length = 255, nullable = false) private String passwordHash;
	@Column(name = "profile_id", length = 36, nullable = false) private String profileId;
	@Column(nullable = false) private boolean enabled = true;
	@Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
	protected HubUser() {}
	public HubUser(String displayName, String email, String passwordHash, String profileId) {
		this.id = UUID.randomUUID().toString(); this.displayName = displayName.strip(); this.email = email.strip();
		this.normalizedEmail = email.strip().toLowerCase(java.util.Locale.ROOT); this.passwordHash = passwordHash;
		this.profileId = profileId; this.enabled = true; this.createdAt = Instant.now();
	}
	public String getId() { return id; }
	public String getDisplayName() { return displayName; }
	public String getEmail() { return email; }
	public String getNormalizedEmail() { return normalizedEmail; }
	public String getPasswordHash() { return passwordHash; }
	public String getProfileId() { return profileId; }
	public boolean isEnabled() { return enabled; }
	public Instant getCreatedAt() { return createdAt; }
	public void setProfileId(String profileId) { this.profileId = profileId; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
