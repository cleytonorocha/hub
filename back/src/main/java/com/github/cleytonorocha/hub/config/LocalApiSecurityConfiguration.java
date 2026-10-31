package com.github.cleytonorocha.hub.config;

import com.github.cleytonorocha.hub.security.HubUserDetailsService;
import com.github.cleytonorocha.hub.security.HubSessionFilter;
import com.github.cleytonorocha.hub.repository.HubUserRepository;
import com.github.cleytonorocha.hub.repository.PermissionProfileRepository;
import com.github.cleytonorocha.hub.service.AuthOwnershipMigration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;

@Configuration
@EnableMethodSecurity
public class LocalApiSecurityConfiguration {
	@Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
	@Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
	@Bean DaoAuthenticationProvider authenticationProvider(HubUserDetailsService users, PasswordEncoder encoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(encoder);
		return provider;
	}
	@Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception { return configuration.getAuthenticationManager(); }

	@Bean
	SecurityFilterChain localApiSecurity(HttpSecurity http, DaoAuthenticationProvider provider, SecurityContextRepository contexts,
			HubUserRepository users, PermissionProfileRepository profiles, AuthOwnershipMigration ownershipMigration) throws Exception {
		CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrf.setCookiePath("/");
		return http
			.authenticationProvider(provider)
			.csrf(config -> config.csrfTokenRepository(csrf))
			.addFilterBefore(new HubSessionFilter(users, profiles, ownershipMigration), AuthorizationFilter.class)
			.securityContext(context -> context.securityContextRepository(contexts))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
				.sessionFixation(fixation -> fixation.migrateSession()))
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, error) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
				.accessDeniedHandler((request, response, error) -> response.sendError(HttpServletResponse.SC_FORBIDDEN)))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/api/auth/csrf", "/api/auth/setup-state", "/api/auth/setup", "/api/auth/login").permitAll()
				.requestMatchers("/api/auth/**").authenticated()
				.requestMatchers("/api/**").authenticated()
				.anyRequest().permitAll())
			.build();
	}
}
