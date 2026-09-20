package com.questbook.tracking;

import com.questbook.storage.QuestSavedData;

import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * Access to the server's quest data.
 *
 * <p>Centralised so the "fetch the saved data" call appears once. Returned as an
 * {@link Optional} because the world is not guaranteed to be loaded — a packet
 * handler or a shutdown hook can run where it is not.
 */
public final class Quests {
	private Quests() {
	}

	public static Optional<QuestSavedData> find(MinecraftServer server) {
		if (server == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(server.getDataStorage().computeIfAbsent(QuestSavedData.TYPE));
	}
}
