package com.questbook.client.gui;

import com.questbook.client.ClientQuestState;
import com.questbook.network.QuestSyncPayload;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The player's task book: vanilla's own book sprite, with a scrolling task list.
 *
 * <p>Blits <em>only</em> the book region of {@code textures/gui/book.png}, not the
 * whole file. The file is 256x256 but the book occupies just 146x180 at (20,1),
 * with the rest transparent margin whose left/right and top/bottom widths differ.
 * Blitting the whole file centres the margin, which makes the visible book sit
 * left and high, and shrinks it by about a quarter. Cropping to the sprite fixes
 * both, and makes the on-screen book a predictable {@code 146x180 x scale}.
 *
 * <p>Page insets are then simple multiples of the sprite's own page rect, measured
 * at sprite (6,7)..(135,170).
 *
 * <p>The X is plain text in the book's leather brown, drawn inside the frame, with
 * no button and no background.
 */
public class QuestBookScreen extends Screen {
	/** Vanilla's book sprite. */
	private static final net.minecraft.resources.Identifier BOOK_TEXTURE =
			net.minecraft.resources.Identifier.withDefaultNamespace("textures/gui/book.png");

	// --- the book's region within the 256x256 file (measured) ---
	private static final int TEX_SIZE = 256;
	private static final int SPRITE_X = 20;
	private static final int SPRITE_Y = 1;
	private static final int SPRITE_W = 146;
	private static final int SPRITE_H = 180;

	// --- the page's usable text area, within the sprite ---
	// From a pixel scan of the sprite, left to right:
	//   x 0..5  frame    x 6..7  parchment    x 8..9  SPINE RIBBON    x 10+  page
	// Text must clear the spine, not merely the frame, so it starts well past
	// x=10. The top inset clears the frame and the rounded corner.
	private static final int PAGE_X = 18;
	private static final int PAGE_Y = 16;
	private static final int PAGE_RIGHT_INSET = SPRITE_W - 135 - 1;
	private static final int PAGE_BOTTOM_INSET = SPRITE_H - 170 - 1;

	/** Text metrics. Vanilla's page style is black with no shadow. */
	private static final int LINE_HEIGHT = 10;

	/**
	 * The close glyph, matching the editor's close and delete crosses.
	 *
	 * <p>Kept as its own constant rather than shared with the editor: this one is
	 * magnified via the pose matrix, so its hit-box is computed from its own metrics.
	 */
	private static final String CLOSE_GLYPH = "\u2715";

	/** Leather brown, from the book's own border band. */
	private static final int LEATHER = 0xFF652816;
	private static final int LEATHER_HOVER = 0xFF9C4327;

	private static final int TEXT = 0xFF000000;
	private static final int TEXT_DIM = 0xFF6D4C41;
	private static final int TEXT_DONE = 0xFF2E7D32;
	private static final int TEXT_PINNED = 0xFFC62828;

	/**
	 * Chrome pair for the headline: a near-white face with a steel-grey shadow.
	 *
	 * <p>Applied as a shadow colour rather than a second draw call. Both values
	 * need full ARGB — see the note on {@link #TEXT} — or they render invisible.
	 */


	/** True while this is the open screen, so the keybind does not stack copies. */
	private static boolean open;

	private double scroll;
	private int contentHeight;

	private int bookLeft;
	private int bookTop;
	private int bookWidth;
	private int bookHeight;
	private int pageLeft;
	private int pageTop;
	private int pageRight;
	private int pageBottom;

	public QuestBookScreen() {
		super(Component.literal("Quest Book"));
	}

	public static boolean isOpen() {
		return open;
	}

	/**
	 * Largest whole scale the sprite fits at, capped at 2.
	 *
	 * <p>The sprite is 146x180. At auto GUI scale the available area can be as
	 * small as a few hundred pixels, so this must never return a size larger than
	 * the screen — a book that overflows is worse than a small one.
	 */
	private static int scaleFor(int guiWidth, int guiHeight) {
		// Leave a margin so the book's edge is not flush against the window.
		int usableWidth = guiWidth - 16;
		int usableHeight = guiHeight - 16;

		int byWidth = usableWidth / SPRITE_W;
		int byHeight = usableHeight / SPRITE_H;

		return Mth.clamp(Math.min(byWidth, byHeight), 1, 2);
	}

