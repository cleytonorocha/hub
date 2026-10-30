package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "hub_permission_profile")
public class PermissionProfile {
	@Id @Column(length = 36, nullable = false) private String id;
	@Column(length = 80, nullable = false) private String name;
	@Column(name = "normalized_name", length = 80, nullable = false, unique = true) private String normalizedName;
	@Column(nullable = false, columnDefinition = "clob") private String permissions;
	@Column(name = "system_profile", nullable = false) private boolean systemProfile;
	protected PermissionProfile() {}
	public PermissionProfile(String name, String permissions, boolean systemProfile) {
		this.id = UUID.randomUUID().toString(); this.name = name.strip();
		this.normalizedName = name.strip().toLowerCase(java.util.Locale.ROOT); this.permissions = permissions; this.systemProfile = systemProfile;
	}
	public String getId() { return id; }
	public String getName() { return name; }
	public String getNormalizedName() { return normalizedName; }
	public String getPermissions() { return permissions; }
	public boolean isSystemProfile() { return systemProfile; }
	public void update(String name, String permissions) { this.name = name.strip(); this.normalizedName = name.strip().toLowerCase(java.util.Locale.ROOT); this.permissions = permissions; }
}
