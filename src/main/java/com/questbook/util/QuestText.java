package com.questbook.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * Consistent, clean chromatic gradient text components for quest announcements.
 */
public final class QuestText {
	private QuestText() {
	}

	/**
	 * Creates a smooth chromatic RGB gradient component at standard default font size.
	 */
	public static MutableComponent chromatic(String text, int startRgb, int endRgb) {
		MutableComponent comp = Component.empty();
		int len = text.length();
		for (int i = 0; i < len; i++) {
			float ratio = len > 1 ? (float) i / (len - 1) : 0;
			int r = (int) (((startRgb >> 16) & 0xFF) * (1 - ratio) + ((endRgb >> 16) & 0xFF) * ratio);
			int g = (int) (((startRgb >> 8) & 0xFF) * (1 - ratio) + ((endRgb >> 8) & 0xFF) * ratio);
			int b = (int) ((startRgb & 0xFF) * (1 - ratio) + (endRgb & 0xFF) * ratio);
			int rgb = (r << 16) | (g << 8) | b;
			comp.append(Component.literal(String.valueOf(text.charAt(i)))
					.withStyle(s -> s.withColor(TextColor.fromRgb(rgb))));
		}
		return comp;
	}

	/**
	 * A short prefix tag: one accent glyph and a word, no trailing decoration.
	 *
	 * <p>The old form wrapped the label in {@code ✦ ... ✦} on every line, which made a
	 * two-word announcement occupy half the chat width. Colour is carried by the label
	 * alone now; the glyph marks the line and nothing more.
	 */
	public static MutableComponent tag(String label, int colour) {
		MutableComponent comp = Component.empty();
		comp.append(Component.literal("\u2726 ").withStyle(s -> s.withColor(colour)));
		comp.append(Component.literal(label).withStyle(s -> s.withColor(colour)));
		comp.append(Component.literal(" \u00a78\u00bb "));
		return comp;
	}

	public static Component taskAssigned(String questName, String itemId, int count) {
		MutableComponent msg = tag("New task", 0xFFA726);
		msg.append(Component.literal(count + "x " + displayName(itemId)).withStyle(s -> s.withColor(0xFFEE58)));
		msg.append(Component.literal("\u00a77 in \u00a7f" + questName));
		return msg;
	}

	public static Component taskCompleted(String playerName, String itemId, int count) {
		MutableComponent msg = tag("Done", 0x66BB6A);
		msg.append(Component.literal(count + "x " + displayName(itemId)).withStyle(s -> s.withColor(0x81C784)));
		msg.append(Component.literal("\u00a77 by \u00a7f" + playerName));
		return msg;
	}

	public static Component questCompleted(String questName) {
		MutableComponent msg = tag("Quest complete", 0xFFD54F);
		msg.append(Component.literal(questName).withStyle(s -> s.withColor(0xFFEE58)));
		return msg;
	}

	public static String displayName(String itemId) {
		String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		path = path.replace('_', ' ');
		return path.isEmpty() ? itemId : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	// --- command output ------------------------------------------------------

	/**
	 * Command output is prefixed with a single outcome glyph rather than a brand tag.
	 *
	 * <p>The old {@code ✦ QUESTBOOK ✦} prefix cost ~11 characters on every line of
	 * every command, which pushed real output off the visible chat width and read as
	 * shouting. One coloured glyph says the same thing in a tenth of the space.
	 */

	/** A command that did what it was asked. */
	public static Component success(String message) {
		MutableComponent msg = Component.empty();
		msg.append(Component.literal("\u2714 ").withStyle(s -> s.withColor(0x81C784)));
		msg.append(Component.literal(message).withStyle(s -> s.withColor(0xE0E0E0)));
		return msg;
	}

	/** A command that could not be carried out. */
	public static Component failure(String message) {
		MutableComponent msg = Component.empty();
		// U+2715, matching the close and delete crosses in the editor. One cross glyph
		// across the mod, so a refusal reads as the same mark the player clicks.
		msg.append(Component.literal("\u2715 ").withStyle(s -> s.withColor(0xE57373)));
		msg.append(Component.literal(message).withStyle(s -> s.withColor(0xE0E0E0)));
		return msg;
	}

	/** Plain command output: neither good nor bad news. */
	public static Component info(String message) {
		MutableComponent msg = Component.empty();
		msg.append(Component.literal("\u00b7 ").withStyle(s -> s.withColor(0x9E9E9E)));
		msg.append(Component.literal(message).withStyle(s -> s.withColor(0xE0E0E0)));
		return msg;
	}

	/** An indented continuation line under {@link #success} or {@link #info}. */
	public static Component sub(String message) {
		return Component.literal("   \u00a78" + message);
	}

	/**
	 * A ranked leaderboard row.
	 *
	 * <p>Rank colour is bronze, silver and gold for the podium and white below,
	 * because a leaderboard is the one place where a player expects a medal.
	 */
	public static Component topRow(int rank, String playerName, int points, int tasks, int quests) {
		int rankColour = switch (rank) {
			case 1 -> 0xFFD700;
			case 2 -> 0xC0C0C0;
			case 3 -> 0xCD7F32;
			default -> 0xBDBDBD;
		};

		MutableComponent msg = Component.empty();
		msg.append(Component.literal("#" + rank + " ").withStyle(s -> s.withColor(rankColour).withBold(true)));
		msg.append(chromatic(playerName, 0x4FC3F7, 0x81C784));
		msg.append(Component.literal("  \u00a7f" + points + " pts"));
		msg.append(Component.literal(" \u00a78(" + tasks + " tasks, " + quests + " quests)"));
		return msg;
	}

	/** A quest line in {@code /questbook tree}. */
	public static Component treeRow(String name, int done, int total) {
		MutableComponent msg = Component.empty();
		msg.append(Component.literal("  \u00a7a\u25cf ").withStyle(s -> s.withColor(0x66BB6A)));
		msg.append(chromatic(name, 0x4FC3F7, 0x81C784));
		msg.append(Component.literal("\u00a77  " + done + "/" + total));

		return msg;
	}
}
