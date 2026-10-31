package com.github.cleytonorocha.hub.service;

public record PortableCopyClassificationSnapshot(TaskBackupClassificationSnapshot tasks,
	ReadingBackupClassificationSnapshot readingItems, HabitBackupClassificationSnapshot habits,
	FocusBackupClassificationSnapshot focusSessions, NoteBackupClassificationSnapshot notes) {
	public PortableCopyClassificationSnapshot(TaskBackupClassificationSnapshot tasks, ReadingBackupClassificationSnapshot readingItems) {
		this(tasks, readingItems, new HabitBackupClassificationSnapshot(java.util.List.of(), java.util.List.of(), java.util.List.of(), 0),
			new FocusBackupClassificationSnapshot(java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, false),
			new NoteBackupClassificationSnapshot(java.util.List.of(), java.util.List.of(), java.util.List.of(), 0));
	}
}
