package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupHabit;
import java.util.List;

public record HabitBackupClassification(List<BackupHabit> newHabits, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds) {
	public HabitBackupClassification {
		newHabits = List.copyOf(newHabits);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
