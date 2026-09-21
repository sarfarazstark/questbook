package com.questbook.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import com.questbook.QuestBook;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The mod's Discord settings, as a JSON file under the server's config directory.
 *
 * <p>Hand-written rather than Cloth Config because a dedicated server never opens
 * the config GUI, and Gson ships with Minecraft so this adds no dependency.
 *
 * <p>Defaults are "off": a fresh install must never post anywhere, so a missing
 * or unreadable file yields a disabled config rather than an error.
 */
public final class DiscordConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "questbook-discord.json";

	private static DiscordConfig cached;

	private String webhookUrl = "";
	private boolean announceQuests = true;
	private boolean announceTasks = false;

	public static synchronized DiscordConfig get() {
		if (cached == null) {
			cached = read();
		}

		return cached;
	}

	private static DiscordConfig read() {
		Path path = path();

		if (!Files.isRegularFile(path)) {
			return new DiscordConfig();
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			DiscordConfig config = GSON.fromJson(reader, DiscordConfig.class);

			return config == null ? new DiscordConfig() : config;
		} catch (IOException | JsonSyntaxException failure) {
			QuestBook.LOGGER.warn("Could not read {}: {}", path, failure.getMessage());

			return new DiscordConfig();
		}
	}

	/** Writes the current values back to disk. */
	public synchronized void save() {
		Path path = path();

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException failure) {
			QuestBook.LOGGER.warn("Could not write {}: {}", path, failure.getMessage());
		}
	}

	/**
	 * The config file's location: {@code <working directory>/config/<name>}.
	 *
	 * <p>The working directory is the server directory for a dedicated server and
	 * for the Gradle dev run, so no path is threaded through from the server.
	 */
	private static Path path() {
		return Paths.get("").toAbsolutePath().normalize().resolve("config").resolve(FILE_NAME);
	}

	/** Whether a webhook is configured and at least one event is enabled. */
	public boolean enabled() {
		return !webhookUrl.isBlank() && (announceQuests || announceTasks);
	}

	public String webhookUrl() {
		return webhookUrl;
	}

	public boolean announceQuests() {
		return announceQuests;
	}

	public boolean announceTasks() {
		return announceTasks;
	}

	public void setWebhookUrl(String url) {
		this.webhookUrl = url == null ? "" : url.trim();
	}

	public void setAnnounceQuests(boolean value) {
		this.announceQuests = value;
	}

	public void setAnnounceTasks(boolean value) {
		this.announceTasks = value;
	}

	/** One-line summary, for {@code /questbook discord status}. */
	public String describe() {
		if (webhookUrl.isBlank()) {
			return "no webhook set";
		}

		return (enabled() ? "enabled" : "all events off")
				+ " quests=" + announceQuests + " tasks=" + announceTasks;
	}
}
