package com.github.cleytonorocha.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "hub_reading_author")
public class ReadingAuthor {
	@Id
	@Column(name = "id", length = 36, nullable = false, columnDefinition = "varchar(36)")
	private String id;
	@TenantId
	@Column(name = "owner_id", length = 36)
	private String ownerId;
	@Column(name = "name", length = 160, nullable = false, columnDefinition = "varchar(160)")
	private String name;
	@Column(name = "normalized_name", length = 160, nullable = false, columnDefinition = "varchar(160)")
	private String normalizedName;

	protected ReadingAuthor() {}
	public ReadingAuthor(String name) {
		this.id = UUID.randomUUID().toString();
		this.name = name.strip();
		this.normalizedName = normalize(name);
	}
	public static ReadingAuthor restore(String id, String name) {
		ReadingAuthor author = new ReadingAuthor(name);
		author.id = id;
		return author;
	}
	public static String normalize(String value) {
		return Normalizer.normalize(value.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}
	public String getId() { return id; }
	public String getName() { return name; }
	public String getNormalizedName() { return normalizedName; }
}
