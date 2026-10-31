package com.github.cleytonorocha.hub.service;

import java.util.List;

public record HabitBackupClassificationSnapshot(List<String> newIds, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds) {
	public HabitBackupClassificationSnapshot {
		newIds = List.copyOf(newIds);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
