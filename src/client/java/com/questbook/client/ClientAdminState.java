package com.questbook.client;

import com.questbook.network.AdminSyncPayload;

import java.util.List;

/**
 * Client-side state holding all quests and online players for the admin screen.
 */
public final class ClientAdminState {
	private static List<AdminSyncPayload.AdminQuestEntry> quests = List.of();
	private static List<AdminSyncPayload.PlayerEntry> players = List.of();
	private static boolean mayEdit = false;

	private ClientAdminState() {
	}

	public static void apply(AdminSyncPayload payload) {
		quests = List.copyOf(payload.quests());
		players = List.copyOf(payload.players());
		mayEdit = payload.mayEdit();
	}

	public static void clear() {
		quests = List.of();
		players = List.of();
		mayEdit = false;
	}

	public static List<AdminSyncPayload.AdminQuestEntry> quests() {
		return quests;
	}

	public static List<AdminSyncPayload.PlayerEntry> players() {
		return players;
	}

	/**
	 * Whether the server considers this player able to edit quests.
	 *
	 * <p>Drives whether the editor opens at all. The server re-checks every action, so
	 * this only decides whether to show a screen whose buttons would all be refused.
	 */
	public static boolean mayEdit() {
		return mayEdit;
	}

	public static boolean isEmpty() {
		return quests.isEmpty();
	}
}
