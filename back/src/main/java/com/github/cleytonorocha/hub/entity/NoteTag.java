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
@Table(name = "hub_note_tag")
@IdClass(NoteTag.Key.class)
public class NoteTag {
	@Id @Column(name = "note_id", length = 36, nullable = false) private String noteId;
	@Id @Column(name = "tag_order", nullable = false) private int tagOrder;
	@TenantId @Column(name = "owner_id", length = 36, nullable = false) private String ownerId;
	@Column(name = "tag", length = 64, nullable = false) private String tag;
	@Column(name = "normalized_tag", length = 64, nullable = false) private String normalizedTag;
	protected NoteTag() {}
	public NoteTag(String noteId, int tagOrder, String tag, String normalizedTag) {
		this.noteId = noteId; this.tagOrder = tagOrder; this.tag = tag; this.normalizedTag = normalizedTag;
	}
	public String getNoteId() { return noteId; }
	public int getTagOrder() { return tagOrder; }
	public String getTag() { return tag; }
	public static final class Key implements Serializable {
		private static final long serialVersionUID = 1L;
		public String noteId; public int tagOrder;
		public Key() {}
		public Key(String noteId, int tagOrder) { this.noteId = noteId; this.tagOrder = tagOrder; }
		@Override public boolean equals(Object value) { return value instanceof Key other && Objects.equals(noteId, other.noteId) && tagOrder == other.tagOrder; }
		@Override public int hashCode() { return Objects.hash(noteId, tagOrder); }
	}
}
