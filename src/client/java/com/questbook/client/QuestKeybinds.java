package com.questbook.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.questbook.QuestBook;
import com.questbook.client.gui.AdminQuestScreen;
import com.questbook.client.gui.QuestBookScreen;
import com.questbook.network.AdminActionPayload;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers and polls the keybinds:
 * - J: player quest book
 * - K: admin quest editor
 */
public final class QuestKeybinds {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(QuestBook.MOD_ID, "main"));

	private static KeyMapping openBook;
	private static KeyMapping openAdmin;

	private QuestKeybinds() {
	}

	public static void register() {
		openBook = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + QuestBook.MOD_ID + ".open_book",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_J,
				CATEGORY));

		openAdmin = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + QuestBook.MOD_ID + ".open_admin",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(QuestKeybinds::onTick);
	}

	private static void onTick(Minecraft client) {
		if (client.player == null) {
			return;
		}

		if (openBook != null) {
			while (openBook.consumeClick()) {
				if (!QuestBookScreen.isOpen() && !AdminQuestScreen.isOpen()) {
					client.setScreenAndShow(new QuestBookScreen());
				}
			}
		}

		if (openAdmin != null) {
			while (openAdmin.consumeClick()) {
				if (!Commands.LEVEL_GAMEMASTERS.check(client.player.permissions())) {
					continue; // Silently ignore for non-OP players
				}
				if (!QuestBookScreen.isOpen() && !AdminQuestScreen.isOpen()) {
					if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
						ClientPlayNetworking.send(AdminActionPayload.requestSync());
					}
					client.setScreenAndShow(new AdminQuestScreen());
				}
			}
		}
	}
}
