package com.github.cleytonorocha.hub.dto;

import com.github.cleytonorocha.hub.entity.Note;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class NoteDtos {
	private NoteDtos() {}
	public record CreateRequest(@NotBlank @Size(max = 512) String title, @NotNull @Size(max = 2048) String body,
		@Size(max = 20) List<@NotBlank @Size(max = 64) String> tags, boolean pinned,
		@Size(max = 36) String relatedTaskId) {}
	public record UpdateRequest(@NotBlank @Size(max = 512) String title, @NotNull @Size(max = 2048) String body,
		@Size(max = 20) List<@NotBlank @Size(max = 64) String> tags, boolean pinned, boolean archived,
		@Size(max = 36) String relatedTaskId, boolean reaffirmRelatedTask, @NotNull Long revision) {}
	public record NoteView(String id, String title, String body, List<String> tags, boolean pinned, boolean archived,
		String relatedTaskId, String relatedTaskStatus, String relatedTaskTitle, long revision, Instant createdAt, Instant updatedAt) {
		public static NoteView from(Note note, String relatedTaskStatus, String relatedTaskTitle) {
			return new NoteView(note.getId(), note.getTitle(), note.getBody(), note.getTags(), note.isPinned(), note.isArchived(),
				note.getRelatedTaskId(), relatedTaskStatus, relatedTaskTitle, note.getRevision(), note.getCreatedAt(), note.getUpdatedAt());
		}
	}
	public record SearchResponse(List<NoteView> content, int page, int size, long totalElements, int totalPages) {}
}
