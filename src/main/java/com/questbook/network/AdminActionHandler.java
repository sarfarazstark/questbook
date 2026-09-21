package com.questbook.network;

import com.questbook.QuestBook;
import com.questbook.data.Quest;
import com.questbook.data.QuestStore;
import com.questbook.data.Task;
import com.questbook.storage.QuestSavedData;
import com.questbook.tracking.Quests;
import com.questbook.util.QuestText;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public final class AdminActionHandler {
	private AdminActionHandler() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(AdminActionPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handle(context.player(), payload)));
	}

	private static void handle(ServerPlayer player, AdminActionPayload payload) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}

		QuestSavedData data = Quests.find(server).orElse(null);
		if (data == null) {
			return;
		}

		if (!mayEdit(player, data)) {
			QuestBook.LOGGER.warn("Player {} without editor access tried to perform admin action: {}",
					player.getGameProfile().name(), payload.action());
			return;
		}

		switch (payload.action()) {
			case REQUEST_SYNC -> sendAdminSync(player, server, data);
			case CREATE_GOAL_WITH_TASKS -> handleCreateQuest(server, data, payload);
			case DELETE_GOAL -> handleDeleteQuest(server, data, payload);
			case DELETE_TASK -> handleDeleteTask(server, data, payload);
			case REASSIGN_TASK -> handleReassignTask(server, data, payload);
			case ADD_TASK -> handleAddTask(server, data, payload);
			case UPDATE_TASK -> handleUpdateTask(server, data, payload);
			case RENAME_GOAL -> handleRenameQuest(server, data, payload);
		}
	}
	private static void handleCreateQuest(MinecraftServer server, QuestSavedData data, AdminActionPayload payload) {
		String name = payload.questName().trim();
		if (name.isEmpty()) {
			return;
		}

		Quest quest = Quest.create(name);
		for (AdminActionPayload.NewTaskData taskData : payload.newTasks()) {
			if (taskData.count() < 1) {
				continue;
			}
			Task task = Task.create(taskData.itemId(), taskData.count(), taskData.assignee());
			quest = quest.withTask(task);
			notifyAssignee(server, taskData.assignee(), name, taskData.itemId(), taskData.count());
		}
		data.setStore(data.store().withQuest(quest));
		QuestNetworking.syncAll(server);
		syncAllAdmins(server, data);
	}

	private static void handleDeleteQuest(MinecraftServer server, QuestSavedData data, AdminActionPayload payload) {
		data.setStore(data.store().withoutQuest(payload.questId()));
		QuestNetworking.syncAll(server);
		syncAllAdmins(server, data);
	}

	private static void handleDeleteTask(MinecraftServer server, QuestSavedData data, AdminActionPayload payload) {
		data.setStore(data.store().updateQuest(payload.questId(), g -> g.withoutTask(payload.taskId())));
		QuestNetworking.syncAll(server);
		syncAllAdmins(server, data);
	}

	private static void handleReassignTask(MinecraftServer server, QuestSavedData data, AdminActionPayload payload) {
		if (payload.newTasks().isEmpty()) {
			return;
		}
		UUID newAssignee = payload.newTasks().getFirst().assignee();
		String newName = resolvePlayerName(server, newAssignee);
		data.setStore(data.store().updateQuest(payload.questId(), g -> {
			g.task(payload.taskId()).ifPresent(t -> notifyAssignee(server, newAssignee, g.name(), t.itemId(), t.count()));
			return g.updateTask(payload.taskId(), t -> t.withAssignee(newAssignee, newName));
		}));
		QuestNetworking.syncAll(server);
		syncAllAdmins(server, data);
	}

	private static void handleAddTask(MinecraftServer server, QuestSavedData data, AdminActionPayload payload) {
		if (payload.newTasks().isEmpty()) {
			return;
		}
		AdminActionPayload.NewTaskData taskData = payload.newTasks().getFirst();
		if (taskData.count() < 1) {
			return;
		}
		Task task = Task.create(taskData.itemId(), taskData.count(), taskData.assignee(),
				resolvePlayerName(server, taskData.assignee()));
		data.setStore(data.store().updateQuest(payload.questId(), g -> {
			notifyAssignee(server, taskData.assignee(), g.name(), taskData.itemId(), taskData.count());
			return g.withTask(task);
		}));
		QuestNetworking.syncAll(server);
		syncAllAdmins(server, data);
	}

	private static void handleUpdateTask(MinecraftServer server, QuestSavedData data, AdminActionPayload payload) {
		if (payload.newTasks().isEmpty()) {
			return;
		}
		AdminActionPayload.NewTaskData taskData = payload.newTasks().getFirst();
		if (taskData.count() < 1) {
			return;
		}
		String newName = resolvePlayerName(server, taskData.assignee());
		data.setStore(data.store().updateQuest(payload.questId(), g -> {
			notifyAssignee(server, taskData.assignee(), g.name(), taskData.itemId(), taskData.count());
			// itemId comes from the payload but the caller sends the task's existing one:
			// an edit changes amount and assignee, never the item.
			return g.updateTask(payload.taskId(), t -> t.withCountAndAssignee(
					taskData.count(), taskData.assignee(), newName));
		}));
		QuestNetworking.syncAll(server);
		syncAllAdmins(server, data);
	}

	private static void handleRenameQuest(MinecraftServer server, QuestSavedData data, AdminActionPayload payload) {
		String newName = payload.questName().trim();
		if (newName.isEmpty()) {
			return;
		}
		data.setStore(data.store().updateQuest(payload.questId(), g -> g.withName(newName)));
		QuestNetworking.syncAll(server);
		syncAllAdmins(server, data);
	}

	/**
	 * Whether {@code player} may edit quests: holds OP level 2, or was granted editor
	 * access by one.
	 *
	 * <p>The single definition of "may edit". Every gate routes through here — the
	 * action receiver, the admin sync fan-out and the commands — because three copies
	 * of this rule is exactly how one of them ends up permissive by accident.
	 */
	public static boolean mayEdit(ServerPlayer player, QuestSavedData data) {
		return Commands.LEVEL_GAMEMASTERS.check(player.permissions())
				|| data.isEditor(player.getUUID());
	}

	public static void syncAllAdmins(MinecraftServer server, QuestSavedData data) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (mayEdit(player, data) && ServerPlayNetworking.canSend(player, AdminSyncPayload.TYPE)) {
				sendAdminSync(player, server, data);
			}
		}
	}

	private static void notifyAssignee(MinecraftServer server, UUID assignee, String questName, String itemId, int count) {
		if (assignee == null || assignee.equals(Task.UNASSIGNED)) {
			return;
		}
		ServerPlayer target = server.getPlayerList().getPlayer(assignee);
		if (target == null) {
			return;
		}
		target.sendSystemMessage(QuestText.taskAssigned(questName, itemId, count));
	}
	public static void sendAdminSync(ServerPlayer player, MinecraftServer server, QuestSavedData data) {
		List<AdminSyncPayload.PlayerEntry> onlinePlayers = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			onlinePlayers.add(new AdminSyncPayload.PlayerEntry(p.getUUID(), p.getGameProfile().name()));
		}

		List<AdminSyncPayload.AdminQuestEntry> questEntries = new ArrayList<>();
		for (Quest quest : data.store().quests()) {
			List<AdminSyncPayload.AdminTaskEntry> taskEntries = new ArrayList<>();
			for (Task task : quest.tasks()) {
				String assigneeName = assigneeDisplayName(server, task);
				String label = displayName(task.itemId());
				taskEntries.add(new AdminSyncPayload.AdminTaskEntry(
						task.id(),
						task.itemId(),
						label,
						task.cappedProgress(),
						task.count(),
						task.isComplete(),
						task.assignee(),
						assigneeName
				));
			}

			questEntries.add(new AdminSyncPayload.AdminQuestEntry(
					quest.id(),
					quest.name(),
					taskEntries
			));
		}

		ServerPlayNetworking.send(player, new AdminSyncPayload(questEntries, onlinePlayers,
				mayEdit(player, data)));
	}

	/**
	 * Who to show for a task's assignee.
	 *
	 * <p>Preference order: the name stored on the task, then a live lookup for a task
	 * saved before names were recorded. The stored name is what makes an assignee survive
	 * a logout — a UUID cannot be turned back into a name, so a live-only lookup would
	 * show a stub for anyone offline. A live lookup still runs first for an unnamed
	 * legacy task, which is the one case where the store has nothing to offer.
	 */
	private static String assigneeDisplayName(MinecraftServer server, Task task) {
		if (task.isUnassigned()) {
			return "Unassigned";
		}
		if (!task.assigneeName().isEmpty()) {
			return task.assigneeName();
		}
		ServerPlayer p = server.getPlayerList().getPlayer(task.assignee());
		if (p != null) {
			return p.getGameProfile().name();
		}
		// Legacy task for someone gone: no name was ever recorded and none can be
		// recovered. Say so rather than presenting a UUID fragment as a name.
		return "Unknown player";
	}

	/** The assignee's name at the moment of assignment, for storing on the task. */
	private static String resolvePlayerName(MinecraftServer server, UUID uuid) {
		if (uuid == null || uuid.equals(Task.UNASSIGNED)) {
			return "";
		}
		ServerPlayer p = server.getPlayerList().getPlayer(uuid);
		return p != null ? p.getGameProfile().name() : "";
	}

	private static String displayName(String itemId) {
		String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		path = path.replace('_', ' ');
		return path.isEmpty() ? itemId : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}
}
