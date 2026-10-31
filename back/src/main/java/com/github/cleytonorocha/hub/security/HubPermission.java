package com.github.cleytonorocha.hub.security;

import java.util.Set;

public final class HubPermission {
	private HubPermission() {}
	public static final Set<String> ALL = Set.of("TASK_READ", "TASK_WRITE", "TASK_DELETE", "READING_READ", "READING_WRITE", "READING_ARCHIVE", "NOTES_READ", "NOTES_WRITE", "HABITS_READ", "HABITS_WRITE", "FOCUS_READ", "FOCUS_WRITE", "SETTINGS_READ", "SETTINGS_WRITE", "VIDEO_DOWNLOAD", "USER_MANAGE", "PROFILE_MANAGE");
	public static final String ADMIN_DEFAULT = String.join(",", ALL);
	public static final String USER_DEFAULT = "TASK_READ,TASK_WRITE,TASK_DELETE,READING_READ,READING_WRITE,READING_ARCHIVE,HABITS_READ,HABITS_WRITE,SETTINGS_READ,SETTINGS_WRITE,VIDEO_DOWNLOAD";
}
