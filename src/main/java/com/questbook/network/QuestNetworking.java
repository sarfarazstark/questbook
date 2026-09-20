package com.questbook.network;

import com.questbook.QuestBook;
import com.questbook.storage.QuestSavedData;
import com.questbook.tracking.Quests;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.level.ServerPlayer;

/**
 * Registers the sync payloads and handlers for client and server.
 */
public final class QuestNetworking {
	private QuestNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(QuestSyncPayload.TYPE, QuestSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PinTaskPayload.TYPE, PinTaskPayload.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(AdminSyncPayload.TYPE, AdminSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AdminActionPayload.TYPE, AdminActionPayload.CODEC);

		PinTaskHandler.register();
		AdminActionHandler.register();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.getPlayer()));
	}

	/** Sends one player their tasks. */
	public static void sync(ServerPlayer player) {
		Quests.find(player.level().getServer()).ifPresent(data ->
				QuestSyncPayload.send(player, data));
	}

	/** Sends every player their tasks, after a change an admin just made. */
	public static void syncAll(net.minecraft.server.MinecraftServer server) {
		QuestSavedData data = Quests.find(server).orElse(null);

		if (data == null) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, QuestSyncPayload.TYPE)) {
				QuestSyncPayload.send(player, data);
			}
		}

		QuestBook.LOGGER.debug("Synced quests to {} players", server.getPlayerList().getPlayers().size());
	}
}
