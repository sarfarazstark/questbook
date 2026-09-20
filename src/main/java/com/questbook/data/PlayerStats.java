package com.questbook.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Map;
import java.util.UUID;

/**
 * A player's lifetime contribution tally, for the leaderboard.
 *
 * <p>Counted from completions, never derived from live tasks: tasks get deleted
 * and quests get edited, but a completed task happened. Points are a flat weight
 * — 1 per task, 10 per quest — rather than anything derived from item counts,
 * so the ranking is explainable to a player without a wiki.
 *
 * <p>Keyed by player UUID in the saved data; the UUID itself is not stored here.
 */
public record PlayerStats(int tasks, int quests) {
	public static final int TASK_POINTS = 1;
	public static final int GOAL_POINTS = 10;

	public static final PlayerStats EMPTY = new PlayerStats(0, 0);

	public static final Codec<PlayerStats> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("tasks", 0).forGetter(PlayerStats::tasks),
			Codec.INT.optionalFieldOf("goals", 0).forGetter(PlayerStats::quests)
	).apply(instance, PlayerStats::new));

	public int points() {
		return tasks * TASK_POINTS + quests * GOAL_POINTS;
	}

	public PlayerStats withTask() {
		return new PlayerStats(tasks + 1, quests);
	}

	public PlayerStats withQuest() {
		return new PlayerStats(tasks, quests + 1);
	}

	/**
	 * Stats ranked by points, then quests, then tasks, then player id.
	 *
	 * <p>The player id is the last tiebreaker so the order is total and stable:
	 * two players on identical counts would otherwise swap places between
	 * refreshes, which reads as a bug.
	 */
	public static java.util.List<Map.Entry<UUID, PlayerStats>> ranked(Map<UUID, PlayerStats> stats) {
		return stats.entrySet().stream()
				.sorted(java.util.Comparator
						.<Map.Entry<UUID, PlayerStats>>comparingInt(e -> e.getValue().points()).reversed()
						.thenComparing(e -> e.getValue().quests(), java.util.Comparator.reverseOrder())
						.thenComparing(e -> e.getValue().tasks(), java.util.Comparator.reverseOrder())
						.thenComparing(Map.Entry::getKey))
				.toList();
	}
}
