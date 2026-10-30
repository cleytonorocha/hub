package com.github.cleytonorocha.hub.service;

import java.util.List;

public record TaskBackupClassificationSnapshot(List<String> newIds, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds) {
	public TaskBackupClassificationSnapshot {
		newIds = List.copyOf(newIds);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
