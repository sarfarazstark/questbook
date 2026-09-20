package com.questbook.client.hud;

import com.questbook.client.ClientQuestState;
import com.questbook.network.QuestSyncPayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Draws the player's pinned tasks in the corner of the screen.
 *
 * <p>Only pinned tasks appear. An empty overlay is deliberate when nothing is
 * pinned: a player who has not chosen to pin anything should see no HUD at all.
 *
 * <p>Kept deliberately small — a single compact line per task, no header and no
 * panel, so it never competes with the game view. The label is truncated when it
 * would run into the count.
 */
public final class QuestHud {
	/** Tasks beyond this are dropped, so a player cannot cover the screen. */
	private static final int MAX_ROWS = 5;

	private static final int MARGIN = 3;
	private static final int LINE_HEIGHT = 9;
	private static final int BAR_HEIGHT = 1;
	private static final int GAP = 3;

	/** Labels longer than this are cut, keeping the line compact. */
	private static final int MAX_LABEL_WIDTH = 72;

	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFCCCCCC;
	private static final int TEXT_DONE = 0xFF55FF55;
	private static final int BAR_FILLED = 0xFF55FF55;
	private static final int BAR_EMPTY = 0x80404040;

	/**
	 * Chrome pair for the label: a bright face with a steel-grey shadow.
	 *
	 * <p>Applied as a shadow colour rather than a second draw call, so the label
	 * keeps its metallic edge on any background. Both values carry full ARGB —
	 * the same alpha-byte rule as the book's text colours.
	 */
	private static final int CHROME_FACE = 0xFFF7F9FC;
	private static final int CHROME_SHADOW = 0xFF3E434B;

	/** Chrome text for the label: bright face, steel shadow. */
	private static Component chrome(String text, int face, int shadow) {
		return Component.literal(text).withStyle(net.minecraft.network.chat.Style.EMPTY
				.withColor(face)
				.withShadowColor(shadow));
	}

	private QuestHud() {
	}

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();

		// Nothing to show before the world loads. The F1 hidden-HUD state is
		// handled by vanilla's layer tree, so it needs no check here.
		if (client.player == null) {
			return;
		}

		List<QuestSyncPayload.TaskEntry> pinned = ClientQuestState.pinnedTasks();

		if (pinned.isEmpty()) {
			return;
		}

		Font font = client.font;
		int x = MARGIN;
		int y = MARGIN;
		int shown = 0;

		for (QuestSyncPayload.TaskEntry task : pinned) {
			if (shown++ >= MAX_ROWS) {
				break;
			}

			drawRow(graphics, font, task, x, y);
			y += LINE_HEIGHT;
		}
	}

	/** One pinned task: a chrome label, then the count, with a thin bar beneath. */
	private static void drawRow(GuiGraphicsExtractor graphics, Font font,
			QuestSyncPayload.TaskEntry task, int x, int y) {
		String label = trim(font, task.label());
		String count = task.have() + "/" + task.need();

		// A completed task keeps the chrome but shifts its face to green, so the
		// metallic look is consistent across states.
		int face = task.complete() ? TEXT_DONE : CHROME_FACE;
		graphics.text(font, chrome(label, face, CHROME_SHADOW), x, y, 0xFFFFFFFF, false);

		// The count follows the label's actual width rather than a fixed offset,
		// so the two can never overlap.
		int countX = x + font.width(label) + GAP;
		graphics.text(font, count, countX, y, TEXT_DIM, true);

		// A thin bar under the whole row, spanning label and count.
		int barY = y + LINE_HEIGHT - BAR_HEIGHT - 1;
		int barWidth = font.width(label) + GAP + font.width(count);
		int filled = task.need() <= 0 ? 0 : barWidth * task.have() / task.need();

		graphics.fill(x, barY, x + barWidth, barY + BAR_HEIGHT, BAR_EMPTY);
		graphics.fill(x, barY, x + filled, barY + BAR_HEIGHT, BAR_FILLED);
	}

	/** Truncates a label to {@link #MAX_LABEL_WIDTH}, with an ellipsis. */
	private static String trim(Font font, String label) {
		if (font.width(label) <= MAX_LABEL_WIDTH) {
			return label;
		}

		String cut = label;

		while (!cut.isEmpty() && font.width(cut + "\u2026") > MAX_LABEL_WIDTH) {
			cut = cut.substring(0, cut.length() - 1);
		}

		return cut + "\u2026";
	}
}
