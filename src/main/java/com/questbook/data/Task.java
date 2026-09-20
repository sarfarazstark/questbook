package com.questbook.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * One unit of work: collect {@code count} of one item, assigned to one player.
 *
 * <p>The assignee rule is one-to-many from this side — a task names exactly one
 * player, and a player may hold many tasks — so a single {@code assignee} field
 * is enough and no join table is needed.
 *
 * <p>A task may carry its own reward. That reward is granted to the assignee and
 * nobody else, the instant this task completes. It is deliberately separate from
 * the quest reward, which is shared by everyone holding a task in the quest.
 *
 * @param id       stable identity, generated on creation
 * @param itemId   registry id of the item to collect, e.g. {@code minecraft:oak_log}
 * @param count    how many are required; always >= 1
 * @param assignee the only player whose pickups count toward this task
 * @param progress how many the assignee has collected so far
 * @param pinned   whether the assignee pinned this task to their HUD. Per-task is
 *                 correct rather than per-player: a task has exactly one
 *                 assignee, so a pin belongs to that pairing.
 * @param reward   granted to the assignee alone on completion; absent means none
 */
public record Task(UUID id, String itemId, int count, UUID assignee, int progress,
		boolean pinned, Optional<Reward> reward) {
	/** Disk form. Field names are the on-disk keys, so rename them only with a data fix. */
	public static final Codec<Task> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Task::id),
			Codec.STRING.fieldOf("item").forGetter(Task::itemId),
			Codec.INT.fieldOf("count").forGetter(Task::count),
			UUIDUtil.CODEC.fieldOf("assignee").forGetter(Task::assignee),
			Codec.INT.optionalFieldOf("progress", 0).forGetter(Task::progress),
			Codec.BOOL.optionalFieldOf("pinned", false).forGetter(Task::pinned),
			Reward.CODEC.optionalFieldOf("reward").forGetter(Task::reward)
	).apply(instance, Task::new));

	/** A task nobody has been assigned yet. */
	public static final UUID UNASSIGNED = new UUID(0L, 0L);

	public Task {
		if (count < 1) {
			throw new IllegalArgumentException("count must be >= 1, got " + count);
		}
	}

	public static Task create(String itemId, int count, UUID assignee) {
		return new Task(UUID.randomUUID(), itemId, count, assignee, 0, false, Optional.empty());
	}

	public boolean isUnassigned() {
		return assignee.equals(UNASSIGNED);
	}

	public boolean isComplete() {
		return progress >= count;
	}

	/** Fraction collected, 0..1, for progress bars. */
	public float fraction() {
		return Math.min(1f, (float) progress / count);
	}

	/** Counts at most {@code count}, so a display never reads 70/64. */
	public int cappedProgress() {
		return Math.min(progress, count);
	}

	public Task withProgress(int newProgress) {
		return new Task(id, itemId, count, assignee, Math.max(0, newProgress), pinned, reward);
	}

	public Task withAssignee(UUID newAssignee) {
		return new Task(id, itemId, count, newAssignee, progress, pinned, reward);
	}

	public Task withPinned(boolean newPinned) {
		return new Task(id, itemId, count, assignee, progress, newPinned, reward);
	}

	public Task withReward(Optional<Reward> newReward) {
		return new Task(id, itemId, count, assignee, progress, pinned, newReward);
	}

	/** Adds to progress, clamped at the requirement. */
	public Task advance(int amount) {
		return withProgress(Math.min(count, progress + amount));
	}
}
