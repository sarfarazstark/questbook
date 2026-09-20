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
 * Server to Admin Client: full quest tree and online player list.
 */
public record AdminSyncPayload(List<AdminQuestEntry> quests, List<PlayerEntry> players) implements CustomPacketPayload {
	public static final Type<AdminSyncPayload> TYPE = new Type<>(QuestBook.id("admin_sync"));

	public static final StreamCodec<FriendlyByteBuf, AdminSyncPayload> CODEC =
			CustomPacketPayload.codec(AdminSyncPayload::write, AdminSyncPayload::new);

	public record PlayerEntry(UUID id, String name) {
		static PlayerEntry read(FriendlyByteBuf buf) {
			return new PlayerEntry(buf.readUUID(), buf.readUtf());
		}

		void write(FriendlyByteBuf buf) {
			buf.writeUUID(id);
			buf.writeUtf(name);
		}
	}

	public record AdminTaskEntry(UUID id, String itemId, String label, int have, int need, boolean complete, UUID assignee, String assigneeName, Optional<Reward> reward) {
		static AdminTaskEntry read(FriendlyByteBuf buf) {
			return new AdminTaskEntry(
					buf.readUUID(),
					buf.readUtf(),
					buf.readUtf(),
					buf.readVarInt(),
					buf.readVarInt(),
					buf.readBoolean(),
					buf.readUUID(),
					buf.readUtf(),
					Reward.readOptionalWire(buf)
			);
		}

		void write(FriendlyByteBuf buf) {
			buf.writeUUID(id);
			buf.writeUtf(itemId);
			buf.writeUtf(label);
			buf.writeVarInt(have);
			buf.writeVarInt(need);
			buf.writeBoolean(complete);
			buf.writeUUID(assignee);
			buf.writeUtf(assigneeName);
			Reward.writeOptionalWire(buf, reward);
		}
	}

	public record AdminQuestEntry(UUID id, String name, Optional<Reward> reward,
			List<String> prerequisiteNames, List<UUID> prerequisiteIds, boolean locked,
			List<AdminTaskEntry> tasks) {
		static AdminQuestEntry read(FriendlyByteBuf buf) {
			return new AdminQuestEntry(
					buf.readUUID(),
					buf.readUtf(),
					Reward.readOptionalWire(buf),
					buf.readCollection(ArrayList::new, FriendlyByteBuf::readUtf),
					buf.readCollection(ArrayList::new, b -> b.readUUID()),
					buf.readBoolean(),
					buf.readCollection(ArrayList::new, AdminTaskEntry::read)
			);
		}

		void write(FriendlyByteBuf buf) {
			buf.writeUUID(id);
			buf.writeUtf(name);
			Reward.writeOptionalWire(buf, reward);
			buf.writeCollection(prerequisiteNames, FriendlyByteBuf::writeUtf);
			buf.writeCollection(prerequisiteIds, (out, id) -> out.writeUUID(id));
			buf.writeBoolean(locked);
			buf.writeCollection(tasks, (out, entry) -> entry.write(out));
		}
	}

	public AdminSyncPayload(FriendlyByteBuf buf) {
		this(
				buf.readCollection(ArrayList::new, AdminQuestEntry::read),
				buf.readCollection(ArrayList::new, PlayerEntry::read)
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeCollection(quests, (out, entry) -> entry.write(out));
		buf.writeCollection(players, (out, entry) -> entry.write(out));
	}
}
