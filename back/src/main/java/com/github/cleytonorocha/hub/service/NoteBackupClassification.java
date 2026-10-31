package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupNote;
import java.util.List;

public record NoteBackupClassification(List<BackupNote> newNotes, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds) {
	public NoteBackupClassification {
		newNotes = List.copyOf(newNotes);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
