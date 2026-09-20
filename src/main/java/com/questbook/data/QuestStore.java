package com.questbook.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole quest set, as an immutable value.
 *
 * <p>Immutable so it can be handed to the client, serialised and compared without
 * defensive copying. Mutations return a new store; {@link #withQuest} et al are the
 * only ways in.
 */
public record QuestStore(List<Quest> quests) {
	public static final QuestStore EMPTY = new QuestStore(List.of());

	public QuestStore {
		quests = List.copyOf(quests);
	}

	public Optional<Quest> quest(UUID id) {
		return quests.stream().filter(g -> g.id().equals(id)).findFirst();
	}

	/**
	 * Adds a quest, or replaces the existing one with the same id.
	 *
	 * <p>Replacement is <em>in place</em>, for the same reason as
	 * {@link Quest#withTask}: a remove-then-add appends, so updating a quest — by
	 * pinning one of its tasks, or by any progress change — would move that whole
	 * quest to the end of the list.
	 */
	public QuestStore withQuest(Quest quest) {
		List<Quest> next = new ArrayList<>(quests);

		for (int i = 0; i < next.size(); i++) {
			if (next.get(i).id().equals(quest.id())) {
				next.set(i, quest);

				return new QuestStore(next);
			}
		}

		next.add(quest);

		return new QuestStore(next);
	}

	public QuestStore withoutQuest(UUID questId) {
		List<Quest> next = new ArrayList<>(quests);
		next.removeIf(existing -> existing.id().equals(questId));
		return new QuestStore(next);
	}

	/**
	 * Whether {@code questId} accepts progress: every prerequisite it names must be
	 * complete.
	 *
	 * <p>A prerequisite naming a quest that no longer exists counts as satisfied.
	 * The alternative is a quest locked forever, unfixable from in-game because the
	 * UUID needed to repair it is gone. An unknown prerequisite is an admin who
	 * deleted a node, not a puzzle.
	 */
	public boolean isUnlocked(UUID questId) {
		Optional<Quest> found = quest(questId);

		if (found.isEmpty()) {
			return false;
		}

		return found.get().prerequisites().stream()
				.allMatch(pre -> quest(pre).map(Quest::isComplete).orElse(true));
	}

	/** Applies {@code mapper} to one quest; no-op if it is absent. */
	public QuestStore updateQuest(UUID questId, java.util.function.UnaryOperator<Quest> mapper) {
		return quest(questId).map(g -> withQuest(mapper.apply(g))).orElse(this);
	}

	/** Applies {@code mapper} to one task of one quest; no-op if either is absent. */
	public QuestStore updateTask(UUID questId, UUID taskId, java.util.function.UnaryOperator<Task> mapper) {
		return updateQuest(questId, quest -> quest.updateTask(taskId, mapper));
	}

	/** Every task assigned to {@code player}, across all quests. */
	public List<Task> tasksOf(UUID player) {
		return quests.stream().flatMap(g -> g.tasksOf(player).stream()).toList();
	}
}
