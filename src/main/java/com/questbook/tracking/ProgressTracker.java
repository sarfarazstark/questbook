package com.questbook.tracking;

import com.questbook.QuestBook;
import com.questbook.data.Quest;
import com.questbook.data.QuestStore;
import com.questbook.data.Task;
import com.questbook.discord.DiscordNotifier;
import com.questbook.network.AdminActionHandler;
import com.questbook.network.QuestNetworking;
import com.questbook.storage.QuestSavedData;
import com.questbook.util.QuestText;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns gameplay events into task progress.
 *
 * <p>Server-authoritative: only the server calls in here, and only the task's
 * assignee can advance it. A pickup by anyone else is ignored, which is the
 * assignee rule taken literally — one task, one person's effort.
 */
public final class ProgressTracker {
	private ProgressTracker() {
	}

	/** Called from the pickup mixin once the inventory has accepted the stack. */
	public static void onPickup(ServerPlayer player, ItemStack stack) {
		record(player, stack);
	}

	/** Called from the craft-output mixin. */
	public static void onCraft(ServerPlayer player, ItemStack stack) {
		record(player, stack);
	}

	/**
	 * Adds {@code stack}'s count to every incomplete task of {@code player}'s that
	 * requires this item.
	 *
	 * <p>A player may hold several quests requiring the same item, so this walks all
	 * of them rather than stopping at the first match.
	 */
	private static void record(ServerPlayer player, ItemStack stack) {
		// ServerPlayer#getServer does not exist in 26.2; ServerLevel#getServer is public.
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		QuestSavedData data = Quests.find(server).orElse(null);

		if (data == null) {
			return;
		}

		String itemId = registryId(stack);

		if (itemId == null) {
			return;
		}

		UUID playerId = player.getUUID();
		int amount = stack.getCount();
		QuestStore store = data.store();
		QuestStore updated = store;
		List<Task> completedNow = new ArrayList<>();
		List<String> completedQuestNames = new ArrayList<>();

		for (Quest quest : store.quests()) {
			for (Task task : quest.tasks()) {
				// The assignee rule: nobody else's pickups count.
				if (!task.assignee().equals(playerId) || task.isComplete()) {
					continue;
				}

				if (!task.itemId().equals(itemId)) {
					continue;
				}

				Task advanced = task.advance(amount);
				updated = updated.updateTask(quest.id(), task.id(), t -> advanced);

				if (advanced.isComplete()) {
					completedNow.add(advanced);
					completedQuestNames.add(quest.name());
				}
			}
		}

		if (updated == store) {
			return;
		}

		data.setStore(updated);

		// Push the new progress to the client. Without this the server's data is
		// correct but the book keeps showing the last synced numbers, so a pickup
		// appears to do nothing until some unrelated action triggers a re-sync.
		QuestNetworking.syncAll(server);
		AdminActionHandler.syncAllAdmins(server, data);

		for (int i = 0; i < completedNow.size(); i++) {
			Task task = completedNow.get(i);
			data.recordTaskCompletion(task.assignee());
			announceTaskComplete(server, player, task, completedQuestNames.get(i));
		}

		announceCompletedQuests(server, store, updated);
	}

	/** Reports a single task reaching its count. */
	private static void announceTaskComplete(MinecraftServer server, ServerPlayer player, Task task,
			String questName) {
		server.getPlayerList().broadcastSystemMessage(
				QuestText.taskCompleted(player.getGameProfile().name(), task.itemId(), task.count()),
				false);
		DiscordNotifier.taskCompleted(player.getGameProfile().name(), questName, task.itemId(), task.count());
	}

	/**
	 * Reports any quest that became complete as a result of this update.
	 *
	 * <p>Compares before and after rather than re-scanning, so a quest already
	 * complete does not announce again on every later pickup.
	 */
	private static void announceCompletedQuests(MinecraftServer server, QuestStore before, QuestStore after) {
		for (Quest quest : after.quests()) {
			if (!quest.isComplete()) {
				continue;
			}

			boolean wasComplete = before.quest(quest.id()).map(Quest::isComplete).orElse(false);

			if (!wasComplete) {
				server.getPlayerList().broadcastSystemMessage(
						QuestText.questCompleted(quest.name()), false);

				// Discord too, or a configured webhook stayed silent for the one event
				// it is most likely to be wanted for. Contributor count is the number of
				// distinct assignees across the quest's tasks.
				int contributors = (int) quest.tasks().stream()
						.map(Task::assignee)
						.filter(id -> !id.equals(Task.UNASSIGNED))
						.distinct()
						.count();
				DiscordNotifier.questCompleted(quest.name(), contributors);
			}
		}
	}

	/** The registry id of {@code stack}'s item, or null if unregistered. */
	private static String registryId(ItemStack stack) {
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());

		if (id == null) {
			QuestBook.LOGGER.warn("Picked up unregistered item {}", stack.getItem());

			return null;
		}

		return id.toString();
	}

	/** Human-readable item name for chat, e.g. {@code minecraft:oak_log -> oak log}. */
	private static String itemName(String itemId) {
		String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		path = path.replace('_', ' ');

		return path.isEmpty() ? itemId : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

}
