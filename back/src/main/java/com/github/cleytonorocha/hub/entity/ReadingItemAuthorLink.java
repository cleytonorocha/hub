package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "hub_reading_item_author")
@IdClass(ReadingItemAuthorLink.Key.class)
public class ReadingItemAuthorLink {
	@Id @Column(name = "reading_item_id", length = 36, nullable = false) private String readingItemId;
	@Id @Column(name = "author_id", length = 36, nullable = false) private String authorId;
	@TenantId @Column(name = "owner_id", length = 36, nullable = false) private String ownerId;
	protected ReadingItemAuthorLink() {}
	public ReadingItemAuthorLink(String readingItemId, String authorId) { this.readingItemId = readingItemId; this.authorId = authorId; }
	public String getReadingItemId() { return readingItemId; }
	public String getAuthorId() { return authorId; }
	public static final class Key implements Serializable {
		private static final long serialVersionUID = 1L;
		public String readingItemId; public String authorId;
		public Key() {}
		public Key(String readingItemId, String authorId) { this.readingItemId = readingItemId; this.authorId = authorId; }
		@Override public boolean equals(Object value) { return value instanceof Key other && Objects.equals(readingItemId, other.readingItemId) && Objects.equals(authorId, other.authorId); }
		@Override public int hashCode() { return Objects.hash(readingItemId, authorId); }
	}
}
