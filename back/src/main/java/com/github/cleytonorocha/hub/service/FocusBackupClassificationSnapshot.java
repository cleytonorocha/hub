package com.github.cleytonorocha.hub.service;

import java.util.List;

public record FocusBackupClassificationSnapshot(List<String> newIds, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds, boolean activeSlotConflict) {
	public FocusBackupClassificationSnapshot {
		newIds = List.copyOf(newIds);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
