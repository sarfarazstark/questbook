package com.questbook.network;

import com.questbook.QuestBook;
import com.questbook.data.Quest;
import com.questbook.data.Task;
import com.questbook.storage.QuestSavedData;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server to client: the player's assigned tasks, grouped by quest.
 *
 * <p>Only the recipient's tasks are sent. A player has no reason to see another
 * player's assignments, and sending them would leak the whole quest plan.
 *
 * <p>Titles are resolved server-side to display names, so the client never needs
 * the item registry to render a row.
 *
 * @param quests the quests that have at least one task for this player
 */
public record QuestSyncPayload(List<QuestEntry> quests) implements CustomPacketPayload {
	/**
	 * Bumped whenever the shape changes, so a mismatched client can be detected.
	 *
	 * <p>Version 2 added quest rewards, prerequisite names, the locked flag and
	 * task rewards to both entry records, so a 1-era client misreads the stream.
	 */
	public static final int PROTOCOL_VERSION = 2;

	public static final CustomPacketPayload.Type<QuestSyncPayload> TYPE =
			new Type<>(QuestBook.id("sync"));

	public static final StreamCodec<FriendlyByteBuf, QuestSyncPayload> CODEC =
			CustomPacketPayload.codec(QuestSyncPayload::write, QuestSyncPayload::new);

	/**
	 * One quest, flattened for the wire.
	 *
	 * @param name  display name
	 * @param tasks this player's tasks in the quest, in display order
	 */
	public record QuestEntry(String name, List<TaskEntry> tasks) {
		static QuestEntry read(FriendlyByteBuf buf) {
			return new QuestEntry(buf.readUtf(),
					buf.readCollection(ArrayList::new, TaskEntry::read));
		}

		void write(FriendlyByteBuf buf) {
			buf.writeUtf(name);
			buf.writeCollection(tasks, (out, entry) -> entry.write(out));
		}
	}

	/**
	 * One task, flattened for the wire.
	 *
	 * @param id       stable task id, so the client can ask to pin this task
	 * @param label    human-readable item name, resolved server-side
	 * @param have     progress so far, already clamped to {@code need}
	 * @param need     the requirement
	 * @param complete whether the task is done
	 * @param pinned   whether this player has pinned it to their HUD
	 */
	public record TaskEntry(String id, String label, int have, int need, boolean complete,
			boolean pinned) {
		static TaskEntry read(FriendlyByteBuf buf) {
			return new TaskEntry(buf.readUtf(), buf.readUtf(), buf.readVarInt(),
					buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
		}

		void write(FriendlyByteBuf buf) {
			buf.writeUtf(id);
			buf.writeUtf(label);
			buf.writeVarInt(have);
			buf.writeVarInt(need);
			buf.writeBoolean(complete);
			buf.writeBoolean(pinned);
		}
	}

	public QuestSyncPayload(FriendlyByteBuf buf) {
		this(readQuests(buf));
	}

	private static List<QuestEntry> readQuests(FriendlyByteBuf buf) {
		return buf.readCollection(ArrayList::new, QuestEntry::read);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Instance writer, matching {@code CustomPacketPayload.codec}'s encoder shape. */
	private void write(FriendlyByteBuf buf) {
		buf.writeCollection(quests, (out, entry) -> entry.write(out));
	}

	/** Builds the payload for one player. */
	public static QuestSyncPayload forPlayer(QuestSavedData data, UUID player) {
		List<QuestEntry> entries = new ArrayList<>();

		for (Quest quest : data.store().quests()) {
			List<Task> mine = quest.tasksOf(player);

			if (mine.isEmpty()) {
				continue;
			}

			List<TaskEntry> tasks = new ArrayList<>();

			for (Task task : mine) {
				tasks.add(new TaskEntry(task.id().toString(), displayName(task.itemId()),
						task.cappedProgress(), task.count(), task.isComplete(), task.pinned()));
			}

			entries.add(new QuestEntry(quest.name(), tasks));
		}

		return new QuestSyncPayload(entries);
	}

	/** {@code minecraft:oak_log -> Oak log}. Duplicated in the tracker; kept local so the payload has no MC deps. */
	private static String displayName(String itemId) {
		String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		path = path.replace('_', ' ');

		return path.isEmpty() ? itemId : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	/** Sends this player their tasks, if the client can receive them. */
	public static void send(ServerPlayer player, QuestSavedData data) {
		if (ServerPlayNetworking.canSend(player, TYPE)) {
			ServerPlayNetworking.send(player, forPlayer(data, player.getUUID()));
		}
	}
}
