package com.github.cleytonorocha.hub.service;

public record PortableCopyClassificationSnapshot(TaskBackupClassificationSnapshot tasks,
	ReadingBackupClassificationSnapshot readingItems) {}