	@Override
	protected void init() {
		open = true;

		// Size to the real sprite, then centre it.
		int scale = scaleFor(width, height);
		bookWidth = SPRITE_W * scale;
		bookHeight = SPRITE_H * scale;

		bookLeft = (width - bookWidth) / 2;
		bookTop = (height - bookHeight) / 2;

		pageLeft = bookLeft + PAGE_X * scale;
		pageTop = bookTop + PAGE_Y * scale;
		pageRight = bookLeft + bookWidth - PAGE_RIGHT_INSET * scale;
		pageBottom = bookTop + bookHeight - PAGE_BOTTOM_INSET * scale;
	}

	// --- drawing -------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Debug capture only: a tooltip exists solely while hovering, so a shot cannot
		// reproduce one without a parked pointer.
		if (hoverX >= 0) {
			mouseX = hoverX;
			mouseY = hoverY;
		}

		// Blit the sprite's own region: u,v must be the sprite's offset in the
		// file (20,1), not 0,0. Passing 0,0 samples the transparent margin, which
		// shows an empty book and shifts the visible art.
		g.blit(RenderPipelines.GUI_TEXTURED, BOOK_TEXTURE, bookLeft, bookTop,
				(float) SPRITE_X, (float) SPRITE_Y,
				bookWidth, bookHeight, SPRITE_W, SPRITE_H, TEX_SIZE, TEX_SIZE);

		drawContent(g, mouseX, mouseY);
		drawClose(g, mouseX, mouseY);

		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	private void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<QuestSyncPayload.QuestEntry> open = ClientQuestState.openQuests();

		if (open.isEmpty()) {
			// Everything assigned is done, or nothing was ever assigned. Either way
			// there is no list to draw, and the empty state says so.
			drawEmptyState(g);
			contentHeight = 0;
			return;
		}

		int listTop = listTop();
		int y = listTop - (int) scroll;

		for (QuestSyncPayload.QuestEntry quest : open) {
			y = drawQuestHeader(g, quest, listTop, y);

			for (QuestSyncPayload.TaskEntry task : quest.tasks()) {
				y = drawTask(g, task, listTop, y, mouseX, mouseY);
			}
		}

		// Rows are drawn first, then the header strip is masked and the header
		// redrawn on top. Without the mask a scrolled row slides through the
		// "My Tasks" line and the two overlap.
		drawHeaderMask(g);
		drawHeader(g);
		drawScrollbar(g, listTop);

