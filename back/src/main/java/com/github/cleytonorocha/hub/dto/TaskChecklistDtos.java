package com.github.cleytonorocha.hub.dto;

import java.util.List;

public final class TaskChecklistDtos {
	private TaskChecklistDtos() {}
	public record ChecklistItem(String id, String text, boolean checked, int position) {}
	public record ChecklistView(long revision, List<ChecklistItem> items) {}
	public record SaveChecklistRequest(long expectedRevision, List<ChecklistItem> items) {}
}
