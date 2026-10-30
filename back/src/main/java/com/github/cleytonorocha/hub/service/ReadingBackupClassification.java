package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.TaskBackupDtos.BackupReadingItem;
import java.util.List;

public record ReadingBackupClassification(List<BackupReadingItem> newItems, List<String> identicalIds,
	List<String> conflictIds, int repeatedIdenticalIds) {
	public ReadingBackupClassification {
		newItems = List.copyOf(newItems);
		identicalIds = List.copyOf(identicalIds);
		conflictIds = List.copyOf(conflictIds);
	}
}
