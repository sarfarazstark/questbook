package com.questbook.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Optional;

/**
 * Granted when a quest completes.
 *
 * <p>Three kinds, because between them they cover everything without the mod
 * needing new code for each idea:
 * <ul>
 *   <li>{@link Kind#ITEM} — gives {@code amount} of item {@code value}</li>
 *   <li>{@link Kind#COMMAND} — runs {@code value} as the server, with
 *       {@code %player%} replaced by the recipient's name</li>
 *   <li>{@link Kind#XP} — grants {@code amount} experience levels</li>
 * </ul>
 *
 * <p>{@code COMMAND} is what makes this extensible: any reward that can be
 * expressed as a command works without shipping a new build. It is also the one
 * dangerous kind — see {@link #validateForOp()} — because an unprivileged editor
 * could otherwise run arbitrary server commands.
 *
 * @param kind   what to grant
 * @param value  item id for {@link Kind#ITEM}, command template for {@link Kind#COMMAND}
 * @param amount quantity for {@link Kind#ITEM} and {@link Kind#XP}; ignored by {@link Kind#COMMAND}
 */
public record Reward(Kind kind, String value, int amount) {
	public enum Kind {
		ITEM,
		COMMAND,
		XP
	}

	public static final Codec<Reward> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.xmap(Kind::valueOf, Kind::name).fieldOf("kind").forGetter(Reward::kind),
			Codec.STRING.fieldOf("value").forGetter(Reward::value),
			Codec.INT.optionalFieldOf("amount", 1).forGetter(Reward::amount)
	).apply(instance, Reward::new));

	public static Reward item(String itemId, int amount) {
		return new Reward(Kind.ITEM, itemId, amount);
	}

	public static Reward command(String template) {
		return new Reward(Kind.COMMAND, template, 0);
	}

	public static Reward xp(int levels) {
		return new Reward(Kind.XP, "", levels);
	}

	/**
	 * Wire form, beside the disk {@link #CODEC} so both live in one place.
	 *
	 * <p>Kind is written by name, not ordinal: a later {@link Kind} inserted in
	 * the middle must not silently renumber every reward already on the wire.
	 * The disk codec is separate because it predates this and is load-bearing for
	 * existing worlds.
	 */
	public static void writeWire(FriendlyByteBuf buf, Reward reward) {
		buf.writeUtf(reward.kind().name());
		buf.writeUtf(reward.value());
		buf.writeVarInt(reward.amount());
	}

	public static Reward readWire(FriendlyByteBuf buf) {
		return new Reward(Kind.valueOf(buf.readUtf()), buf.readUtf(), buf.readVarInt());
	}

	/**
	 * Absence as a single boolean, so "no reward" costs one byte and never needs
	 * a sentinel reward that could be mistaken for a real one.
	 */
	public static void writeOptionalWire(FriendlyByteBuf buf, Optional<Reward> reward) {
		buf.writeBoolean(reward.isPresent());
		reward.ifPresent(r -> writeWire(buf, r));
	}

	public static Optional<Reward> readOptionalWire(FriendlyByteBuf buf) {
		return buf.readBoolean() ? Optional.of(readWire(buf)) : Optional.empty();
	}

	/**
	 * Whether this reward is safe to grant.
	 *
	 * <p>A command reward is a privilege-escalation surface: whoever can set one
	 * can name any command. Callers must gate reward editing to op level 2+, and
	 * must substitute {@code %player%} with a sanitised player name rather than
	 * concatenating raw input.
	 */
	public boolean isSane() {
		return switch (kind) {
			case ITEM -> !value.isBlank() && amount > 0;
			case COMMAND -> !value.isBlank() && !value.contains("\n");
			case XP -> amount > 0;
		};
	}

	/** The command to run for {@code playerName}, or empty for non-command rewards. */
	public java.util.Optional<String> commandFor(String playerName) {
		if (kind != Kind.COMMAND) {
			return java.util.Optional.empty();
		}

		return java.util.Optional.of(value.replace("%player%", playerName));
	}
}
