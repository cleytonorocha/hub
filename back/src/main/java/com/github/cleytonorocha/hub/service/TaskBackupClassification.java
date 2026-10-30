package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupTask;
import java.util.List;

public record TaskBackupClassification(List<BackupTask> newTasks, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds) {
	public TaskBackupClassification {
		newTasks = List.copyOf(newTasks);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
