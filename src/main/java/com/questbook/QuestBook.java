package com.questbook;

import com.questbook.command.QuestCommands;
import com.questbook.network.QuestNetworking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entrypoint: registers commands and networking. */
public class QuestBook implements ModInitializer {
	public static final String MOD_ID = "questbook";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		QuestNetworking.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
				QuestCommands.register(dispatcher));

		LOGGER.info("Quest Book loaded");
	}
}
