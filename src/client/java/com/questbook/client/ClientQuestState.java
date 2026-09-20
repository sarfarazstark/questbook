package com.questbook.client;

import com.questbook.network.QuestSyncPayload;

import java.util.List;

/**
 * The client's copy of this player's tasks.
 *
 * <p>Holds only what the server sent, which is only this player's assignments.
 * The client never computes progress; it renders what it was given and waits for
 * the next sync.
 */
public final class ClientQuestState {
	private static List<QuestSyncPayload.QuestEntry> quests = List.of();

	private ClientQuestState() {
	}

	public static void apply(QuestSyncPayload payload) {
		quests = List.copyOf(payload.quests());
	}

	public static void clear() {
		quests = List.of();
	}

	public static List<QuestSyncPayload.QuestEntry> quests() {
		return quests;
	}

	/** Tasks this player pinned, across every quest, in list order. */
	public static List<QuestSyncPayload.TaskEntry> pinnedTasks() {
		return quests.stream()
				.flatMap(quest -> quest.tasks().stream())
				.filter(QuestSyncPayload.TaskEntry::pinned)
				.toList();
	}

	public static boolean isEmpty() {
		return quests.isEmpty();
	}

	/** Total tasks across every quest, for the header tally. */
	public static int totalTasks() {
		return quests.stream().mapToInt(quest -> quest.tasks().size()).sum();
	}

	public static int completedTasks() {
		return quests.stream()
				.flatMap(quest -> quest.tasks().stream())
				.filter(QuestSyncPayload.TaskEntry::complete)
				.mapToInt(task -> 1)
				.sum();
	}
}
