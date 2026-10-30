package com.github.cleytonorocha.hub.controller;

import com.github.cleytonorocha.hub.service.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class UserAdministrationController {
	private final AuthService auth;
	public UserAdministrationController(AuthService auth) { this.auth = auth; }
	@GetMapping("/users") public List<AuthService.SessionView> users() { return auth.listUsers(); }
	@PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) public AuthService.SessionView create(@Valid @RequestBody AuthService.CreateUserRequest request) { return auth.createUser(request); }
	@PutMapping("/users/{id}") public AuthService.SessionView update(@PathVariable String id, @Valid @RequestBody AuthService.UpdateUserRequest request) { return auth.updateUser(id, request); }
	@GetMapping("/profiles") public List<AuthService.ProfileView> profiles() { return auth.listProfiles(); }
	@PostMapping("/profiles") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('PROFILE_MANAGE')") public AuthService.ProfileView createProfile(@Valid @RequestBody AuthService.ProfileRequest request) { return auth.createProfile(request); }
	@PutMapping("/profiles/{id}") @PreAuthorize("hasAuthority('PROFILE_MANAGE')") public AuthService.ProfileView updateProfile(@PathVariable String id, @Valid @RequestBody AuthService.ProfileRequest request) { return auth.updateProfile(id, request); }
}
