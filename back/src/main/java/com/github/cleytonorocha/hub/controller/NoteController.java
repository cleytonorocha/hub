package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.NoteDtos.CreateRequest;
import com.github.cleytonorocha.hub.dto.NoteDtos.NoteView;
import com.github.cleytonorocha.hub.dto.NoteDtos.SearchResponse;
import com.github.cleytonorocha.hub.dto.NoteDtos.UpdateRequest;
import com.github.cleytonorocha.hub.service.NoteService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
public class NoteController {
	private final NoteService notes;
	public NoteController(NoteService notes) { this.notes = notes; }

	@GetMapping
	@PreAuthorize("hasAuthority('NOTES_READ')")
	public SearchResponse search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String tag,
			@RequestParam(defaultValue = "false") boolean archived, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) { return notes.search(q, tag, archived, page, size); }

	@GetMapping("/tags")
	@PreAuthorize("hasAuthority('NOTES_READ')")
	public List<String> tags(@RequestParam(defaultValue = "false") boolean archived) { return notes.listTags(archived); }

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('NOTES_READ')")
	public NoteView get(@PathVariable String id) { return notes.get(id); }

	@PostMapping
	@PreAuthorize("hasAuthority('NOTES_WRITE')")
	@ResponseStatus(HttpStatus.CREATED)
	public NoteView create(@Valid @RequestBody CreateRequest request) { return notes.create(request); }

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('NOTES_WRITE')")
	public NoteView update(@PathVariable String id, @Valid @RequestBody UpdateRequest request) { return notes.update(id, request); }

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('NOTES_WRITE')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) { notes.delete(id); }
}
