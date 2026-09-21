package com.questbook.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A named container of tasks.
 *
 * <p>A quest is complete when every task in it is complete.
 *
 * @param id    stable identity
 * @param name  shown to players
 * @param tasks the tasks, in display order
 */
public record Quest(UUID id, String name, List<Task> tasks) {
	/** Disk form. Field names are the on-disk keys, so rename them only with a data fix. */
	public static final Codec<Quest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Quest::id),
			Codec.STRING.fieldOf("name").forGetter(Quest::name),
			Task.CODEC.listOf().optionalFieldOf("tasks", List.of()).forGetter(Quest::tasks)
	).apply(instance, Quest::new));

	public Quest {
		tasks = List.copyOf(tasks);
	}

	public static Quest create(String name) {
		return new Quest(UUID.randomUUID(), name, List.of());
	}

	public Quest withName(String newName) {
		return new Quest(id, newName, tasks);
	}

	/**
	 * Adds a task, or replaces the existing one with the same id.
	 *
	 * <p>Replacement is <em>in place</em>. A remove-then-add would move the task to
	 * the end of the list, so simply updating a task's progress or pin would
	 * reorder the player's list under them.
	 */
	public Quest withTask(Task task) {
		List<Task> next = new ArrayList<>(tasks);

		for (int i = 0; i < next.size(); i++) {
			if (next.get(i).id().equals(task.id())) {
				next.set(i, task);

				return new Quest(id, name, next);
			}
		}

		next.add(task);

		return new Quest(id, name, next);
	}

	/** Removes a task by id. No-op if absent. */
	public Quest withoutTask(UUID taskId) {
		List<Task> next = new ArrayList<>(tasks);
		next.removeIf(existing -> existing.id().equals(taskId));
		return new Quest(id, name, next);
	}

	public Optional<Task> task(UUID taskId) {
		return tasks.stream().filter(t -> t.id().equals(taskId)).findFirst();
	}

	/** Applies {@code mapper} to the task with {@code taskId}; no-op if absent. */
	public Quest updateTask(UUID taskId, java.util.function.UnaryOperator<Task> mapper) {
		return task(taskId).map(t -> withTask(mapper.apply(t))).orElse(this);
	}

	public boolean isComplete() {
		return !tasks.isEmpty() && tasks.stream().allMatch(Task::isComplete);
	}

	public int completedCount() {
		return (int) tasks.stream().filter(Task::isComplete).count();
	}

	/** Tasks assigned to {@code player}, in display order. */
	public List<Task> tasksOf(UUID player) {
		return tasks.stream().filter(t -> t.assignee().equals(player)).toList();
	}
}
