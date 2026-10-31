package com.github.cleytonorocha.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.cleytonorocha.hub.repository.ReadingItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
class ReadingItemControllerTests extends AuthenticatedHubMvcTestSupport {
	@Autowired ReadingItemRepository items;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper mapper;

	@BeforeEach
	void clearItems() {
		items.deleteAll();
		jdbc.update("UPDATE hub_reading_collection_state SET revision = 0 WHERE state_id = 1");
	}

	@Test
	void createsBookWithoutUrlAndSearchesIgnoringAccentsWithPagination() throws Exception {
		create("Ficção científica", "BOOK", null);
		create("Ficção brasileira", "BOOK", null);
		mvc.perform(get("/api/reading-items").param("q", "FICCAO").param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.content.length()").value(1));
	}

	@Test
	void rejectsExternalSchemesAndUserInfoWithoutCreatingAnItem() throws Exception {
		create("JavaScript URL", "ARTICLE", "javascript:alert(1)").andExpect(status().isBadRequest());
		create("Credential URL", "ARTICLE", "https://user:secret@example.com/page").andExpect(status().isBadRequest());
		assertThat(items.count()).isZero();
	}

	@Test
	void revisionPreventsSilentOverwriteAndArchivedItemsRequireExplicitFilter() throws Exception {
		MvcResult created = create("Leitura", "BOOK", null).andExpect(status().isCreated()).andReturn();
		JsonNode item = mapper.readTree(created.getResponse().getContentAsString());
		String id = item.get("id").asText();
		long revision = item.get("revision").asLong();
		String update = "{\"title\":\"Leitura\",\"type\":\"BOOK\",\"authorOrSource\":null,\"url\":null,\"status\":\"IN_PROGRESS\",\"progressMarker\":\"Capítulo 2\",\"note\":\"Nota\",\"tags\":[\"Pessoal\"],\"archived\":false,\"revision\":" + revision + "}";
		MvcResult saved = mvc.perform(put("/api/reading-items/{id}", id).contentType(MediaType.APPLICATION_JSON).content(update))
			.andExpect(status().isOk()).andExpect(jsonPath("$.progressMarker").value("Capítulo 2")).andReturn();
		long nextRevision = mapper.readTree(saved.getResponse().getContentAsString()).get("revision").asLong();
		mvc.perform(put("/api/reading-items/{id}", id).contentType(MediaType.APPLICATION_JSON).content(update))
			.andExpect(status().isConflict());
		String archive = update.replace("\"archived\":false", "\"archived\":true")
			.replace("\"revision\":" + revision, "\"revision\":" + nextRevision);
		mvc.perform(put("/api/reading-items/{id}", id).contentType(MediaType.APPLICATION_JSON).content(archive))
			.andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
		mvc.perform(get("/api/reading-items")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/reading-items").param("archived", "true")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
	}

	private org.springframework.test.web.servlet.ResultActions create(String title, String type, String url) throws Exception {
		String urlJson = url == null ? "null" : mapper.writeValueAsString(url);
		return mvc.perform(post("/api/reading-items").contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":" + mapper.writeValueAsString(title) + ",\"type\":\"" + type + "\",\"url\":" + urlJson + ",\"tags\":[\"Trabalho\",\" trabalho \"]}"));
	}
}
