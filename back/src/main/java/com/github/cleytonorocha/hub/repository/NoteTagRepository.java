package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.NoteTag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteTagRepository extends JpaRepository<NoteTag, NoteTag.Key> {
	List<NoteTag> findAllByNoteIdInOrderByNoteIdAscTagOrderAsc(Collection<String> noteIds);
	void deleteAllByNoteId(String noteId);
	@Query("select distinct tag.tag from NoteTag tag, Note note where tag.noteId = note.id and note.archived = :archived order by tag.tag")
	List<String> findDistinctTagsByArchived(@Param("archived") boolean archived);
}
