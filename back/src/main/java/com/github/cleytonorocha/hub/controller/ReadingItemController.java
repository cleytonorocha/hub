package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.dto.ReadingItemDtos.CreateRequest;
import com.github.cleytonorocha.hub.dto.ReadingItemDtos.ItemView;
import com.github.cleytonorocha.hub.dto.ReadingItemDtos.SearchResponse;
import com.github.cleytonorocha.hub.dto.ReadingItemDtos.UpdateRequest;
import com.github.cleytonorocha.hub.service.ReadingItemService;
import com.github.cleytonorocha.hub.service.ReadingItemService.CoverContent;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/reading-items")
public class ReadingItemController {
	private final ReadingItemService items;

	public ReadingItemController(ReadingItemService items) { this.items = items; }

	@GetMapping
	public SearchResponse search(
			@RequestParam(defaultValue = "") String q,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String type,
			@RequestParam(defaultValue = "false") boolean archived,
		@RequestParam(required = false) String authorId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return items.search(q, status, type, archived, authorId, page, size);
	}

	@GetMapping("/authors")
	public java.util.List<com.github.cleytonorocha.hub.dto.ReadingItemDtos.AuthorView> authors() { return items.listAuthors(); }

	@PostMapping("/authors")
	@ResponseStatus(HttpStatus.CREATED)
	public com.github.cleytonorocha.hub.dto.ReadingItemDtos.AuthorView createAuthor(@Valid @RequestBody AuthorRequest request) {
		return items.createAuthor(request.name());
	}
	public record AuthorRequest(@NotBlank @Size(max = 160) String name) {}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ItemView create(@Valid @RequestBody CreateRequest request) { return items.create(request); }

	@GetMapping("/{id}")
	public ItemView get(@PathVariable String id) { return items.get(id); }

	@PutMapping("/{id}")
	public ItemView update(@PathVariable String id, @Valid @RequestBody UpdateRequest request) { return items.update(id, request); }

	@PutMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ItemView uploadCover(@PathVariable String id, @RequestParam long revision, @RequestPart("file") MultipartFile file) {
		try { return items.uploadCover(id, revision, file); }
		catch (com.github.cleytonorocha.hub.service.ReadingCoverStorageService.CoverValidationException error) {
			throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
		}
	}

	@PostMapping("/{id}/cover/import")
	public ItemView importCover(@PathVariable String id, @Valid @RequestBody CoverImportRequest request) {
		try { return items.importCoverFromUrl(id, request.revision(), request.url()); }
		catch (IllegalArgumentException error) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
	}
	public record CoverImportRequest(@jakarta.validation.constraints.NotNull Long revision,
		@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2048) String url) {}

	@DeleteMapping("/{id}/cover")
	public ItemView removeCover(@PathVariable String id, @RequestParam long revision) { return items.removeCover(id, revision); }

	@GetMapping(value = "/{id}/cover", produces = {"image/jpeg", "image/png"})
	public ResponseEntity<byte[]> cover(@PathVariable String id, @RequestParam(defaultValue = "false") boolean thumbnail) {
		CoverContent cover = items.readCover(id, thumbnail);
		return ResponseEntity.ok().contentType(cover.mediaType())
			.header("X-Content-Type-Options", "nosniff")
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.body(cover.bytes());
	}
}
