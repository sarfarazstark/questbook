package com.questbook.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.questbook.QuestBook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Posts quest events to a Discord webhook.
 *
 * <p>Every post is off the server thread. A webhook is an outbound HTTP call
 * whose latency belongs to someone else's infrastructure; running it inline
 * would stall the tick loop, which is the one thing this mod must never do.
 * Failures are logged and dropped rather than retried — a missed Discord
 * message is worth less than a stuttering server.
 */
public final class DiscordNotifier {
	private DiscordNotifier() {
	}

	/** One client for the process lifetime; it pools the webhook connection. */
	private static volatile HttpClient client;

	private static HttpClient client() {
		HttpClient existing = client;

		if (existing == null) {
			synchronized (DiscordNotifier.class) {
				if (client == null) {
					client = HttpClient.newBuilder()
							.connectTimeout(Duration.ofSeconds(5))
							.build();
				}

				return client;
			}
		}

		return existing;
	}

	/** A quest's tasks all completed. */
	public static void questCompleted(String questName, int contributorCount) {
		if (!DiscordConfig.get().announceQuests()) {
			return;
		}

		post("Quest complete", questName + " — completed by " + contributorCount
				+ (contributorCount == 1 ? " contributor" : " contributors"), 0x43A047);
	}

	/** One task reached its count. */
	public static void taskCompleted(String playerName, String questName, String itemId, int count) {
		if (!DiscordConfig.get().announceTasks()) {
			return;
		}

		post("Task complete", playerName + " collected " + count + "x " + itemId + " in " + questName,
				0x1E88E5);
	}

	/** Fires a payload straight through, for {@code /questbook discord test}. */
	public static CompletableFuture<Boolean> test() {
		return post("Quest Book connected", "The webhook is working.", 0x00ACC1);
	}

	/** Posts an embed; resolves to whether the webhook accepted it. */
	private static CompletableFuture<Boolean> post(String title, String description, int colour) {
		DiscordConfig config = DiscordConfig.get();

		if (config.webhookUrl().isBlank()) {
			return CompletableFuture.completedFuture(false);
		}

		JsonObject embed = new JsonObject();
		embed.addProperty("title", title);
		embed.addProperty("description", description);
		// Discord takes a decimal integer, not the hex literal.
		embed.addProperty("color", colour);

		JsonArray embeds = new JsonArray();
		embeds.add(embed);

		JsonObject payload = new JsonObject();
		payload.add("embeds", embeds);

		HttpRequest request = HttpRequest.newBuilder(URI.create(config.webhookUrl()))
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(10))
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
				.build();

		return client().sendAsync(request, HttpResponse.BodyHandlers.discarding())
				.thenApply(response -> {
					int status = response.statusCode();

					// 204 is the success code for a webhook that does not wait.
					if (status < 200 || status >= 300) {
						QuestBook.LOGGER.warn("Discord webhook returned {}", status);
					}

					return status >= 200 && status < 300;
				})
				.exceptionally(failure -> {
					QuestBook.LOGGER.warn("Discord webhook failed: {}", failure.getMessage());

					return false;
				});
	}
}
