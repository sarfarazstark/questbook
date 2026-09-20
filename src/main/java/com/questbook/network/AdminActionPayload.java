package com.questbook.network;

import com.questbook.QuestBook;
import com.questbook.data.Reward;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Client to Server: admin action requests.
 */
public record AdminActionPayload(
		Action action,
		String questName,
		UUID questId,
		UUID taskId,
		List<NewTaskData> newTasks,
		Optional<Reward> reward,
		UUID prerequisiteId) implements CustomPacketPayload {
	public static final Type<AdminActionPayload> TYPE = new Type<>(QuestBook.id("admin_action"));

	public static final StreamCodec<FriendlyByteBuf, AdminActionPayload> CODEC =
			CustomPacketPayload.codec(AdminActionPayload::write, AdminActionPayload::new);

	public enum Action {
		CREATE_GOAL_WITH_TASKS,
		DELETE_GOAL,
		DELETE_TASK,
		ADD_TASK,
		UPDATE_TASK,
		REASSIGN_TASK,
		RENAME_GOAL,
		REQUEST_SYNC,
		SET_QUEST_REWARD,
		SET_TASK_REWARD,
		ADD_PREREQUISITE,
		REMOVE_PREREQUISITE
	}

	public record NewTaskData(String itemId, int count, UUID assignee) {
		static NewTaskData read(FriendlyByteBuf buf) {
			return new NewTaskData(buf.readUtf(), buf.readVarInt(), buf.readUUID());
		}

		void write(FriendlyByteBuf buf) {
			buf.writeUtf(itemId);
			buf.writeVarInt(count);
			buf.writeUUID(assignee);
		}
	}

	private static final UUID NIL = new UUID(0L, 0L);

	public static AdminActionPayload createQuest(String name, List<NewTaskData> tasks) {
		return new AdminActionPayload(Action.CREATE_GOAL_WITH_TASKS, name, NIL, NIL, tasks, Optional.empty(), NIL);
	}

	public static AdminActionPayload deleteQuest(UUID questId) {
		return new AdminActionPayload(Action.DELETE_GOAL, "", questId, NIL, List.of(), Optional.empty(), NIL);
	}

	public static AdminActionPayload deleteTask(UUID questId, UUID taskId) {
		return new AdminActionPayload(Action.DELETE_TASK, "", questId, taskId, List.of(), Optional.empty(), NIL);
	}

	public static AdminActionPayload addTask(UUID questId, NewTaskData taskData) {
		return new AdminActionPayload(Action.ADD_TASK, "", questId, NIL, List.of(taskData), Optional.empty(), NIL);
	}

	public static AdminActionPayload reassignTask(UUID questId, UUID taskId, UUID newAssignee) {
		return new AdminActionPayload(Action.REASSIGN_TASK, "", questId, taskId, List.of(new NewTaskData("", 0, newAssignee)), Optional.empty(), NIL);
	}

	public static AdminActionPayload updateTask(UUID questId, UUID taskId, NewTaskData taskData) {
		return new AdminActionPayload(Action.UPDATE_TASK, "", questId, taskId, List.of(taskData), Optional.empty(), NIL);
	}

	public static AdminActionPayload renameQuest(UUID questId, String newName) {
		return new AdminActionPayload(Action.RENAME_GOAL, newName, questId, NIL, List.of(), Optional.empty(), NIL);
	}

	public static AdminActionPayload requestSync() {
		return new AdminActionPayload(Action.REQUEST_SYNC, "", NIL, NIL, List.of(), Optional.empty(), NIL);
	}

	public static AdminActionPayload setQuestReward(UUID questId, Optional<Reward> reward) {
		return new AdminActionPayload(Action.SET_QUEST_REWARD, "", questId, NIL, List.of(), reward, NIL);
	}

	public static AdminActionPayload setTaskReward(UUID questId, UUID taskId, Optional<Reward> reward) {
		return new AdminActionPayload(Action.SET_TASK_REWARD, "", questId, taskId, List.of(), reward, NIL);
	}

	public static AdminActionPayload addPrerequisite(UUID questId, UUID prerequisiteId) {
		return new AdminActionPayload(Action.ADD_PREREQUISITE, "", questId, NIL, List.of(), Optional.empty(), prerequisiteId);
	}

	public static AdminActionPayload removePrerequisite(UUID questId, UUID prerequisiteId) {
		return new AdminActionPayload(Action.REMOVE_PREREQUISITE, "", questId, NIL, List.of(), Optional.empty(), prerequisiteId);
	}

	public AdminActionPayload(FriendlyByteBuf buf) {
		this(
				buf.readEnum(Action.class),
				buf.readUtf(),
				buf.readUUID(),
				buf.readUUID(),
				buf.readCollection(ArrayList::new, NewTaskData::read),
				Reward.readOptionalWire(buf),
				buf.readUUID()
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeEnum(action);
		buf.writeUtf(questName);
		buf.writeUUID(questId);
		buf.writeUUID(taskId);
		buf.writeCollection(newTasks, (out, entry) -> entry.write(out));
		Reward.writeOptionalWire(buf, reward);
		buf.writeUUID(prerequisiteId);
	}
}
