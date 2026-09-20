package com.questbook.client;

import com.questbook.network.AdminSyncPayload;

import java.util.List;

/**
 * Client-side state holding all quests and online players for the admin screen.
 */
public final class ClientAdminState {
	private static List<AdminSyncPayload.AdminQuestEntry> quests = List.of();
	private static List<AdminSyncPayload.PlayerEntry> players = List.of();

	private ClientAdminState() {
	}

	public static void apply(AdminSyncPayload payload) {
		quests = List.copyOf(payload.quests());
		players = List.copyOf(payload.players());
	}

	public static void clear() {
		quests = List.of();
		players = List.of();
	}

	public static List<AdminSyncPayload.AdminQuestEntry> quests() {
		return quests;
	}

	public static List<AdminSyncPayload.PlayerEntry> players() {
		return players;
	}

	public static boolean isEmpty() {
		return quests.isEmpty();
	}
}
