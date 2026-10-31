package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.entity.Note;
import com.github.cleytonorocha.hub.entity.NoteTag;
import com.github.cleytonorocha.hub.repository.NoteTagRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteTagAssociationService {
	private final NoteTagRepository tags;
	public NoteTagAssociationService(NoteTagRepository tags) { this.tags = tags; }

	@Transactional(readOnly = true)
	public void populate(List<Note> notes) {
		if (notes.isEmpty()) return;
		Map<String, Note> byId = new LinkedHashMap<>();
		for (Note note : notes) { note.restoreTags(List.of()); byId.put(note.getId(), note); }
		for (NoteTag tag : tags.findAllByNoteIdInOrderByNoteIdAscTagOrderAsc(byId.keySet())) {
			Note note = byId.get(tag.getNoteId());
			if (note != null) {
				java.util.ArrayList<String> values = new java.util.ArrayList<>(note.getTags());
				values.add(tag.getTag());
				note.restoreTags(values);
			}
		}
	}

	@Transactional
	public void save(Note note) {
		tags.deleteAllByNoteId(note.getId());
		List<String> values = note.getTags();
		for (int i = 0; i < values.size(); i++) {
			String value = values.get(i);
			tags.save(new NoteTag(note.getId(), i, value, TaskSearchNormalization.normalize(value)));
		}
	}
}
