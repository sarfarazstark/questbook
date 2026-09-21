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

	/**
	 * Quests as the book should show them: completed tasks are dropped, and a quest
	 * whose tasks are all done is dropped with them.
	 *
	 * <p>A finished task is still on the server — this is display only, and the next
	 * sync still carries it. Hiding it is the whole point: a list that keeps its
	 * finished work in place pushes the remaining work down the page, and the player
	 * has to re-read rows they are done with to find what is left.
	 *
	 * <p>Empty quests go too. A title with nothing under it reads as a rendering bug
	 * rather than as "you finished this".
	 */
	public static List<QuestSyncPayload.QuestEntry> openQuests() {
		return quests.stream()
				.map(quest -> new QuestSyncPayload.QuestEntry(quest.name(),
						quest.tasks().stream()
								.filter(task -> !task.complete())
								.toList()))
				.filter(quest -> !quest.tasks().isEmpty())
				.toList();
	}

	/**
	 * Tasks this player pinned, across every quest, in list order.
	 *
	 * <p>Completed tasks are excluded: the HUD is a to-do list, and a finished entry
	 * sitting in the corner of the screen is noise the player cannot dismiss without
	 * unpinning something they may not remember pinning.
	 */
	public static List<QuestSyncPayload.TaskEntry> pinnedTasks() {
		return quests.stream()
				.flatMap(quest -> quest.tasks().stream())
				.filter(QuestSyncPayload.TaskEntry::pinned)
				.filter(task -> !task.complete())
				.toList();
	}

	public static boolean isEmpty() {
		return quests.isEmpty();
	}

	/** Total tasks across every quest, for the header tally. */
	public static int totalTasks() {
		return quests.stream().mapToInt(quest -> quest.tasks().size()).sum();
	}

	/** Tasks still to do, across every quest. What the header counts. */
	public static int openTasks() {
		return quests.stream()
				.flatMap(quest -> quest.tasks().stream())
				.filter(task -> !task.complete())
				.mapToInt(task -> 1)
				.sum();
	}

	public static int completedTasks() {
		return quests.stream()
				.flatMap(quest -> quest.tasks().stream())
				.filter(QuestSyncPayload.TaskEntry::complete)
				.mapToInt(task -> 1)
				.sum();
	}
}
