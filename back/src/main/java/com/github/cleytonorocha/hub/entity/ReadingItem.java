package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "hub_reading_item")
public class ReadingItem {
	@Id
	@Column(name = "id", length = 36, nullable = false, columnDefinition = "varchar(36)")
	private String id;

	@Column(name = "title", length = 1024, nullable = false, columnDefinition = "varchar(1024)")
	private String title;

	@Column(name = "item_type", length = 16, nullable = false, columnDefinition = "varchar(16)")
	private String type;

	@Column(name = "author_or_source", length = 512, columnDefinition = "varchar(512)")
	private String authorOrSource;

	@Column(name = "reference_url", length = 2048, columnDefinition = "varchar(2048)")
	private String url;

	@Column(name = "reading_status", length = 16, nullable = false, columnDefinition = "varchar(16)")
	private String status;

	@Column(name = "progress_marker", length = 512, nullable = false, columnDefinition = "varchar(512)")
	private String progressMarker;

	@Column(name = "total_pages")
	private Integer totalPages;

	@Column(name = "current_page")
	private Integer currentPage;

	@Column(name = "personal_note", length = 2000, nullable = false, columnDefinition = "varchar(2000)")
	private String note;

	@Column(name = "archived", nullable = false, columnDefinition = "boolean")
	private boolean archived;

	@Column(name = "cover_image_name", length = 40, columnDefinition = "varchar(40)")
	private String coverImageName;

	@Column(name = "cover_image_sha256", length = 64, columnDefinition = "varchar(64)")
	private String coverImageSha256;

	@Column(name = "cover_source_url", length = 2048, columnDefinition = "varchar(2048)")
	private String coverSourceUrl;

	@ManyToMany(fetch = FetchType.LAZY)
	@jakarta.persistence.JoinTable(name = "hub_reading_item_author",
		joinColumns = @JoinColumn(name = "reading_item_id"),
		inverseJoinColumns = @JoinColumn(name = "author_id"))
	@org.hibernate.annotations.BatchSize(size = 100)
	private Set<ReadingAuthor> authors = new LinkedHashSet<>();

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "hub_reading_item_tag", joinColumns = @JoinColumn(name = "reading_item_id"))
	@OrderColumn(name = "tag_order")
	@Column(name = "tag", length = 64, nullable = false, columnDefinition = "varchar(64)")
	private List<String> tags = new ArrayList<>();

	@Version
	@Column(name = "revision", nullable = false, columnDefinition = "bigint")
	private long revision;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
	private Instant updatedAt;

	protected ReadingItem() {}

	public ReadingItem(String title, String type, String authorOrSource, String url, String status,
			String progressMarker, String note, List<String> tags, Instant now) {
		this(title, type, authorOrSource, url, status, progressMarker, null, null, note, tags, now);
	}

	public ReadingItem(String title, String type, String authorOrSource, String url, String status,
			String progressMarker, Integer totalPages, Integer currentPage, String note, List<String> tags, Instant now) {
		this(UUID.randomUUID().toString(), title, type, authorOrSource, url, status, progressMarker, note, tags, false, now, now, 0);
		this.totalPages = totalPages;
		this.currentPage = currentPage;
	}

	private ReadingItem(String id, String title, String type, String authorOrSource, String url, String status,
			String progressMarker, String note, List<String> tags, boolean archived, Instant createdAt, Instant updatedAt, long revision) {
		this.id = id;
		this.title = title;
		this.type = type;
		this.authorOrSource = authorOrSource;
		this.url = url;
		this.status = status;
		this.progressMarker = progressMarker;
		this.note = note;
		this.tags = new ArrayList<>(tags == null ? List.of() : tags);
		this.archived = archived;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.revision = revision;
	}

	public static ReadingItem restore(String id, String title, String type, String authorOrSource, String url,
			String status, String progressMarker, String note, List<String> tags, boolean archived,
			Instant createdAt, Instant updatedAt) {
		return new ReadingItem(id, title, type, authorOrSource, url, status, progressMarker, note, tags,
			archived, createdAt, updatedAt, 0);
	}

	public static ReadingItem restore(String id, String title, String type, String authorOrSource, String url,
			String status, String progressMarker, String note, List<String> tags, boolean archived,
			Instant createdAt, Instant updatedAt, String coverImageName, String coverImageSha256) {
		ReadingItem item = restore(id, title, type, authorOrSource, url, status, progressMarker, note, tags, archived, createdAt, updatedAt);
		item.coverImageName = coverImageName;
		item.coverImageSha256 = coverImageSha256;
		return item;
	}

	public static ReadingItem restore(String id, String title, String type, String authorOrSource, String url,
			String status, String progressMarker, Integer totalPages, Integer currentPage, String note, List<String> tags, boolean archived,
			Instant createdAt, Instant updatedAt, String coverImageName, String coverImageSha256) {
		ReadingItem item = restore(id, title, type, authorOrSource, url, status, progressMarker, note, tags, archived,
			createdAt, updatedAt, coverImageName, coverImageSha256);
		item.totalPages = totalPages;
		item.currentPage = currentPage;
		return item;
	}

	public void update(String title, String type, String authorOrSource, String url, String status,
			String progressMarker, Integer totalPages, Integer currentPage, String note, List<String> tags, boolean archived, Instant now) {
		this.title = title;
		this.type = type;
		this.authorOrSource = authorOrSource;
		this.url = url;
		this.status = status;
		this.progressMarker = progressMarker;
		this.totalPages = totalPages;
		this.currentPage = currentPage;
		this.note = note;
		this.tags = new ArrayList<>(tags);
		this.archived = archived;
		this.updatedAt = now;
	}

	public void updateCoverImage(String coverImageName, String coverImageSha256, Instant now) {
		this.coverImageName = coverImageName;
		this.coverImageSha256 = coverImageSha256;
		this.updatedAt = now;
	}

	public void updateCoverImage(String coverImageName, String coverImageSha256, String coverSourceUrl, Instant now) {
		this.coverImageName = coverImageName;
		this.coverImageSha256 = coverImageSha256;
		this.coverSourceUrl = coverSourceUrl;
		this.updatedAt = now;
	}

	public void replaceAuthors(Set<ReadingAuthor> authors, Instant now) {
		this.authors = new LinkedHashSet<>(authors);
		this.updatedAt = now;
	}

	public String getId() { return id; }
	public String getTitle() { return title; }
	public String getType() { return type; }
	public String getAuthorOrSource() { return authorOrSource; }
	public String getUrl() { return url; }
	public String getStatus() { return status; }
	public String getProgressMarker() { return progressMarker; }
	public Integer getTotalPages() { return totalPages; }
	public Integer getCurrentPage() { return currentPage; }
	public String getNote() { return note; }
	public boolean isArchived() { return archived; }
	public String getCoverImageName() { return coverImageName; }
	public String getCoverImageSha256() { return coverImageSha256; }
	public String getCoverSourceUrl() { return coverSourceUrl; }
	public Set<ReadingAuthor> getAuthors() { return Set.copyOf(authors); }
	public void restoreCoverSourceUrl(String coverSourceUrl) { this.coverSourceUrl = coverSourceUrl; }
	public List<String> getTags() { return List.copyOf(tags); }
	public long getRevision() { return revision; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
