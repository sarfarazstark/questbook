package com.questbook.network;

import com.questbook.QuestBook;
import com.questbook.data.Quest;
import com.questbook.data.Task;
import com.questbook.storage.QuestSavedData;
import com.questbook.tracking.Quests;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Handles a client's request to pin or unpin one of its tasks.
 *
 * <p>Validates ownership: a player may only change the pin on a task assigned to
 * them. The client is not trusted for that check.
 */
public final class PinTaskHandler {
	private PinTaskHandler() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PinTaskPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handle(context.player(), payload)));
	}

	private static void handle(ServerPlayer player, PinTaskPayload payload) {
		QuestSavedData data = Quests.find(player.level().getServer()).orElse(null);

		if (data == null) {
			return;
		}

		UUID playerId = player.getUUID();
		boolean changed = false;

		for (Quest quest : data.store().quests()) {
			for (Task task : quest.tasks()) {
				if (!task.id().equals(payload.taskId())) {
					continue;
				}

				// Only the assignee may pin. Silently ignore otherwise rather than
				// reporting an error, since a client should never send this.
				if (!task.assignee().equals(playerId)) {
					QuestBook.LOGGER.warn("{} tried to pin a task not assigned to them",
							player.getGameProfile().name());
					return;
				}

				data.setStore(data.store().updateTask(quest.id(), task.id(),
						t -> t.withPinned(payload.pinned())));
				changed = true;
				break;
			}

			if (changed) {
				break;
			}
		}

		if (changed) {
			// Send the new state straight back, so the book and HUD update at once
			// rather than waiting for the next unrelated sync.
			QuestSyncPayload.send(player, data);
		}
	}
}
