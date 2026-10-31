package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.Note;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NoteRepository extends JpaRepository<Note, String>, JpaSpecificationExecutor<Note> {
	List<Note> findAllByIdIn(Collection<String> ids);
	long count();
}
