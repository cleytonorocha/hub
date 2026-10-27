package com.github.cleytonorocha.hub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class LocalApiSecurityConfiguration {
	@Bean
	SecurityFilterChain localApiSecurity(HttpSecurity http) throws Exception {
		return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/tasks/**"))
			.authorizeHttpRequests(requests -> requests.requestMatchers("/api/tasks/**").permitAll()
				.anyRequest().authenticated())
			.build();
	}
}
