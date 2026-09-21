package com.questbook.client;

import com.questbook.QuestBook;
import com.questbook.client.hud.QuestHud;
import com.questbook.network.AdminSyncPayload;
import com.questbook.network.QuestSyncPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.resources.Identifier;

/** Client entrypoint: receives syncs and registers the book keybind. */
public class QuestBookClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(QuestSyncPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					ClientQuestState.apply(payload);
					int tasks = payload.quests().stream()
							.mapToInt(quest -> quest.tasks().size())
							.sum();
					QuestBook.LOGGER.info("Received {} quest(s), {} task(s) from server",
							payload.quests().size(), tasks);
				}));

		ClientPlayNetworking.registerGlobalReceiver(AdminSyncPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					ClientAdminState.apply(payload);
					QuestBook.LOGGER.info("Received admin data: {} quest(s), {} player(s)",
							payload.quests().size(), payload.players().size());
				}));

		// Drop the cached tasks on disconnect so stale state does not leak.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientQuestState.clear();
			ClientAdminState.clear();
		});

		HudElementRegistry.attachElementAfter(
				VanillaHudElements.MISC_OVERLAYS,
				Identifier.fromNamespaceAndPath(QuestBook.MOD_ID, "quest_hud"),
				QuestHud::render);

		QuestKeybinds.register();
		EditorDebugDriver.register();
	}
}
