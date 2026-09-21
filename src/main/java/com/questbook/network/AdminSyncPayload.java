package com.questbook.network;

import com.questbook.QuestBook;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server to Admin Client: full quest tree and online player list.
 *
 * <p>{@code mayEdit} tells the client whether to offer the editor at all. The server
 * enforces the same rule on every action regardless, so this is a convenience for the
 * UI rather than a security boundary — a forged value still gets rejected.
 */
public record AdminSyncPayload(List<AdminQuestEntry> quests, List<PlayerEntry> players,
		boolean mayEdit) implements CustomPacketPayload {
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

	public record AdminTaskEntry(UUID id, String itemId, String label, int have, int need, boolean complete,
			UUID assignee, String assigneeName) {
		static AdminTaskEntry read(FriendlyByteBuf buf) {
			return new AdminTaskEntry(
					buf.readUUID(),
					buf.readUtf(),
					buf.readUtf(),
					buf.readVarInt(),
					buf.readVarInt(),
					buf.readBoolean(),
					buf.readUUID(),
					buf.readUtf()
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
		}
	}

	public record AdminQuestEntry(UUID id, String name, List<AdminTaskEntry> tasks) {
		static AdminQuestEntry read(FriendlyByteBuf buf) {
			return new AdminQuestEntry(
					buf.readUUID(),
					buf.readUtf(),
					buf.readCollection(ArrayList::new, AdminTaskEntry::read)
			);
		}

		void write(FriendlyByteBuf buf) {
			buf.writeUUID(id);
			buf.writeUtf(name);
			buf.writeCollection(tasks, (out, entry) -> entry.write(out));
		}
	}

	public AdminSyncPayload(FriendlyByteBuf buf) {
		this(
				buf.readCollection(ArrayList::new, AdminQuestEntry::read),
				buf.readCollection(ArrayList::new, PlayerEntry::read),
				buf.readBoolean()
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeCollection(quests, (out, entry) -> entry.write(out));
		buf.writeCollection(players, (out, entry) -> entry.write(out));
		buf.writeBoolean(mayEdit);
	}
}
