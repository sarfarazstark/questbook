package com.questbook.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * One unit of work: collect {@code count} of one item, assigned to one player.
 *
 * <p>The assignee rule is one-to-many from this side — a task names exactly one
 * player, and a player may hold many tasks — so a single {@code assignee} field
 * is enough and no join table is needed.
 *
 * @param id           stable identity, generated on creation
 * @param itemId       registry id of the item to collect, e.g. {@code minecraft:oak_log}
 * @param count        how many are required; always >= 1
 * @param assignee     the only player whose pickups count toward this task
 * @param assigneeName the assignee's name as it was when assigned. Stored rather than
 *                     looked up because a name is not derivable from a UUID once the
 *                     player is offline — the lookup degrades to a UUID stub, which is
 *                     not a name. Recorded at assignment time and kept even after the
 *                     assignee logs out; empty when unknown, as in a legacy save.
 * @param progress     how many the assignee has collected so far
 * @param pinned       whether the assignee pinned this task to their HUD. Per-task is
 *                     correct rather than per-player: a task has exactly one
 *                     assignee, so a pin belongs to that pairing.
 */
public record Task(UUID id, String itemId, int count, UUID assignee, String assigneeName, int progress,
		boolean pinned) {
	/** Disk form. Field names are the on-disk keys, so rename them only with a data fix. */
	public static final Codec<Task> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Task::id),
			Codec.STRING.fieldOf("item").forGetter(Task::itemId),
			Codec.INT.fieldOf("count").forGetter(Task::count),
			UUIDUtil.CODEC.fieldOf("assignee").forGetter(Task::assignee),
			// Optional so a save written before names were recorded still decodes; those
			// tasks fall back to a live lookup until next re-assigned.
			Codec.STRING.optionalFieldOf("assignee_name", "").forGetter(Task::assigneeName),
			Codec.INT.optionalFieldOf("progress", 0).forGetter(Task::progress),
			Codec.BOOL.optionalFieldOf("pinned", false).forGetter(Task::pinned)
	).apply(instance, Task::new));

	/** A task nobody has been assigned yet. */
	public static final UUID UNASSIGNED = new UUID(0L, 0L);

	public Task {
		if (count < 1) {
			throw new IllegalArgumentException("count must be >= 1, got " + count);
		}
		assigneeName = assigneeName == null ? "" : assigneeName;
	}

	public static Task create(String itemId, int count, UUID assignee) {
		return create(itemId, count, assignee, "");
	}

	public static Task create(String itemId, int count, UUID assignee, String assigneeName) {
		return new Task(UUID.randomUUID(), itemId, count, assignee, assigneeName, 0, false);
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
		return new Task(id, itemId, count, assignee, assigneeName, Math.max(0, newProgress), pinned);
	}

	/**
	 * Reassigns, recording the new name alongside the id. The name is stored at the
	 * same moment as the id so the two cannot disagree about who the assignee was.
	 */
	public Task withAssignee(UUID newAssignee, String newAssigneeName) {
		return new Task(id, itemId, count, newAssignee, newAssigneeName == null ? "" : newAssigneeName,
				progress, pinned);
	}

	/**
	 * Changes the amount and the assignee together — what an edit does. The item is
	 * untouched by construction, so no caller can accidentally swap it. Progress is
	 * clamped down to the new requirement rather than left able to read 70/64.
	 */
	public Task withCountAndAssignee(int newCount, UUID newAssignee, String newAssigneeName) {
		if (newCount < 1) {
			throw new IllegalArgumentException("count must be >= 1, got " + newCount);
		}
		return new Task(id, itemId, newCount, newAssignee,
				newAssigneeName == null ? "" : newAssigneeName, Math.min(progress, newCount), pinned);
	}

	public Task withPinned(boolean newPinned) {
		return new Task(id, itemId, count, assignee, assigneeName, progress, newPinned);
	}

	/** Adds to progress, clamped at the requirement. */
	public Task advance(int amount) {
		return withProgress(Math.min(count, progress + amount));
	}
}