		contentHeight = (y + (int) scroll) - listTop;
	}

	/**
	 * The fixed header line: title on the left, remaining count on the right.
	 *
	 * <p>Shows what is left, not what is done. The list below only contains open work
	 * now, so a done/total tally would disagree with every row a player can see.
	 */
	private void drawHeader(GuiGraphicsExtractor g) {
		g.text(font, "My Tasks", pageLeft, pageTop, TEXT, false);

		// The tally sits left of the close glyph, which owns the page's top-right.
		String tally = ClientQuestState.openTasks() + " left";
		int tallyRight = closeX() - 6;

		g.text(font, tally, tallyRight - font.width(tally), pageTop, TEXT_DIM, false);
	}

	/**
	 * What a player with no tasks sees.
	 *
	 * <p>Drawn on the page rather than left blank, so an empty book is
	 * self-explanatory instead of looking broken.
	 */
	private void drawEmptyState(GuiGraphicsExtractor g) {
		int y = pageTop + 6;

		g.text(font, "Quest Book", pageLeft, y, TEXT, false);
		y += LINE_HEIGHT * 3;

		g.text(font, "No tasks assigned", pageLeft, y, TEXT_DIM, false);
		y += LINE_HEIGHT;
		g.text(font, "to you yet.", pageLeft, y, TEXT_DIM, false);
		y += LINE_HEIGHT * 2;

		g.text(font, "An operator can add", pageLeft, y, TEXT_DIM, false);
		y += LINE_HEIGHT;
		g.text(font, "some with:", pageLeft, y, TEXT_DIM, false);
		y += LINE_HEIGHT;
		g.text(font, "/questbook add", pageLeft, y, TEXT_DIM, false);
	}

	/**
	 * Right inset from the parchment edge.
	 *
	 * <p>Mirrors {@link #PAGE_X} on the left: the count is right-aligned to this,
	 * not to the parchment edge, so it does not crowd the frame.
	 */
	private static final int PAGE_RIGHT_PAD = 8;

	/** The right edge of the text column, inside the parchment. */
	private int textRight() {
		return pageRight - PAGE_RIGHT_PAD;
	}

	/**
	 * One task as a single row: marker, label, progress.
	 *
	 * <p>Rows outside the page are skipped rather than drawn and clipped, so text
	 * can never bleed over the frame.
	 *
	 * @return the next y
	 */
	private int drawTask(GuiGraphicsExtractor g, QuestSyncPayload.TaskEntry task, int listTop, int y,
			int mouseX, int mouseY) {
		if (y + LINE_HEIGHT > pageTop && y < pageBottom) {
			// Pinned rows get their own marker, so the state is visible without
			// opening the HUD.
			String marker = task.pinned() ? "\u2022 " : task.complete() ? "\u2714 " : "\u25CB ";
			String count = task.have() + "/" + task.need();
			int labelColour = task.pinned() ? TEXT_PINNED
					: task.complete() ? TEXT_DONE : TEXT;

			// Two hover zones: the marker is a control, the rest of the row is text.
			boolean overMarker = mouseX >= pageLeft - 2 && mouseX <= pageLeft + MARKER_WIDTH
					&& mouseY >= y && mouseY < y + LINE_HEIGHT;
			boolean overRow = !overMarker
					&& mouseX > pageLeft + MARKER_WIDTH && mouseX <= textRight()
					&& mouseY >= y && mouseY < y + LINE_HEIGHT;

			if (overMarker || overRow) {
				g.fill(pageLeft - 2, y - 1, textRight() + 2, y + LINE_HEIGHT - 1, HOVER_BAND);
			}

			// The label gets the column width minus whatever the count needs, measured
			// from pageLeft.
			int room = textRight() - pageLeft - font.width(count) - LABEL_GAP;
			g.text(font, clamp(marker + task.label(), room), pageLeft, y, labelColour, false);
			g.text(font, count, textRight() - font.width(count), y,
					task.complete() ? TEXT_DONE : TEXT_DIM, false);

			// Two tooltips, one line each.
			//
			// The marker's names the control. The row's shows the full name — which is
			// the only place a clamped name is readable in full. Neither repeats the
			// count: it is already in the right-hand column, and duplicating it was
			// what made the box two lines tall and cover the list it described.
			if (overMarker && !task.complete()) {
				g.setTooltipForNextFrame(font,
						Component.literal(task.pinned() ? "Unpin from HUD" : "Pin to HUD"), mouseX, mouseY);
			} else if (overRow) {
				g.setTooltipForNextFrame(font, Component.literal(task.label()), mouseX, mouseY);
			}
		}

		return y + LINE_HEIGHT;
	}

	/** Row tint under the pointer. Warm, translucent, so the parchment shows through. */
	private static final int HOVER_BAND = 0x2A8A5A00;

	/** Space between a left label and the right-aligned value on the same row. */
	private static final int LABEL_GAP = 6;

	/**
	 * Truncates to {@code width}, ending in an ellipsis when anything was dropped.
	 *
	 * <p>Measures and cuts with the same font, so the result always fits {@code width}.
	 */
	private String clamp(String text, int width) {
		return clampPlain(text, width, false);
	}

	/**
	 * As {@link #clamp}, optionally budgeting for bold glyphs.
	 *
	 * <p>Bold is roughly one pixel per character wider than plain. Measuring the plain
	 * string and drawing the bold one therefore overflows by a character or two, which
	 * is exactly the overlap this is meant to prevent — so a bold caller asks for the
	 * allowance rather than being handed a wrong number.
	 */
	private String clampPlain(String text, int width, boolean bold) {
		int budget = bold ? width - text.length() : width;

		if (font.width(text) <= budget) {
			return text;
		}

		return font.plainSubstrByWidth(text, Math.max(0, budget - font.width("\u2026"))) + "\u2026";
	}

	/**
	 * A quest's title row: name on the left, completion tally on the right.
	 *
	 * <p>Without this row the book is a flat list of item names, and the player
	 * cannot tell which task belongs to which quest or what a quest is worth.
	 * Bold, with a gap beneath, so the rows under it read as its children.
	 */
	private int drawQuestHeader(GuiGraphicsExtractor g, QuestSyncPayload.QuestEntry quest,
			int listTop, int y) {
		if (y + LINE_HEIGHT > pageTop && y < pageBottom) {
			int done = (int) quest.tasks().stream()
					.filter(QuestSyncPayload.TaskEntry::complete).count();
			String tally = done + "/" + quest.tasks().size();

			// Clamped so a long quest name cannot run under its own tally. Measured on
			// the bold string, because bold glyphs are wider than the plain equivalent
			// the clamp would otherwise budget for.
			int room = textRight() - pageLeft - font.width(tally) - LABEL_GAP;
			String shown = clampPlain(quest.name(), room, true);
			g.text(font, bold(shown), pageLeft, y, TEXT, false);
			g.text(font, tally, textRight() - font.width(tally), y, TEXT_DIM, false);
		}

		return y + LINE_HEIGHT + QUEST_GAP;
	}

	/** Space under a quest header, so its tasks read as children. */
	private static final int QUEST_GAP = 2;

	private static net.minecraft.network.chat.Component bold(String text) {
		return net.minecraft.network.chat.Component.literal(text)
				.withStyle(net.minecraft.network.chat.Style.EMPTY.withBold(true));
	}

	/** A thin bar at the page's right edge, only when there is more to see. */
	private void drawScrollbar(GuiGraphicsExtractor g, int listTop) {
		int visible = pageBottom - listTop;

		if (contentHeight <= visible || visible <= 0) {
			return;
		}

		int maxScroll = contentHeight - visible;
		int thumbHeight = Math.max(10, visible * visible / Math.max(1, contentHeight));
		int thumbY = listTop + (int) ((visible - thumbHeight) * (scroll / Math.max(1, maxScroll)));

		g.fill(pageRight + 2, listTop, pageRight + 3, pageBottom, 0x30000000);
		g.fill(pageRight + 2, thumbY, pageRight + 3, thumbY + thumbHeight, LEATHER);
	}

	/**
	 * The close control: a lowercase {@code x}, drawn as text in the page's corner.
	 *
	 * <p>Deliberately a plain letter rather than a symbol glyph. Minecraft's font
	 * has no {@code U+2716} (✖), and the substitutes it does have ({@code U+2718},
	 * {@code U+274C}) are wide, heavy marks from the nonlatin bitmap sheet that
	 * dominate the corner. A letter reads as a close control without the weight.
	 *
	 * <p>Enlarged via the pose matrix, since {@code text} has no scale parameter.
	 */
	private void drawClose(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = closeX();
		int y = closeY();
		boolean hovered = mouseX >= x - 2 && mouseX <= x + closeWidth()
				&& mouseY >= y - 2 && mouseY <= y + closeHeight();

		var glyph = Component.literal(CLOSE_GLYPH)
				.withStyle(net.minecraft.network.chat.Style.EMPTY
						.withColor(hovered ? LEATHER_HOVER : LEATHER));

		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) x, (float) y);
		pose.scale(CLOSE_SCALE, CLOSE_SCALE);
		g.text(font, glyph, 0, 0, 0xFFFFFFFF, false);
		pose.popMatrix();
	}

	// --- input ---------------------------------------------------------------

	/** Magnification; the font has one size, so scale comes from the pose matrix. */
	private static final float CLOSE_SCALE = 1.4f;

	/** Inset from the parchment's right edge; negative moves it further right. */
	private static final int CLOSE_INSET_X = -4;

	/** On-screen size of the magnified glyph, for hit-testing. */
	private int closeWidth() {
		return (int) (font.width(CLOSE_GLYPH) * CLOSE_SCALE) + 4;
	}

	private int closeHeight() {
		return (int) (LINE_HEIGHT * CLOSE_SCALE);
	}

	private int closeX() {
		return textRight() - closeWidth() - CLOSE_INSET_X;
	}

	/**
	 * Covers the header strip with page-coloured bands, so a scrolled row cannot
	 * slide up through the "My Tasks" line.
	 *
	 * <p>The header is drawn last, but that alone is not enough: an incoming row is
	 * still visible above and below the header text. Filling the strip first, in
	 * the page's own tones, hides it completely. The bands follow the sprite's
	 * horizontal gradient so no seam shows.
	 */
	private void drawHeaderMask(GuiGraphicsExtractor g) {
		int top = pageTop - 6;
		int bottom = listTop();
		int right = textRight() + 8;

		// Measured from the sprite across the header row: darker toward the spine,
		// brightest through the middle, slightly warmer at the right.
		g.fill(pageLeft - 8, top, pageLeft + 30, bottom, 0xFFFDF6E5);
		g.fill(pageLeft + 30, top, pageLeft + 70, bottom, 0xFFFFF9ED);
		g.fill(pageLeft + 70, top, right - 20, bottom, 0xFFFDF8EB);
		g.fill(right - 20, top, right, bottom, 0xFFFCF3DD);
	}

	/** Y just below the header line, where the scrolling list may begin. */
	private int listTop() {
		return pageTop + LINE_HEIGHT + 6;
	}

	/**
	 * Y of the close glyph: on the header line, aligned with "My Tasks".
	 *
	 * <p>Sharing the line is intentional, but the tally is shifted left to leave
	 * the glyph the right-hand corner to itself.
	 */
	private int closeY() {
		return pageTop - 2;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		int x = closeX();
		int y = closeY();

		if (event.x() >= x - 2 && event.x() <= x + closeWidth()
				&& event.y() >= y - 2 && event.y() <= y + closeHeight()) {
			onClose();
			return true;
		}

		if (clickMarker(event.x(), event.y())) {
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * Toggles the pin on the task whose marker was clicked.
	 *
	 * <p>The marker is the {@code ○}/{@code ✔} at the left of each row, so pinning
	 * needs no extra glyph column. Hit-testing re-walks the rows in the same order
	 * the renderer drew them.
	 */
	private boolean clickMarker(double mouseX, double mouseY) {
		if (mouseX < pageLeft || mouseX > pageLeft + MARKER_WIDTH) {
			return false;
		}

		int listTop = listTop();
		int y = listTop - (int) scroll;

		for (QuestSyncPayload.QuestEntry quest : ClientQuestState.openQuests()) {
			// The quest's own title row occupies this space; it is not a task and
			// so must not be pin-targetable.
			y += LINE_HEIGHT + QUEST_GAP;

			for (QuestSyncPayload.TaskEntry task : quest.tasks()) {
				if (mouseY >= y && mouseY < y + LINE_HEIGHT) {
					sendPin(task);
					return true;
				}

				y += LINE_HEIGHT;
			}
		}

		return false;
	}

	/** Asks the server to flip this task's pin. */
	private void sendPin(QuestSyncPayload.TaskEntry task) {
		if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
				.canSend(com.questbook.network.PinTaskPayload.TYPE)) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new com.questbook.network.PinTaskPayload(
							java.util.UUID.fromString(task.id()), !task.pinned()));
		}
	}

	/** Clickable width of the row marker. */
	private static final int MARKER_WIDTH = 10;

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// Computed from state, not read from the last draw: mouse input is handled
		// before rendering, so a value captured during drawContent would be one
		// frame stale and the first wheel notch after opening would be swallowed.
		int content = listContentHeight();

		int listTop = listTop();
		int visible = pageBottom - listTop;
		int maxScroll = Math.max(0, content - visible);

		scroll = Mth.clamp(scroll - scrollY * LINE_HEIGHT * 2, 0, maxScroll);

		return true;
	}

	/**
	 * Total scrollable height of the list, counting the quest title rows.
	 *
	 * <p>Must match what {@link #drawContent} lays out exactly: a header row plus
	 * its gap for each quest, then one row per task. If the two disagree the
	 * scrollbar thumb and the wheel clamp drift from the visible rows.
	 */
	private int listContentHeight() {
		int rows = 0;

		for (QuestSyncPayload.QuestEntry quest : ClientQuestState.openQuests()) {
			rows += 1 + quest.tasks().size();
		}

		return rows * LINE_HEIGHT + ClientQuestState.openQuests().size() * QUEST_GAP;
	}

	@Override
	public void onClose() {
		open = false;
		super.onClose();
	}

	// --- DEBUG HOOKS (development only; used by EditorDebugDriver) ----------

	/**
	 * Parked pointer for captures. Synthetic mouse input is discarded by the client,
	 * so a hover-only surface such as a tooltip cannot be photographed any other way.
	 * -1 means "use the real cursor".
	 */
	private int hoverX = -1;
	private int hoverY = -1;

	/** Rows drawn so far, so a hook can aim at one without duplicating the walk. */
	private int debugFirstTaskY() {
		int y = listTop() - (int) scroll;

		for (QuestSyncPayload.QuestEntry quest : ClientQuestState.quests()) {
			y += LINE_HEIGHT + QUEST_GAP;

			if (!quest.tasks().isEmpty()) {
				return y;
			}
		}

		return -1;
	}

	/** Hovers the first task's label, exercising the row tooltip. */
	public void debugHoverFirstTask() {
		int y = debugFirstTaskY();

		if (y >= 0) {
			hoverX = pageLeft + MARKER_WIDTH + 4;
			hoverY = y + 2;
		}
	}

	/** Hovers the first task's marker, exercising the pin tooltip. */
	public void debugHoverFirstMarker() {
		int y = debugFirstTaskY();

		if (y >= 0) {
			hoverX = pageLeft + 2;
			hoverY = y + 2;
		}
	}

	public void debugClearHover() {
		hoverX = -1;
		hoverY = -1;
	}

	public String debugState() {
		return "quests=" + ClientQuestState.quests().size()
				+ " tasks=" + ClientQuestState.totalTasks()
				+ " scale=" + scaleFor(width, height)
				+ " page=" + pageLeft + "," + pageTop + ".." + pageRight + "," + pageBottom
				+ " textRight=" + textRight()
				+ " hover=" + hoverX + "," + hoverY;
	}

	@Override
	public void removed() {
		open = false;
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
