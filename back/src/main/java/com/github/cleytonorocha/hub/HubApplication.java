package com.github.cleytonorocha.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HubApplication {

	public static void main(String[] args) {
		prepareDatabaseDirectory(args);
		SpringApplication.run(HubApplication.class, args);
	}

	private static void prepareDatabaseDirectory(String[] args) {
		String configuredRoot = System.getProperty("hub.data.root", System.getenv("HUB_DATA_ROOT"));
		for (String arg : args) if (arg.startsWith("--hub.data.root=")) configuredRoot = arg.substring("--hub.data.root=".length());
		java.nio.file.Path root;
		if (configuredRoot != null && !configuredRoot.isBlank()) root = java.nio.file.Path.of(configuredRoot);
		else {
			String localAppData = System.getenv("LOCALAPPDATA");
			java.nio.file.Path base = localAppData == null || localAppData.isBlank()
				? java.nio.file.Path.of(System.getProperty("user.home"), "AppData", "Local")
				: java.nio.file.Path.of(localAppData);
			root = base.resolve("Hub");
		}
		try { java.nio.file.Files.createDirectories(root.resolve("database")); }
		catch (java.io.IOException error) { throw new IllegalStateException("Não foi possível preparar a pasta do banco local do Hub.", error); }
	}

}
