package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.entity.ReadingAuthor;
import com.github.cleytonorocha.hub.entity.ReadingItem;
import com.github.cleytonorocha.hub.entity.ReadingItemAuthorLink;
import com.github.cleytonorocha.hub.entity.ReadingItemTag;
import com.github.cleytonorocha.hub.repository.ReadingAuthorRepository;
import com.github.cleytonorocha.hub.repository.ReadingItemAuthorLinkRepository;
import com.github.cleytonorocha.hub.repository.ReadingItemTagRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ReadingItemAssociationService {
	private final ReadingItemAuthorLinkRepository authorLinks;
	private final ReadingItemTagRepository tags;
	private final ReadingAuthorRepository authors;
	public ReadingItemAssociationService(ReadingItemAuthorLinkRepository authorLinks, ReadingItemTagRepository tags, ReadingAuthorRepository authors) {
		this.authorLinks = authorLinks; this.tags = tags; this.authors = authors;
	}

	public void populate(Collection<ReadingItem> items) {
		if (items == null || items.isEmpty()) return;
		List<String> ids = items.stream().map(ReadingItem::getId).distinct().toList();
		Map<String, List<String>> authorIdsByItem = new LinkedHashMap<>();
		for (ReadingItemAuthorLink link : authorLinks.findAllByReadingItemIdIn(ids))
			authorIdsByItem.computeIfAbsent(link.getReadingItemId(), ignored -> new ArrayList<>()).add(link.getAuthorId());
		Set<String> allAuthorIds = authorIdsByItem.values().stream().flatMap(Collection::stream).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Map<String, ReadingAuthor> authorsById = new LinkedHashMap<>();
		for (ReadingAuthor author : authors.findAllById(allAuthorIds)) authorsById.put(author.getId(), author);
		Map<String, List<String>> tagsByItem = new LinkedHashMap<>();
		for (ReadingItemTag tag : tags.findAllByReadingItemIdInOrderByReadingItemIdAscTagOrderAsc(ids))
			tagsByItem.computeIfAbsent(tag.getReadingItemId(), ignored -> new ArrayList<>()).add(tag.getTag());
		for (ReadingItem item : items) {
			List<ReadingAuthor> itemAuthors = authorIdsByItem.getOrDefault(item.getId(), List.of()).stream().map(authorsById::get).filter(java.util.Objects::nonNull).toList();
			item.replaceAuthors(new LinkedHashSet<>(itemAuthors), item.getUpdatedAt());
			item.restoreTags(tagsByItem.getOrDefault(item.getId(), List.of()));
		}
	}

	public void save(ReadingItem item) {
		authorLinks.deleteAllByReadingItemId(item.getId());
		tags.deleteAllByReadingItemId(item.getId());
		if (!item.getAuthors().isEmpty()) authorLinks.saveAll(item.getAuthors().stream().map(author -> new ReadingItemAuthorLink(item.getId(), author.getId())).toList());
		if (!item.getTags().isEmpty()) {
			List<ReadingItemTag> rows = new ArrayList<>();
			for (int i = 0; i < item.getTags().size(); i++) rows.add(new ReadingItemTag(item.getId(), i, item.getTags().get(i)));
			tags.saveAll(rows);
		}
	}
}
