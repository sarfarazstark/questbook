package com.questbook.network;

import com.questbook.QuestBook;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Client to server: pin or unpin one of this player's tasks.
 *
 * <p>Only the assignee may pin their own task, and the server checks that rather
 * than trusting the client.
 *
 * @param taskId the task to change
 * @param pinned the desired state
 */
public record PinTaskPayload(UUID taskId, boolean pinned) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PinTaskPayload> TYPE =
			new Type<>(QuestBook.id("pin_task"));

	public static final StreamCodec<FriendlyByteBuf, PinTaskPayload> CODEC =
			CustomPacketPayload.codec(PinTaskPayload::write, PinTaskPayload::new);

	public PinTaskPayload(FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readBoolean());
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUUID(taskId);
		buf.writeBoolean(pinned);
	}
}
