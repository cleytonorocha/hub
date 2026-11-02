package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.service.AuthService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserAdministrationControllerTests extends AuthenticatedHubMvcTestSupport {
	private static final String TEST_PROFILE_ID = "a0000000-0000-4000-8000-000000000002";

	@Autowired ObjectMapper mapper;
	@MockitoSpyBean AuthService auth;

	@Test
	void profileMutationsRequireBothAdministrativeAuthorities() throws Exception {
		String name = "Perfil negado " + UUID.randomUUID();
		byte[] request = profileRequest(name);
		for (String permissions : List.of("TASK_READ", "USER_MANAGE", "PROFILE_MANAGE")) {
			HubAuthenticatedMvc client = mvcWithPermissions(permissions);
			client.perform(post("/api/admin/profiles").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isForbidden());
			client.perform(put("/api/admin/profiles/" + TEST_PROFILE_ID).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isForbidden());
		}
		verify(auth, never()).createProfile(any(AuthService.ProfileRequest.class));
		verify(auth, never()).updateProfile(any(String.class), any(AuthService.ProfileRequest.class));
	}

	@Test
	void bothAuthoritiesCanCreateAndUpdateProfiles() throws Exception {
		String name = "Perfil permitido " + UUID.randomUUID();
		HubAuthenticatedMvc client = mvcWithPermissions("USER_MANAGE,PROFILE_MANAGE");
		var created = client.perform(post("/api/admin/profiles").contentType(MediaType.APPLICATION_JSON).content(profileRequest(name)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name)).andReturn();
		String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		String updatedName = name + " atualizado";
		client.perform(put("/api/admin/profiles/" + id).contentType(MediaType.APPLICATION_JSON).content(profileRequest(updatedName)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.name").value(updatedName));
	}

	private byte[] profileRequest(String name) throws Exception {
		return mapper.writeValueAsBytes(Map.of("name", name, "permissions", List.of("TASK_READ")));
	}
}