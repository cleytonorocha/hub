package com.github.cleytonorocha.hub;

import com.github.cleytonorocha.hub.config.LocalApiSecurityConfiguration;
import com.github.cleytonorocha.hub.controller.AuthController;
import com.github.cleytonorocha.hub.controller.VideoDownloadController;
import com.github.cleytonorocha.hub.controller.advice.google.ApiExceptionHandler;
import com.github.cleytonorocha.hub.entity.HubUser;
import com.github.cleytonorocha.hub.entity.PermissionProfile;
import com.github.cleytonorocha.hub.repository.HubUserRepository;
import com.github.cleytonorocha.hub.repository.PermissionProfileRepository;
import com.github.cleytonorocha.hub.security.HubPrincipal;
import com.github.cleytonorocha.hub.security.HubUserDetailsService;
import com.github.cleytonorocha.hub.security.LoginAttemptLimiter;
import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import com.github.cleytonorocha.hub.service.AuthService;
import com.github.cleytonorocha.hub.service.VideoDownloadFailure;
import com.github.cleytonorocha.hub.service.VideoDownloadService;
import com.github.cleytonorocha.hub.service.google.GoogleOAuthService;
import jakarta.servlet.http.Cookie;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real security chain and download HTTP contract without processes, network or a database. */
@WebMvcTest(controllers = { VideoDownloadController.class, AuthController.class },
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiExceptionHandler.class))
@Import(LocalApiSecurityConfiguration.class)
class VideoDownloadControllerTests {
    private static final String BODY = "{\"platform\":\"YOUTUBE\",\"url\":\"https://youtu.be/abcdefghijk\"}";
    @Autowired MockMvc mvc;
    @MockitoBean VideoDownloadService downloads;
    @MockitoBean GoogleOAuthService googleOAuth;
    @MockitoBean AuthService auth;
    @MockitoBean HubUserDetailsService userDetails;
    @MockitoBean HubUserRepository users;
    @MockitoBean PermissionProfileRepository profiles;
    @MockitoBean AuthOwnershipMigration migration;
    @MockitoBean LoginAttemptLimiter limiter;
    private HubPrincipal actor;

    @BeforeEach
    void sessionIsReady() {
        when(migration.isReady()).thenReturn(true);
        actor = account("VIDEO_DOWNLOAD");
    }

