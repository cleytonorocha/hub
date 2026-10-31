package com.github.cleytonorocha.hub.service;

import com.github.cleytonorocha.hub.dto.ReadingItemDtos.CreateRequest;
import com.github.cleytonorocha.hub.dto.ReadingItemDtos.ItemView;
import com.github.cleytonorocha.hub.dto.ReadingItemDtos.SearchResponse;
import com.github.cleytonorocha.hub.dto.ReadingItemDtos.AuthorView;
import com.github.cleytonorocha.hub.dto.ReadingItemDtos.UpdateRequest;
import com.github.cleytonorocha.hub.entity.ReadingItem;
import com.github.cleytonorocha.hub.entity.ReadingItemAuthorLink;
import com.github.cleytonorocha.hub.entity.ReadingAuthor;
import com.github.cleytonorocha.hub.entity.ReadingItemType;
import com.github.cleytonorocha.hub.entity.ReadingStatus;
import com.github.cleytonorocha.hub.repository.ReadingItemRepository;
import com.github.cleytonorocha.hub.repository.ReadingAuthorRepository;
import com.github.cleytonorocha.hub.security.AuthContext;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReadingItemService {
	public static final int MAX_PAGE_SIZE = 100;
	public static final int MAX_QUERY_LENGTH = 200;
	public static final int MAX_TAGS = 20;
	public static final int MAX_TAG_LENGTH = 64;
	public static final int MAX_TOTAL_PAGES = 100_000;
	private static final Locale TAG_LOCALE = Locale.forLanguageTag("pt-BR");
	private final ReadingItemRepository items;
	private final ReadingCollectionRevision collectionRevision;
	private final ReadingCoverStorageService covers;
	private final ReadingAuthorRepository authors;
	private final ReadingItemAssociationService associations;
	private final GoogleCoverImportService googleCovers;

	public ReadingItemService(ReadingItemRepository items, ReadingCollectionRevision collectionRevision, ReadingCoverStorageService covers, ReadingAuthorRepository authors, ReadingItemAssociationService associations, GoogleCoverImportService googleCovers) {
		this.items = items;
		this.collectionRevision = collectionRevision;
		this.covers = covers;
		this.authors = authors;
		this.associations = associations;
		this.googleCovers = googleCovers;
	}

	@Transactional(readOnly = true)
	public SearchResponse search(String query, String status, String type, boolean archived, String authorId, int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw badRequest("A página ou o tamanho dos resultados é inválido.");
		String q = query == null ? "" : query.strip();
		if (q.length() > MAX_QUERY_LENGTH) throw badRequest("A busca excede 200 caracteres.");
		ReadingStatus statusFilter = parseStatus(status);
		ReadingItemType typeFilter = parseType(type);
		if (authorId != null && !authorId.isBlank() && authors.findById(authorId).isEmpty()) throw badRequest("O autor selecionado não existe.");
		String normalized = TaskSearchNormalization.normalize(q);
		Specification<ReadingItem> specification = (root, criteria, builder) -> {
			Predicate predicate = builder.equal(root.get("archived"), archived);
			if (!normalized.isEmpty()) {
				String pattern = "%" + escapeLike(normalized) + "%";
				Predicate title = builder.like(normalize(builder, root.get("title")), pattern, '\\');
				Predicate source = builder.like(normalize(builder, root.get("authorOrSource")), pattern, '\\');
				Predicate note = builder.like(normalize(builder, root.get("note")), pattern, '\\');
				Subquery<Integer> authorMatch = criteria.subquery(Integer.class);
				Root<ReadingItemAuthorLink> link = authorMatch.from(ReadingItemAuthorLink.class);
				Root<ReadingAuthor> authorEntity = authorMatch.from(ReadingAuthor.class);
				authorMatch.select(builder.literal(1)).where(
					builder.equal(link.get("readingItemId"), root.get("id")),
					builder.equal(link.get("authorId"), authorEntity.get("id")),
					builder.like(normalize(builder, authorEntity.get("name")), pattern, '\\'));
				Predicate author = builder.exists(authorMatch);
				predicate = builder.and(predicate, builder.or(title, source, note, author));
			}
			if (statusFilter != null) predicate = builder.and(predicate, builder.equal(root.get("status"), statusFilter.name()));
			if (typeFilter != null) predicate = builder.and(predicate, builder.equal(root.get("type"), typeFilter.name()));
			if (authorId != null && !authorId.isBlank()) {
				Subquery<Integer> authorFilter = criteria.subquery(Integer.class);
				Root<ReadingItemAuthorLink> link = authorFilter.from(ReadingItemAuthorLink.class);
				authorFilter.select(builder.literal(1)).where(
					builder.equal(link.get("readingItemId"), root.get("id")), builder.equal(link.get("authorId"), authorId));
				predicate = builder.and(predicate, builder.exists(authorFilter));
			}
			return predicate;
		};
		Sort sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"));
		Page<ReadingItem> result = items.findAll(specification, PageRequest.of(page, size, sort));
		List<ReadingItem> hydrated = result.isEmpty() ? List.of() : items.findAllByIdIn(result.getContent().stream().map(ReadingItem::getId).toList());
		associations.populate(hydrated);
		Map<String, ReadingItem> hydratedById = new LinkedHashMap<>();
		for (ReadingItem item : hydrated) hydratedById.putIfAbsent(item.getId(), item);
		List<ItemView> content = result.getContent().stream().map(item -> ItemView.from(hydratedById.get(item.getId()))).toList();
		return new SearchResponse(content, result.getNumber(),
			result.getSize(), result.getTotalElements(), result.getTotalPages());
	}

	@Transactional(readOnly = true)
	public List<AuthorView> listAuthors() { return authors.findAllByOrderByNormalizedNameAscIdAsc().stream().map(AuthorView::from).toList(); }

	@Transactional
	public AuthorView createAuthor(String name) {
		if (name == null || name.isBlank() || name.strip().length() > 160) throw badRequest("Informe um nome de autor com até 160 caracteres.");
		collectionRevision.lock();
		String normalized = ReadingAuthor.normalize(name);
		if (authors.existsByNormalizedName(normalized)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um autor com esse nome.");
		ReadingAuthor saved = authors.saveAndFlush(new ReadingAuthor(name));
		collectionRevision.increment();
		return AuthorView.from(saved);
	}

	@Transactional(readOnly = true)
	public ItemView get(String id) { return ItemView.from(find(id)); }

	@Transactional
	public ItemView create(CreateRequest request) {
		collectionRevision.lock();
		PageProgress pageProgress = validatePageProgress(request.type(), pageValue(request.totalPages(), "total de páginas"), pageValue(request.currentPage(), "página atual"));
		String url = normalizeUrl(request.url());
		ReadingItem item = new ReadingItem(request.title().strip(), request.type().name(), optional(request.authorOrSource()),
			url, (request.status() == null ? ReadingStatus.TO_READ : request.status()).name(),
			markerValue(request.progressMarker()), pageProgress.totalPages(), pageProgress.currentPage(), noteValue(request.note()), normalizeTags(request.tags()), Instant.now());
		ReadingItem created = items.saveAndFlush(item);
		created.replaceAuthors(resolveAuthors(request.authorIds(), request.type()), Instant.now());
		created = items.saveAndFlush(created);
		associations.save(created);
		collectionRevision.increment();
		return ItemView.from(created);
	}

	@Transactional
	public ItemView update(String id, UpdateRequest request) {
		collectionRevision.lock();
		ReadingItem item = find(id);
		if (Boolean.TRUE.equals(request.archived()) != item.isArchived() && !AuthContext.hasPermission("READING_ARCHIVE")) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu perfil não permite arquivar ou restaurar conteúdo.");
		}
		if (request.revision() == null || item.getRevision() != request.revision()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Este conteúdo foi alterado em outra janela. Recarregue-o antes de salvar.");
		}
		Integer totalPages = Boolean.TRUE.equals(request.pageProgressProvided()) ? pageValue(request.totalPages(), "total de páginas") : item.getTotalPages();
		Integer currentPage = Boolean.TRUE.equals(request.pageProgressProvided()) ? pageValue(request.currentPage(), "página atual") : item.getCurrentPage();
		PageProgress pageProgress = validatePageProgress(request.type(), totalPages, currentPage);
		item.update(request.title().strip(), request.type().name(), optional(request.authorOrSource()), normalizeUrl(request.url()),
			request.status().name(), markerValue(request.progressMarker()), pageProgress.totalPages(), pageProgress.currentPage(), noteValue(request.note()), normalizeTags(request.tags()),
			Boolean.TRUE.equals(request.archived()), Instant.now());
		if (request.authorIds() != null || request.type() != ReadingItemType.BOOK) item.replaceAuthors(resolveAuthors(request.authorIds(), request.type()), Instant.now());
		ReadingItem updated = items.saveAndFlush(item);
		associations.save(updated);
		collectionRevision.increment();
		return ItemView.from(updated);
	}

	private java.util.Set<ReadingAuthor> resolveAuthors(List<String> ids, ReadingItemType type) {
		List<String> requested = ids == null ? List.of() : ids.stream().distinct().toList();
		if (requested.size() > 20) throw badRequest("Associe até 20 autores a um livro.");
		if (type != ReadingItemType.BOOK && !requested.isEmpty()) throw badRequest("Autores do catálogo podem ser associados somente a livros.");
		List<ReadingAuthor> found = requested.isEmpty() ? List.of() : authors.findAllById(requested);
		if (found.size() != requested.size()) throw badRequest("Um ou mais autores selecionados não existem.");
		return new java.util.LinkedHashSet<>(found);
	}

	@Transactional
	public ItemView uploadCover(String id, long expectedRevision, MultipartFile file) {
		collectionRevision.lock();
		ReadingItem item = find(id);
		checkRevision(item, expectedRevision);
		String ownerId = AuthContext.userId();
		ReadingCoverStorageService.StoredCover stored = covers.store(ownerId, file);
		String previous = item.getCoverImageName();
		try {
			item.updateCoverImage(stored.name(), stored.sha256(), null, Instant.now());
			ReadingItem saved = items.saveAndFlush(item);
			collectionRevision.increment();
			cleanupOnCompletion(ownerId, stored.name(), previous);
			return ItemView.from(saved);
		} catch (RuntimeException error) {
			covers.delete(ownerId, stored.name());
			throw error;
		}
	}

	@Transactional
	public ItemView importCoverFromUrl(String id, long expectedRevision, String sourceUrl) {
		collectionRevision.lock();
		ReadingItem item = find(id);
		checkRevision(item, expectedRevision);
		if (!"BOOK".equals(item.getType())) throw badRequest("A importação de capa por link está disponível somente para livros.");
		if (sourceUrl == null || sourceUrl.isBlank() || sourceUrl.strip().length() > 2048) throw badRequest("Informe um link de imagem válido.");
		String ownerId = AuthContext.userId();
		ReadingCoverStorageService.StoredCover stored = covers.store(ownerId, googleCovers.fetch(sourceUrl.strip()));
		String previous = item.getCoverImageName();
		try {
			item.updateCoverImage(stored.name(), stored.sha256(), sourceUrl.strip(), Instant.now());
			ReadingItem saved = items.saveAndFlush(item);
			collectionRevision.increment();
			cleanupOnCompletion(ownerId, stored.name(), previous);
			return ItemView.from(saved);
		} catch (RuntimeException error) {
			covers.delete(ownerId, stored.name());
			throw error;
		}
	}

	@Transactional
	public ItemView removeCover(String id, long expectedRevision) {
		collectionRevision.lock();
		ReadingItem item = find(id);
		checkRevision(item, expectedRevision);
		String previous = item.getCoverImageName();
		String ownerId = AuthContext.userId();
		if (previous == null) return ItemView.from(item);
		item.updateCoverImage(null, null, null, Instant.now());
		ReadingItem saved = items.saveAndFlush(item);
		collectionRevision.increment();
		cleanupOnCompletion(ownerId, null, previous);
		return ItemView.from(saved);
	}

	@Transactional(readOnly = true)
	public CoverContent readCover(String id, boolean thumbnail) {
		ReadingItem item = find(id);
		if (item.getCoverImageName() == null || item.getCoverImageSha256() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Este conteúdo não possui imagem de capa.");
		try {
			byte[] bytes = covers.read(AuthContext.userId(), item.getCoverImageName(), thumbnail);
			if (!thumbnail && !java.security.MessageDigest.isEqual(
				item.getCoverImageSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
				sha256(bytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "A imagem de capa não passou na verificação de integridade.");
			}
			return new CoverContent(bytes, covers.mediaType(item.getCoverImageName()));
		} catch (ReadingCoverStorageService.CoverNotFoundException error) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "A imagem de capa não está disponível.");
		}
	}

	private void checkRevision(ReadingItem item, long expectedRevision) {
		if (item.getRevision() != expectedRevision) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Este conteúdo foi alterado em outra janela. Recarregue-o antes de salvar.");
		}
	}

	private void cleanupOnCompletion(String ownerId, String added, String previous) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override public void afterCompletion(int status) {
				if (status == STATUS_COMMITTED) {
					if (previous != null && !previous.equals(added)) covers.delete(ownerId, previous);
				} else if (added != null) covers.delete(ownerId, added);
			}
		});
	}

	private String sha256(byte[] bytes) {
		try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.", impossible); }
	}

	private ReadingItem find(String id) {
		ReadingItem item = items.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conteúdo não encontrado."));
		associations.populate(List.of(item));
		return item;
	}

	private List<String> normalizeTags(List<String> input) {
		if (input == null) return List.of();
		if (input.size() > MAX_TAGS) throw badRequest("Use até 20 etiquetas por conteúdo.");
		Map<String, String> unique = new LinkedHashMap<>();
		for (String raw : input) {
			if (raw == null || raw.isBlank()) throw badRequest("As etiquetas não podem ficar vazias.");
			String value = raw.strip();
			if (value.length() > MAX_TAG_LENGTH) throw badRequest("Cada etiqueta pode ter até 64 caracteres.");
			unique.putIfAbsent(value.toLowerCase(TAG_LOCALE), value);
		}
		return new ArrayList<>(unique.values());
	}

	private String normalizeUrl(String value) {
		String url = optional(value);
		if (url == null) return null;
		if (url.length() > 2048) throw badRequest("O link pode ter até 2.048 caracteres.");
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException error) { throw badRequest("O endereço web é inválido."); }
		String scheme = uri.getScheme();
		if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
			|| !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
			throw badRequest("Informe um endereço web completo começando com http:// ou https://.");
		}
		return url;
	}

	private PageProgress validatePageProgress(ReadingItemType type, Integer totalPages, Integer currentPage) {
		if (type != ReadingItemType.BOOK) {
			if (totalPages != null || currentPage != null) throw badRequest("O progresso por páginas está disponível somente para livros.");
			return new PageProgress(null, null);
		}
		if (totalPages == null) {
			if (currentPage != null) throw badRequest("Informe o total de páginas para registrar a página atual.");
			return new PageProgress(null, null);
		}
		if (totalPages < 1 || totalPages > MAX_TOTAL_PAGES) throw badRequest("O total de páginas deve estar entre 1 e 100.000.");
		int page = currentPage == null ? 0 : currentPage;
		if (page < 0 || page > totalPages) throw badRequest("A página atual deve estar entre 0 e o total de páginas.");
		return new PageProgress(totalPages, page);
	}

	private Integer pageValue(tools.jackson.databind.JsonNode value, String label) {
		if (value == null || value.isNull()) return null;
		if (!value.isIntegralNumber() || !value.canConvertToInt()) throw badRequest("O campo " + label + " precisa ser um número inteiro.");
		return value.intValue();
	}

	private ReadingStatus parseStatus(String value) {
		if (!StringUtils.hasText(value)) return null;
		try { return ReadingStatus.valueOf(value); }
		catch (IllegalArgumentException error) { throw badRequest("O estado de leitura é inválido."); }
	}

	private ReadingItemType parseType(String value) {
		if (!StringUtils.hasText(value)) return null;
		try { return ReadingItemType.valueOf(value); }
		catch (IllegalArgumentException error) { throw badRequest("O tipo de conteúdo é inválido."); }
	}

	private static Expression<String> normalize(jakarta.persistence.criteria.CriteriaBuilder builder, Expression<String> value) {
		return builder.function("HUB_NORMALIZE_SEARCH", String.class, value);
	}
	private static String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
	private static String optional(String value) { return value == null || value.isBlank() ? null : value.strip(); }
	private static String markerValue(String value) { return value == null ? "" : value.strip(); }
	private static String noteValue(String value) { return value == null ? "" : value; }
	private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
	public record CoverContent(byte[] bytes, MediaType mediaType) {}
	private record PageProgress(Integer totalPages, Integer currentPage) {}
}
