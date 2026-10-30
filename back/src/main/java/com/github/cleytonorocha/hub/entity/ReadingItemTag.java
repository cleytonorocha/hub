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
@Table(name = "hub_reading_item_tag")
@IdClass(ReadingItemTag.Key.class)
public class ReadingItemTag {
	@Id @Column(name = "reading_item_id", length = 36, nullable = false) private String readingItemId;
	@Id @Column(name = "tag_order", nullable = false) private int tagOrder;
	@TenantId @Column(name = "owner_id", length = 36, nullable = false) private String ownerId;
	@Column(name = "tag", length = 64, nullable = false) private String tag;
	protected ReadingItemTag() {}
	public ReadingItemTag(String readingItemId, int tagOrder, String tag) { this.readingItemId = readingItemId; this.tagOrder = tagOrder; this.tag = tag; }
	public String getReadingItemId() { return readingItemId; }
	public int getTagOrder() { return tagOrder; }
	public String getTag() { return tag; }
	public static final class Key implements Serializable {
		private static final long serialVersionUID = 1L;
		public String readingItemId; public int tagOrder;
		public Key() {}
		public Key(String readingItemId, int tagOrder) { this.readingItemId = readingItemId; this.tagOrder = tagOrder; }
		@Override public boolean equals(Object value) { return value instanceof Key other && Objects.equals(readingItemId, other.readingItemId) && tagOrder == other.tagOrder; }
		@Override public int hashCode() { return Objects.hash(readingItemId, tagOrder); }
	}
}
