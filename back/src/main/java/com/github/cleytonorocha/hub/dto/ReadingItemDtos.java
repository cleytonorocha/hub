package com.github.cleytonorocha.hub.dto;

import com.github.cleytonorocha.hub.entity.ReadingItem;
import com.github.cleytonorocha.hub.entity.ReadingItemType;
import com.github.cleytonorocha.hub.entity.ReadingStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import com.github.cleytonorocha.hub.entity.ReadingAuthor;
import tools.jackson.databind.JsonNode;

public final class ReadingItemDtos {
	private ReadingItemDtos() {}

	public record CreateRequest(
		@NotBlank @Size(max = 1024) String title,
		@NotNull ReadingItemType type,
		@Size(max = 512) String authorOrSource,
		@Size(max = 2048) String url,
		ReadingStatus status,
		@Size(max = 512) String progressMarker,
		JsonNode totalPages,
		JsonNode currentPage,
		@Size(max = 2000) String note,
		@Size(max = 20) List<@NotBlank @Size(max = 64) String> tags,
		@Size(max = 20) List<@NotBlank @Size(max = 36) String> authorIds) {}

	public record UpdateRequest(
		@NotBlank @Size(max = 1024) String title,
		@NotNull ReadingItemType type,
		@Size(max = 512) String authorOrSource,
		@Size(max = 2048) String url,
		@NotNull ReadingStatus status,
		@Size(max = 512) String progressMarker,
		JsonNode totalPages,
		JsonNode currentPage,
		Boolean pageProgressProvided,
		@Size(max = 2000) String note,
		@Size(max = 20) List<@NotBlank @Size(max = 64) String> tags,
		@NotNull Boolean archived,
		@NotNull Long revision,
		@Size(max = 20) List<@NotBlank @Size(max = 36) String> authorIds) {}

	public record ItemView(String id, String title, ReadingItemType type, String authorOrSource, String url,
		ReadingStatus status, String progressMarker, Integer totalPages, Integer currentPage, String note, List<String> tags, boolean archived,
		long revision, Instant createdAt, Instant updatedAt, String coverImageUrl, String coverSourceUrl, List<AuthorView> authors) {
		public static ItemView from(ReadingItem item) {
			return new ItemView(item.getId(), item.getTitle(), ReadingItemType.valueOf(item.getType()), item.getAuthorOrSource(),
				item.getUrl(), ReadingStatus.valueOf(item.getStatus()), item.getProgressMarker(), item.getTotalPages(), item.getCurrentPage(), item.getNote(),
			item.getTags(), item.isArchived(), item.getRevision(), item.getCreatedAt(), item.getUpdatedAt(),
			item.getCoverImageName() == null ? null : "/api/reading-items/" + item.getId() + "/cover", item.getCoverSourceUrl(),
			item.getAuthors().stream().map(AuthorView::from).sorted(java.util.Comparator.comparing(AuthorView::name, String.CASE_INSENSITIVE_ORDER)).toList());
		}
	}

	public record AuthorView(String id, String name) {
		public static AuthorView from(ReadingAuthor author) { return new AuthorView(author.getId(), author.getName()); }
	}

	public record SearchResponse(List<ItemView> content, int page, int size, long totalElements, int totalPages) {}
}
