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

	public static MutableComponent tag(String label, int startRgb, int endRgb) {
		MutableComponent comp = Component.empty();
		comp.append(Component.literal("\u2726 ").withStyle(s -> s.withColor(startRgb)));
		comp.append(chromatic(label, startRgb, endRgb));
		comp.append(Component.literal(" \u2726 ").withStyle(s -> s.withColor(endRgb)));
		return comp;
	}

	public static Component taskAssigned(String questName, String itemId, int count) {
		MutableComponent msg = tag("QUEST", 0xFFA726, 0xFFD54F);
		msg.append(Component.literal("\u00a77New task in "));
		msg.append(chromatic(questName, 0x4FC3F7, 0x81C784));
		msg.append(Component.literal("\u00a77: \u00a7f"));
		msg.append(chromatic(count + "x " + displayName(itemId), 0xFFEE58, 0x81C784));
		msg.append(Component.literal(" \u00a78(\u00a7bJ\u00a78 to view)"));
		return msg;
	}

	public static Component taskCompleted(String playerName, String itemId, int count) {
		MutableComponent msg = tag("TASK COMPLETE", 0x66BB6A, 0x81C784);
		msg.append(chromatic(playerName, 0xFFD54F, 0xFFA726));
		msg.append(Component.literal("\u00a77 collected "));
		msg.append(chromatic(count + "x " + displayName(itemId), 0x81C784, 0x4FC3F7));
		msg.append(Component.literal("\u00a7a \u2714"));
		return msg;
	}

	public static Component questCompleted(String questName) {
		MutableComponent msg = tag("GOAL COMPLETE", 0xFFD54F, 0xFF7043);
		msg.append(chromatic(questName, 0xFFEE58, 0x81C784));
		msg.append(Component.literal("\u00a77 completed by the team! \u00a76\u2605"));
		return msg;
	}

	public static String displayName(String itemId) {
		String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		path = path.replace('_', ' ');
		return path.isEmpty() ? itemId : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	// --- command output ------------------------------------------------------

	/** The {@code ✦ QUESTBOOK ✦} brand tag every command line opens with. */
	public static MutableComponent brand() {
		return tag("QUESTBOOK", 0xFFA726, 0xFFD54F);
	}

	/** A command that did what it was asked. */
	public static Component success(String message) {
		MutableComponent msg = brand();
		msg.append(Component.literal("\u00a7a "));
		msg.append(Component.literal(message));
		return msg;
	}

	/** A command that could not be carried out. */
	public static Component failure(String message) {
		MutableComponent msg = brand();
		msg.append(Component.literal("\u00a7c "));
		msg.append(Component.literal(message));
		return msg;
	}

	/** Plain command output: neither good nor bad news. */
	public static Component info(String message) {
		MutableComponent msg = brand();
		msg.append(Component.literal("\u00a7f "));
		msg.append(Component.literal(message));
		return msg;
	}

	/** An indented continuation line under {@link #success} or {@link #info}. */
	public static Component sub(String message) {
		return Component.literal("   \u00a78\u00bb \u00a77" + message);
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
