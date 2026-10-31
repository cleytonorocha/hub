package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupFocusSession;
import java.util.List;

public record FocusBackupClassification(List<BackupFocusSession> newSessions, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds, boolean activeSlotConflict) {
	public FocusBackupClassification {
		newSessions = List.copyOf(newSessions);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