    private HubPrincipal account(String permissions) {
        PermissionProfile profile = new PermissionProfile("Teste", permissions, false);
        HubUser user = new HubUser("Test user", "test@example.invalid", "unused-test-hash", profile.getId());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
        return new HubPrincipal(user.getId(), user.getDisplayName(), user.getEmail(), user.getPasswordHash(), true,
            profile.getId(), profile.getName(), permissions);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor signedIn(HubPrincipal principal) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor realCsrf() throws Exception {
        return realCsrf(true, true);
    }

    // Exercise the actual cookie/XOR-token contract rather than replacing the shared filter's repository.
    private org.springframework.test.web.servlet.request.RequestPostProcessor realCsrf(boolean includeHeader, boolean validToken) throws Exception {
        MvcResult response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.token");
        String name = com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.headerName");
        Cookie cookie = response.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return servletRequest -> {
            servletRequest.setCookies(cookie);
            if (includeHeader) servletRequest.addHeader(name, validToken ? token : "invalid-token");
            return servletRequest;
        };
    }

    @Test
    void allRoutesRequireAuthentication() throws Exception {
        mvc.perform(post("/api/video-downloads").with(realCsrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/video-downloads/job")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/video-downloads/job/file")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/video-downloads/job").with(realCsrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(downloads);
    }

    @Test
    void allRoutesRequireVideoPermission() throws Exception {
        HubPrincipal reader = account("TASK_READ");
        mvc.perform(post("/api/video-downloads").with(signedIn(reader)).with(realCsrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/video-downloads/job").with(signedIn(reader))).andExpect(status().isForbidden());
        mvc.perform(get("/api/video-downloads/job/file").with(signedIn(reader))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/video-downloads/job").with(signedIn(reader)).with(realCsrf())).andExpect(status().isForbidden());
        verifyNoInteractions(downloads);
    }

    @Test
    void mutationsRejectMissingAndInvalidCsrf() throws Exception {
        mvc.perform(post("/api/video-downloads").with(signedIn(actor)).with(realCsrf(false, true)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/video-downloads").with(signedIn(actor)).with(realCsrf(true, false)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/video-downloads/job").with(signedIn(actor)).with(realCsrf(false, true))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/video-downloads/job").with(signedIn(actor)).with(realCsrf(true, false))).andExpect(status().isForbidden());
        verifyNoInteractions(downloads);
    }

    @Test
    void acceptsTheRawCookieTokenUsedByTheFrontend() throws Exception {
        MvcResult csrfResponse = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String headerName = com.jayway.jsonpath.JsonPath.read(csrfResponse.getResponse().getContentAsString(), "$.headerName");
        Cookie cookie = csrfResponse.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        when(downloads.create(eq(actor.id()), any())).thenReturn(new VideoDownloadService.Created("raw-token-job", VideoDownloadService.State.RUNNING, "/api/video-downloads/raw-token-job"));

        mvc.perform(post("/api/video-downloads").with(signedIn(actor)).cookie(cookie).header(headerName, cookie.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("raw-token-job"));
        verify(downloads).create(eq(actor.id()), any());
    }

    @Test
    void createsAsynchronouslyUsingTheExplicitCsrfJsonTokenAndDefaultsTo720() throws Exception {
        MvcResult csrfResponse = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(csrfResponse.getResponse().getContentAsString(), "$.token");
        String headerName = com.jayway.jsonpath.JsonPath.read(csrfResponse.getResponse().getContentAsString(), "$.headerName");
        Cookie cookie = csrfResponse.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        when(downloads.create(eq(actor.id()), any())).thenReturn(new VideoDownloadService.Created("job-123", VideoDownloadService.State.RUNNING, "/api/video-downloads/job-123"));

        mvc.perform(post("/api/video-downloads").with(signedIn(actor)).cookie(cookie).header(headerName, token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/video-downloads/job-123"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.id").value("job-123")).andExpect(jsonPath("$.state").value("RUNNING"));
        ArgumentCaptor<com.github.cleytonorocha.hub.dto.VideoDownloadRequest> request = ArgumentCaptor.forClass(com.github.cleytonorocha.hub.dto.VideoDownloadRequest.class);
        verify(downloads).create(eq(actor.id()), request.capture());
        assertThat(request.getValue().quality()).isEqualTo(720);
    }

    @Test
    void malformedPlatformsAndBlankUrlsAreRejectedBeforeAdmission() throws Exception {
        for (String body : new String[] { BODY.replace("YOUTUBE", "TIKTOK"), "{\"platform\":\"YOUTUBE\",\"url\":\"\"}", "{\"url\":\"https://youtu.be/abcdefghijk\"}" }) {
            mvc.perform(post("/api/video-downloads").with(signedIn(actor)).with(realCsrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(downloads);
    }

    @Test
    void statusReturnsOnlyThePublicMetadataAndUsesTheSessionOwner() throws Exception {
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        when(downloads.status(actor.id(), "job-123")).thenReturn(new VideoDownloadService.View("job-123", VideoDownloadService.State.READY, null,
            720, now, now.plusSeconds(600), "video.mp4", "video/mp4", 42L, 1080, 1920, 1080, true, "/api/video-downloads/job-123/file", null, 0L, false, false));
        mvc.perform(get("/api/video-downloads/job-123").with(signedIn(actor)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.effectiveQuality").value(1080)).andExpect(jsonPath("$.fallbackToOriginal").value(true))
            .andExpect(jsonPath("$.bytesTransferred").value(0))
            .andExpect(jsonPath("$.downloadUrl").value("/api/video-downloads/job-123/file"))
            .andExpect(jsonPath("$.path").doesNotExist()).andExpect(jsonPath("$.ownerId").doesNotExist());
        verify(downloads).status(actor.id(), "job-123");
    }

    @Test
    void ownerLookupFailuresStayNotFoundAndCapacityHasRetryAfter() throws Exception {
        when(downloads.status(actor.id(), "foreign")).thenThrow(new VideoDownloadFailure("NOT_FOUND", HttpStatus.NOT_FOUND, "Preparação não encontrada."));
        mvc.perform(get("/api/video-downloads/foreign").with(signedIn(actor))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        when(downloads.create(eq(actor.id()), any())).thenThrow(new VideoDownloadFailure("CAPACITY_LIMIT", HttpStatus.TOO_MANY_REQUESTS, "Servidor ocupado."));
        mvc.perform(post("/api/video-downloads").with(signedIn(actor)).with(realCsrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "5"))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("CAPACITY_LIMIT"));
    }

    @Test
    void cancellationUsesTheAuthenticatedOwner() throws Exception {
        mvc.perform(delete("/api/video-downloads/job-123").with(signedIn(actor)).with(realCsrf()))
            .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        verify(downloads).cancel(actor.id(), "job-123");
    }

    @Test
    void fileIsAnAsyncNativeAttachmentWithExactBytes() throws Exception {
        VideoDownloadService.Transfer transfer = mock(VideoDownloadService.Transfer.class);
        when(transfer.fileName()).thenReturn("video.mp4");
        when(transfer.contentType()).thenReturn("video/mp4");
        when(transfer.sizeBytes()).thenReturn(3L);
        when(downloads.claim(actor.id(), "job-123")).thenReturn(transfer);
        doAnswer(invocation -> { invocation.getArgument(1, OutputStream.class).write(new byte[] { 1, 2, 3 }); return null; })
            .when(downloads).stream(eq(transfer), any(OutputStream.class));

        MvcResult pending = mvc.perform(get("/api/video-downloads/job-123/file").with(signedIn(actor)))
            .andExpect(request().asyncStarted()).andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("Content-Length", "3")).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(content().contentType("video/mp4"))
            .andExpect(content().bytes(new byte[] { 1, 2, 3 }));
        verify(downloads).claim(actor.id(), "job-123");
    }
}
