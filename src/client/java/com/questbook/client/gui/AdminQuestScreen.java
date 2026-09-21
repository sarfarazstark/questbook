package com.questbook.client.gui;

import com.questbook.client.ClientAdminState;
import com.questbook.data.Reward;
import com.questbook.data.Task;
import com.questbook.network.AdminActionPayload;
import com.questbook.network.AdminSyncPayload;
import com.questbook.util.QuestText;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Clean, structured Admin Quest Editor screen.
 * Master list of quests on left, item picker & task editor on right.
 * Features modal dialog for new quests, inline task editing, and rich hover tooltips.
 */
public final class AdminQuestScreen extends Screen {

	// Color Palette — flat parchment theme: cream panel, dark ink, leather accents.
	private static final int BG_BACKDROP = 0x88000000;
	private static final int PANEL_BG = 0xFFE8D9B8;
	private static final int PANEL_BORDER = 0xFF6B4A2A;
	private static final int HEADER_BG = 0xFFDCC9A0;
	/** Opaque, for popups that must not show the panel through them. */
	private static final int POPUP_BG = 0xFFF0E4C8;
	/** Input outline: leather ink, darker while focused. Background stays clear. */
	private static final int FIELD_BORDER = 0xFF8A7A5E;
	private static final int FIELD_BORDER_FOCUS = 0xFF6B4A2A;

	private static final int TEXT_WHITE = 0xFF2A2118;
	private static final int TEXT_MUTED = 0xFF5C4B33;
	private static final int TEXT_DIM = 0xFF8A7A5E;
	private static final int TEXT_GOLD = 0xFF8A5A00;
	private static final int TEXT_GREEN = 0xFF2E6B2E;
	private static final int TEXT_RED = 0xFF9B2C2C;
	private static final int TEXT_CYAN = 0xFF1F5C6B;

	private static final int CARD_BG = 0xFFDDCBA6;
	private static final int CARD_HOVER = 0xFFE6D6B4;
	private static final int CARD_SELECTED = 0xFFD3BC8C;
	private static final int CARD_SELECTED_BORDER = 0xFF8A5A00;

	private static final int BTN_PRIMARY = 0xFF6E4A26;
	private static final int BTN_PRIMARY_HOVER = 0xFF8A5F31;
	private static final int BTN_SECONDARY = 0xFFD2C09B;
	private static final int BTN_SECONDARY_HOVER = 0xFFE0D0AE;

	private static final int SLOT_BG = 0xFFD8C7A2;
	private static final int SLOT_HOVER = 0xFFE4D5B2;
	private static final int SLOT_SELECTED = 0xFFA8C08A;
	private static final int SLOT_SELECTED_BORDER = 0xFF2E6B2E;

	private static boolean open;

	public static boolean isOpen() {
		return open;
	}

	// Layout bounds
	private int panelLeft;
	private int panelTop;
	private int panelWidth = DESIGN_PANEL_W;
	private int panelHeight = DESIGN_PANEL_H;

	/** The panel size every hardcoded offset in this class is authored against. */
	private static final int DESIGN_PANEL_W = 490;
	private static final int DESIGN_PANEL_H = 236;

	/**
	 * Share of the screen the panel aims to cover. Row heights inside the panel stay
	 * native — the font has no fractional size — so this only sizes the frame.
	 */
	private static final float TARGET_SCREEN_FRACTION = 0.70f;

	private int leftPaneLeft;
	private int leftPaneTop;
	private int leftPaneWidth = 184;
	private int leftPaneHeight;

	private int rightPaneLeft;
	private int rightPaneTop;
	private int rightPaneWidth;
	private int rightPaneHeight;

	// Master State (Left Pane)
	private UUID selectedQuestId = null;
	private double leftScroll = 0;
	private int leftContentHeight = 0;

	// Editing Task State
	private UUID editingTaskId = null; // null = adding new task, non-null = editing existing

	// Right Pane (Quest Content)
	private double rightScroll = 0;
	private int rightContentHeight = 0;

	// Item Picker Modal State
	private boolean itemPickerOpen = false;
	private InkField itemSearchField;
	private InkField countField;

	private int selectedTab = 0;
	private int selectedGridIndex = -1;
	private ItemStack selectedItem = ItemStack.EMPTY;
	private double pickerScroll = 0;
	private int pickerContentHeight = 0;

	// Assignee Selection
	private UUID selectedAssigneeId = Task.UNASSIGNED;
	private String selectedAssigneeName = "Unassigned";
	private boolean assigneeDropdownOpen = false;
	private int assigneeDropdownX = 0;
	private int assigneeDropdownY = 0;

	// New Quest Dialog State (Modal)
	private boolean newQuestDialogOpen = false;
	private InkField newQuestNameField;

	public AdminQuestScreen() {
		super(Component.literal("Quest Editor"));
	}

	// --- ITEM PICKER MODAL GEOMETRY -----------------------------------------

	/**
	 * The picker is sized relative to the panel so the two scale together; a fixed
	 * picker would swallow a smaller panel. Floors keep the 18px grid clickable.
	 */
	private static final int PICKER_MIN_W = 210;
	private static final int PICKER_MIN_H = 176;

	/**
	 * Single horizontal inset for the picker's contents. The search field, tab strip,
	 * item grid, scrollbar and footer row all align to it, so they share one left
	 * edge and one right edge instead of each computing its own.
	 */
	private static final int PICKER_PAD = 8;

	/** Right edge of the picker's content area. */
	private int pickerContentRight() {
		return pickerLeft() + pickerWidth() - PICKER_PAD;
	}

	/** Width of the content area, matching {@link #pickerContentRight()}. */
	private int pickerContentWidth() {
		return pickerWidth() - PICKER_PAD * 2;
	}

	/** Count field box, derived from the drawn label so the two cannot disagree. */
	private static final String AMOUNT_LABEL = "Amount:";
	private static final int AMOUNT_FIELD_W = 54;
	private static final int LABEL_GAP = 6;

	private int amountFieldX() {
		return gridLeft() + font.width(AMOUNT_LABEL) + LABEL_GAP;
	}

	private int pickerWidth() {
		return Math.max(PICKER_MIN_W, panelWidth - 16);
	}

	private int pickerHeight() {
		return Math.max(PICKER_MIN_H, panelHeight - 16);
	}

	private int slotSize() {
		return 18;
	}

	private int pickerLeft() {
		return Math.max(4, (width - pickerWidth()) / 2);
	}

	private int pickerTop() {
		return Math.max(4, (height - pickerHeight()) / 2);
	}

	private int pickerTabsY() {
		return pickerTop() + 24;
	}

	private int pickerSearchY() {
		return pickerTabsY() + 18;
	}

	private int pickerGridY() {
		return pickerSearchY() + 17;
	}

	private int pickerBottomY() {
		return pickerTop() + pickerHeight() - 17;
	}

	/** Columns that fit the content area; the grid never overflows or under-fills it. */
	private int gridCols() {
		return Math.max(1, pickerContentWidth() / slotSize());
	}

	/** Width actually occupied by the grid, i.e. whole columns only. */
	private int gridWidth() {
		return gridCols() * slotSize();
	}

	/**
	 * Left edge of the grid. Whole 18px columns never fill the content area exactly,
	 * so the remaining slack is split evenly: the grid ends up centred in the modal
	 * rather than hugging the left inset and leaving a visible gap on the right.
	 */
	private int gridLeft() {
		return pickerLeft() + (pickerWidth() - gridWidth()) / 2;
	}

	private int gridRows() {
		return Math.max(1, (pickerBottomY() - pickerGridY() - 6) / slotSize());
	}

	/** Right edge of the grid, i.e. the column every footer control aligns to. */
	private int gridRight() {
		return gridLeft() + gridWidth();
	}

	@Override
	protected void init() {
		open = true;
		ItemCatalog.init();

		// Fit the panel to a share of the screen, never growing past its design size:
		// the rows inside are authored for native 9px glyphs and cannot scale up.
		float fit = Math.min(width * TARGET_SCREEN_FRACTION / DESIGN_PANEL_W,
				height * TARGET_SCREEN_FRACTION / DESIGN_PANEL_H);

		panelWidth = Math.min(width - 16, Math.round(DESIGN_PANEL_W * fit));
		panelHeight = Math.min(height - 16, Math.round(DESIGN_PANEL_H * fit));
		panelLeft = (width - panelWidth) / 2;
		panelTop = (height - panelHeight) / 2;

		int headerH = 22;
		int contentY = panelTop + headerH + 2;
		int contentH = panelHeight - headerH - 4;

		leftPaneLeft = panelLeft + 4;
		leftPaneTop = contentY;
		leftPaneWidth = 118;
		leftPaneHeight = contentH;

		rightPaneLeft = leftPaneLeft + leftPaneWidth + 6;
		rightPaneTop = leftPaneTop;
		rightPaneWidth = panelLeft + panelWidth - 4 - rightPaneLeft;
		rightPaneHeight = leftPaneHeight;

		// Default selection to first quest if none selected
		ensureSelection();

		initControls();
	}

	/**
	 * Keeps a quest selected. Called on init and whenever a sync lands, because the
	 * first sync can arrive after the screen opens — in which case there was nothing
	 * to select at init time and the pane would stay empty forever.
	 */
	private void ensureSelection() {
		List<AdminSyncPayload.AdminQuestEntry> quests = ClientAdminState.quests();
		if (quests.isEmpty()) {
			return;
		}
		boolean stillPresent = selectedQuestId != null
				&& quests.stream().anyMatch(q -> q.id().equals(selectedQuestId));
		if (!stillPresent) {
			selectedQuestId = quests.get(0).id();
		}
	}

	/**
	 * Turns off the vanilla sprite (that is what paints the opaque fill and frame)
	 * and switches to ink text. {@link InkField} draws its own outline instead, so
	 * the panel shows through the input.
	 */
	private void styleField(EditBox field) {
		field.setBordered(false);
		field.setTextColor(0xFF2A2118);
		field.setTextColorUneditable(TEXT_DIM);
		field.setTextShadow(false);
	}

	private void initControls() {
		clearWidgets();

		// Search Field — shares the grid's box exactly (same left edge, same width),
		// so field, grid and scrollbar all line up instead of three separate insets.
		int searchY = pickerSearchY();
		itemSearchField = new InkField(font, gridLeft(), searchY, gridWidth(), 14);
		itemSearchField.setHintText("Search items & blocks...");
		itemSearchField.setMaxLength(32);
		styleField(itemSearchField);
		itemSearchField.setResponder(s -> {
			pickerScroll = 0;
			selectedGridIndex = -1;
		});
		addRenderableWidget(itemSearchField);

		// Amount / Count Input Box — same x the render and click paths use, so the
		// widget and its hit-test cannot drift apart. The value is centred: the box is
		// wide enough for six digits, so a two-digit count would otherwise hug the
		// left border and leave most of the box empty.
		int bottomY = pickerBottomY();
		countField = new InkField(font, amountFieldX(), bottomY, AMOUNT_FIELD_W, 14, true);
		countField.setValue("16");
		countField.setMaxLength(6);
		styleField(countField);
		addRenderableWidget(countField);

		// Modal New Quest Name Field (generous width)
		int modalW = 240;
		int modalH = 88;
		int modalX = (width - modalW) / 2;
		int modalY = (height - modalH) / 2;
		newQuestNameField = new InkField(font, modalX + 12, modalY + 30, modalW - 24, 16);
		newQuestNameField.setHintText("Enter quest name...");
		newQuestNameField.setMaxLength(64);
		styleField(newQuestNameField);
		addRenderableWidget(newQuestNameField);
		newQuestNameField.visible = newQuestDialogOpen;
	}

	/**
	 * EditBox draws its frame AND fill as one opaque sprite. The panel should show
	 * through, so the sprite is switched off and a plain outline is drawn instead:
	 * border kept, background transparent.
	 *
	 * <p>Narration is empty because the field shows its own value/hint.
	 *
	 * <p>The position is pinned on every render. EditBox recentres itself when it is
	 * resized or its value changes, which moved the field out from under the label it
	 * was placed after.
	 *
	 * <p>{@code centered} hands horizontal centring to EditBox's own
	 * {@code setCentered}. Only the vertical shift is ours: unbordering the field
	 * also strips EditBox's vertical centring, so the text would sit flush against
	 * the top border.
	 *
	 * <p>The hint is drawn here rather than through {@code EditBox.setHint}: vanilla
	 * routes the hint through {@code GuiGraphicsExtractor.text(Font, Component, x, y,
	 * color)}, the 5-arg overload, which hardcodes a drop shadow. A {@code setTextShadow}
	 * call does not reach that path, so the hint came out as two overlapping ink passes —
	 * a doubled ghost — while every other string on the screen drew once. Drawing it here
	 * also buys the left inset an unbordered box loses.
	 */
	private static final class InkField extends EditBox {
		/** Pixels between the left border and the first glyph. */
		private static final int TEXT_PAD = 4;

		private final int pinnedX;
		private final int pinnedY;
		private final Font textFont;
		/** Drawn when the value is empty; null when the field has no placeholder. */
		private String hint;
		private final boolean centered;

		InkField(Font font, int x, int y, int w, int h) {
			this(font, x, y, w, h, false);
		}

		InkField(Font font, int x, int y, int w, int h, boolean centered) {
			super(font, x, y, w, h, Component.empty());
			this.pinnedX = x;
			this.pinnedY = y;
			this.textFont = font;
			this.centered = centered;
			setCentered(centered);
		}

		/** Placeholder for an empty field. Blank or null hides it. */
		void setHintText(String text) {
			this.hint = text == null || text.isEmpty() ? null : text;
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			// x carries the inset because EditBox derives textX from getX() in
			// updateTextPosition (getX() + (centered ? … : bordered ? 4 : 0)), and an
			// unbordered field lands on the 0 branch. Padding getX() rather than the
			// drawn x means value, cursor, selection highlight and our hint all share
			// one origin — nudging only the hint left the value flush to the border.
			int shiftY = Math.max(0, (getHeight() - 8) / 2);
			setX(pinnedX + (centered ? 0 : TEXT_PAD));
			setY(pinnedY + shiftY);
			super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
			setX(pinnedX);
			setY(pinnedY);
			if (hint != null && getValue().isEmpty()) {
				// getX() is back on the pinned rect here, so re-apply the inset rather
				// than reading it off the widget.
				drawHint(g, pinnedY + shiftY);
			}
			int x = pinnedX;
			int y = pinnedY;
			int r = x + getWidth();
			int b = y + getHeight();
			int c = isFocused() ? FIELD_BORDER_FOCUS : FIELD_BORDER;
			g.fill(x, y, r, y + 1, c);         // top
			g.fill(x, b - 1, r, b, c);         // bottom
			g.fill(x, y, x + 1, b, c);         // left
			g.fill(r - 1, y, r, b, c);         // right
		}

		/**
		 * Same origin the value uses: {@code pinnedX + TEXT_PAD}, or EditBox's own
		 * centring. Text is confined to the frame's inner rect so a hint wider than
		 * the field is cut off at the border instead of running over it.
		 * {@code false} on the shadow flag is the whole point — see the class note.
		 */
		private void drawHint(GuiGraphicsExtractor g, int textY) {
			int left = pinnedX + 1;
			int right = pinnedX + getWidth() - 1;
			int hx = centered ? left + (right - left - textFont.width(hint)) / 2 : pinnedX + TEXT_PAD;
			g.enableScissor(left, pinnedY + 1, right, pinnedY + getHeight() - 1);
			g.text(textFont, hint, hx, textY, TEXT_DIM, false);
			g.disableScissor();
		}
	}

	// --- RENDERING -----------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// A sync can arrive while this screen is open; keep the pane populated.
		ensureSelection();

		g.fill(0, 0, width, height, BG_BACKDROP);

		// Main Studio Frame
		renderStudioFrame(g, mouseX, mouseY);

		// Left: Master Quest List
		renderLeftPane(g, mouseX, mouseY);

		// Separator
		g.fill(rightPaneLeft - 3, leftPaneTop, rightPaneLeft - 2, leftPaneTop + leftPaneHeight, PANEL_BORDER);

		// Right: Quest Content
		renderRightPane(g, mouseX, mouseY);

		// Update widget visibility
		if (itemSearchField != null) itemSearchField.visible = itemPickerOpen;
		if (countField != null) countField.visible = itemPickerOpen;
		if (newQuestNameField != null) newQuestNameField.visible = newQuestDialogOpen;

		// Modal frames (rendered BEFORE widgets so their EditBoxes are drawn on top)
		if (newQuestDialogOpen) {
			renderNewQuestDialog(g, mouseX, mouseY);
		}
		if (itemPickerOpen) {
			renderItemPickerModal(g, mouseX, mouseY);
		}

		// Render widgets (EditBox fields) on top of panels/dialog
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		// Dropdown overlays (on top of everything)
		renderAssigneeDropdown(g, mouseX, mouseY);
	}

	private void renderStudioFrame(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		// Flat panel: 1px border plus a solid body.
		g.fill(panelLeft - 1, panelTop - 1, panelLeft + panelWidth + 1, panelTop + panelHeight + 1, 0xFF000000);
		g.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BG);

		// Header Bar
		int headerH = 22;
		g.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + headerH, HEADER_BG);
		g.fill(panelLeft, panelTop + headerH, panelLeft + panelWidth, panelTop + headerH + 1, PANEL_BORDER);

		// Title
		g.text(font, "Quest Editor", panelLeft + 8, panelTop + 7, TEXT_GOLD, false);

		// Count
		int questCount = ClientAdminState.quests().size();
		String sub = questCount + (questCount == 1 ? " Quest" : " Quests");
		g.text(font, sub, panelLeft + 86, panelTop + 7, TEXT_MUTED, false);

		// [+ New Quest] Button
		int newBtnW = font.width("+ New Quest") + 8;
		int newBtnH = 14;
		int newBtnX = panelLeft + panelWidth - newBtnW - 22;
		int newBtnY = panelTop + 4;
		boolean newHover = mouseX >= newBtnX && mouseX <= newBtnX + newBtnW && mouseY >= newBtnY && mouseY <= newBtnY + newBtnH;

		g.fill(newBtnX, newBtnY, newBtnX + newBtnW, newBtnY + newBtnH, newHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY);
		g.text(font, "+ New Quest", newBtnX + 4, newBtnY + 3, TEXT_WHITE, false);

		// [✕] Close Button
		int closeX = panelLeft + panelWidth - 14;
		int closeY = panelTop + 6;
		boolean closeHover = mouseX >= closeX - 2 && mouseX <= closeX + 10 && mouseY >= closeY - 2 && mouseY <= closeY + 10;
		g.text(font, "\u2715", closeX, closeY, closeHover ? TEXT_RED : TEXT_MUTED, false);
	}

	// --- LEFT PANE (MASTER LIST) ---------------------------------------------

	private void renderLeftPane(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int listTop = leftPaneTop;
		int listBottom = leftPaneTop + leftPaneHeight;
		int y = listTop - (int) leftScroll;

		List<AdminSyncPayload.AdminQuestEntry> quests = ClientAdminState.quests();
		if (quests.isEmpty()) {
			int w = leftPaneWidth - 12;
			g.text(font, font.plainSubstrByWidth("No quests yet.", w), leftPaneLeft + 4, listTop + 8, TEXT_DIM, false);
			g.text(font, font.plainSubstrByWidth("Click '+ New Quest'.", w), leftPaneLeft + 4, listTop + 20, TEXT_DIM, false);
			leftContentHeight = 0;
			return;
		}

		// Clip to the pane so cards that start near the bottom edge cannot bleed out.
		g.enableScissor(leftPaneLeft, listTop, leftPaneLeft + leftPaneWidth, listBottom);
		for (AdminSyncPayload.AdminQuestEntry quest : quests) {
			y = renderQuestCard(g, quest, y, listTop, listBottom, mouseX, mouseY);
		}
		g.disableScissor();

		leftContentHeight = (y + (int) leftScroll) - listTop;
		renderScrollbar(g, leftPaneLeft + leftPaneWidth - 3, listTop, listBottom - listTop, leftContentHeight, leftScroll);
	}

	private int renderQuestCard(GuiGraphicsExtractor g, AdminSyncPayload.AdminQuestEntry quest, int y, int listTop, int listBottom, int mouseX, int mouseY) {
		boolean isSelected = quest.id().equals(selectedQuestId);

		int cardH = QUEST_CARD_H;
		int cardW = leftPaneWidth - 6;
		int cardX = leftPaneLeft;

		if (y + cardH > listTop && y < listBottom) {
			boolean hover = mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= y && mouseY < y + cardH && !modalOpen();
			int bg = isSelected ? CARD_SELECTED : (hover ? CARD_HOVER : CARD_BG);
			g.fill(cardX, y, cardX + cardW, y + cardH, bg);
			if (isSelected) {
				g.fill(cardX, y, cardX + 2, y + cardH, CARD_SELECTED_BORDER);
			}

			// Progress Tally (laid out first: the name takes whatever is left)
			long doneCount = quest.tasks().stream().filter(AdminSyncPayload.AdminTaskEntry::complete).count();
			String progStr = doneCount + "/" + quest.tasks().size();
			int delX = cardX + cardW - 10;
			int progW = font.width(progStr);
			int progX = delX - 4 - progW;
			g.text(font, progStr, progX, y + 4, doneCount == quest.tasks().size() && !quest.tasks().isEmpty() ? TEXT_GREEN : TEXT_MUTED, false);

			// Quest Name
			int nameMaxW = progX - 4 - (cardX + 6);
			String name = font.plainSubstrByWidth(quest.name(), Math.max(8, nameMaxW));
			g.text(font, name, cardX + 6, y + 4, isSelected ? TEXT_GOLD : TEXT_WHITE, false);

			// Delete [✕] button
			boolean delHover = mouseX >= delX - 2 && mouseX <= delX + 8 && mouseY >= y + 2 && mouseY <= y + 14 && !modalOpen();
			g.text(font, "\u2715", delX, y + 4, delHover ? TEXT_RED : TEXT_DIM, false);

			// Tooltip on quest hover
			if (hover && mouseX < delX - 4) {
				Component tip = Component.literal(quest.name() + " (" + progStr + " done) · Click to select");
				g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
			}
		}
		y += questCardPitch();

		return y;
	}

	private int renderTaskRow(GuiGraphicsExtractor g, AdminSyncPayload.AdminQuestEntry quest, AdminSyncPayload.AdminTaskEntry task, int x, int y, int w, int mouseX, int mouseY) {
		int rowH = TASK_ROW_H;
		boolean isTaskEditing = task.id().equals(editingTaskId);
		boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH && !modalOpen();

		int rowBg = isTaskEditing ? 0x502E5E3A : (hover ? 0x30000000 : 0x15000000);
		g.fill(x, y, x + w, y + rowH, rowBg);
		if (isTaskEditing) {
			g.fill(x, y, x + 2, y + rowH, SLOT_SELECTED_BORDER);
		}

		// Status
		String statusIcon = task.complete() ? "\u2714" : "\u25CB";
		int statusColor = task.complete() ? TEXT_GREEN : TEXT_DIM;
		g.text(font, statusIcon, x + 3, y + 3, statusColor, false);

		// Label & Count
		String label = font.plainSubstrByWidth(task.label() + " x" + task.need(), w - 66);
		g.text(font, label, x + 12, y + 3, isTaskEditing ? TEXT_GOLD : (task.complete() ? TEXT_MUTED : TEXT_WHITE), false);

		// Assignee Chip
		String assignee = task.assigneeName();
		int assignW = Math.min(font.width(assignee) + 4, 40);
		int assignX = x + w - assignW - 12;
		g.fill(assignX, y + 2, assignX + assignW, y + rowH - 2, 0x40000000);
		int nameColor = task.assignee().equals(Task.UNASSIGNED) ? TEXT_RED : TEXT_CYAN;
		g.text(font, font.plainSubstrByWidth(assignee, assignW - 2), assignX + 2, y + 3, nameColor, false);

		// Delete [✕]
		int delX = x + w - 8;
		boolean delHover = mouseX >= delX - 2 && mouseX <= delX + 8 && mouseY >= y + 1 && mouseY <= y + rowH - 1 && !modalOpen();
		g.text(font, "\u2715", delX, y + 3, delHover ? TEXT_RED : TEXT_DIM, false);

		// Tooltip on task hover
		if (hover && mouseX < delX - 4) {
			String action = isTaskEditing ? "Currently editing" : "Click to edit";
			Component tip = Component.literal(task.label() + " x" + task.need() + " (" + task.have() + "/" + task.need() + ") · " + action);
			g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
		}

		return y + rowH + 1;
	}

	// --- RIGHT PANE (QUEST CONTENT) ------------------------------------------

	private void renderRightPane(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		AdminSyncPayload.AdminQuestEntry quest = selectedQuest();
		if (quest == null) {
			// Wrap into the pane; a single line runs off the panel at this width.
			int w = rightPaneWidth - 12;
			g.text(font, font.plainSubstrByWidth("Select a Quest on the left", w), rightPaneLeft + 4, rightPaneTop + 8, TEXT_DIM, false);
			g.text(font, font.plainSubstrByWidth("to manage its tasks.", w), rightPaneLeft + 4, rightPaneTop + 20, TEXT_DIM, false);
			rightContentHeight = 0;
			return;
		}

		int listTop = rightPaneTop;
		int listBottom = rightPaneTop + rightPaneHeight;
		int w = rightPaneWidth - 8;
		int x = rightPaneLeft + 4;

		// Header: title, progress, reward, prerequisites, locked banner, rule.
		// Pitches are named constants so rightHeaderHeight() cannot drift from them.
		int headerH = rightHeaderHeight(quest);
		int hy = listTop - (int) rightScroll;
		int y = hy + headerH;

		long done = quest.tasks().stream().filter(AdminSyncPayload.AdminTaskEntry::complete).count();
		g.text(font, font.plainSubstrByWidth(quest.name(), w), x, hy + HDR_TITLE_Y, TEXT_GOLD, false);

		String prog = done + "/" + quest.tasks().size() + " tasks complete";
		g.text(font, prog, x, hy + HDR_PROGRESS_Y, done == quest.tasks().size() && !quest.tasks().isEmpty() ? TEXT_GREEN : TEXT_MUTED, false);

		Optional<Reward> reward = quest.reward();
		String rewardText = reward.isPresent()
				? "Reward: " + QuestText.rewardSummary(reward.get().kind().name(), reward.get().value(), reward.get().amount()).getString()
				: "Reward: none";
		g.text(font, font.plainSubstrByWidth(rewardText, w), x, hy + HDR_REWARD_Y, reward.isPresent() ? TEXT_GOLD : TEXT_DIM, false);

		String prereqText = quest.prerequisiteNames().isEmpty()
				? "Prerequisites: none"
				: "Prerequisites: " + String.join(", ", quest.prerequisiteNames());
		g.text(font, font.plainSubstrByWidth(prereqText, w), x, hy + HDR_PREREQ_Y, TEXT_DIM, false);

		if (quest.locked()) {
			g.text(font, font.plainSubstrByWidth("Locked until prerequisites complete", w), x, hy + HDR_LOCKED_Y, TEXT_RED, false);
		}
		int ruleY = hy + (quest.locked() ? HDR_LOCKED_Y : HDR_PREREQ_Y) + HDR_RULE_GAP;
		g.fill(x, ruleY, x + w, ruleY + HDR_RULE_H, PANEL_BORDER);

		// Tasks — clipped so a row near the bottom edge cannot bleed past the pane.
		g.enableScissor(rightPaneLeft, listTop, rightPaneLeft + rightPaneWidth - 12, listBottom - 14);
		if (quest.tasks().isEmpty()) {
			g.text(font, "No tasks yet.", x, y + 2, TEXT_DIM, false);
			y += 14;
		} else {
			for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
				if (y + TASK_ROW_H > listTop && y < listBottom) {
					y = renderTaskRow(g, quest, task, x, y, w, mouseX, mouseY);
				} else {
					y += taskRowPitch();
				}
			}
		}
		g.disableScissor();

		// [+ Add Task] button pinned to the bottom of the pane
		int btnW = font.width("+ Add Task") + 10;
		int btnX = x + w - btnW;
		int btnY = listBottom - 14;
		boolean btnHover = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + 14 && !modalOpen();
		g.fill(btnX, btnY, btnX + btnW, btnY + 14, btnHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY);
		g.text(font, "+ Add Task", btnX + 5, btnY + 3, TEXT_WHITE, false);

		rightContentHeight = (y + (int) rightScroll) - listTop;
		renderScrollbar(g, x + w + 1, listTop, listBottom - listTop, rightContentHeight, rightScroll);
	}

	/**
	 * Row geometry, shared by the render walk and the click walk. They used to
	 * repeat these literals independently and agreed only by coincidence; a change
	 * to one silently mis-aligned the other.
	 */
	private static final int QUEST_CARD_H = 17;
	private static final int QUEST_CARD_GAP = 2;
	private static final int TASK_ROW_H = 15;
	private static final int TASK_ROW_GAP = 1;

	private int questCardPitch() {
		return QUEST_CARD_H + QUEST_CARD_GAP;
	}

	private int taskRowPitch() {
		return TASK_ROW_H + TASK_ROW_GAP;
	}

	/**
	 * Right-pane header rows. Line pitch equals the font's 9px height plus 2px of
	 * leading; the rule sits below the last line with a gap so descenders clear it.
	 */
	private static final int HDR_TITLE_Y = 1;
	private static final int HDR_PROGRESS_Y = 12;
	private static final int HDR_REWARD_Y = 23;
	private static final int HDR_PREREQ_Y = 34;
	private static final int HDR_LOCKED_Y = 45;
	/** Glyph height of the 9px font, used to keep rules clear of descenders. */
	private static final int FONT_H = 9;
	/** Gap between the last header line's baseline and the divider rule. */
	private static final int HDR_RULE_GAP = FONT_H + 3;
	/** Rule thickness. */
	private static final int HDR_RULE_H = 1;
	/** Gap between the rule and the first task row. */
	private static final int HDR_RULE_TO_TASKS = 6;

	/** Height of the right-pane header block; shared by render and click hit-testing. */
	private int rightHeaderHeight(AdminSyncPayload.AdminQuestEntry quest) {
		int lastLineY = quest.locked() ? HDR_LOCKED_Y : HDR_PREREQ_Y;
		int ruleBottom = lastLineY + HDR_RULE_GAP + HDR_RULE_H;
		return ruleBottom + HDR_RULE_TO_TASKS;
	}

	private AdminSyncPayload.AdminQuestEntry selectedQuest() {
		if (selectedQuestId == null) {
			return null;
		}
		for (AdminSyncPayload.AdminQuestEntry quest : ClientAdminState.quests()) {
			if (quest.id().equals(selectedQuestId)) {
				return quest;
			}
		}
		return null;
	}

	private boolean modalOpen() {
		return newQuestDialogOpen || itemPickerOpen;
	}

	// --- MODAL: ITEM PICKER --------------------------------------------------

	private void renderItemPickerModal(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int left = pickerLeft();
		int top = pickerTop();
		int bottom = top + pickerHeight();

		// Backdrop
		g.fill(0, 0, width, height, 0x90000000);

		// Frame
		g.fill(left - 1, top - 1, left + pickerWidth() + 1, bottom + 1, 0xFF000000);
		g.fill(left, top, left + pickerWidth(), bottom, PANEL_BG);
		g.fill(left, top, left + pickerWidth(), top + 20, HEADER_BG);
		g.fill(left, top + 20, left + pickerWidth(), top + 21, CARD_SELECTED_BORDER);

		// Header
		boolean isEditing = editingTaskId != null;
		g.text(font, isEditing ? "Edit Task Item" : "Select Item", gridLeft(), top + 6, TEXT_GOLD, false);

		// Selected item + quest context
		String context = "Quest: " + (selectedQuest() != null ? selectedQuest().name() : "?");
		g.text(font, font.plainSubstrByWidth(context, gridWidth()), gridLeft(), top + 24, TEXT_MUTED, false);
		if (!selectedItem.isEmpty()) {
			String sel = "Selected: " + selectedItem.getHoverName().getString();
			g.text(font, font.plainSubstrByWidth(sel, gridWidth()), gridLeft(), pickerTabsY() - 10, TEXT_GREEN, false);
		}

		// [✕] Close
		int closeX = gridLeft() + gridWidth() - 8;
		int closeY = top + 6;
		boolean closeHover = mouseX >= closeX - 2 && mouseX <= closeX + 10 && mouseY >= closeY - 2 && mouseY <= closeY + 10;
		g.text(font, "\u2715", closeX, closeY, closeHover ? TEXT_RED : TEXT_MUTED, false);

		// Category Tabs
		List<ItemCatalog.Tab> tabs = ItemCatalog.tabs();
		int tabX = gridLeft();
		int tabY = pickerTabsY();
		int tabSize = slotSize();
		for (int i = 0; i < Math.min(tabs.size(), 14); i++) {
			ItemCatalog.Tab tab = tabs.get(i);
			boolean isSel = i == selectedTab;
			boolean hover = mouseX >= tabX && mouseX <= tabX + tabSize && mouseY >= tabY && mouseY <= tabY + tabSize;
			g.fill(tabX, tabY, tabX + tabSize, tabY + tabSize, isSel ? CARD_SELECTED : (hover ? CARD_HOVER : SLOT_BG));
			if (isSel) {
				g.fill(tabX, tabY + tabSize - 1, tabX + tabSize, tabY + tabSize, CARD_SELECTED_BORDER);
			}
			g.item(tab.icon(), tabX + 1, tabY + 1);
			if (hover) {
				g.setTooltipForNextFrame(font, tab.icon(), mouseX, mouseY);
			}
			tabX += tabSize;
		}

		// Item Grid
		int gx = gridLeft();
		int gridTop = pickerGridY();
		String query = itemSearchField != null ? itemSearchField.getValue() : "";
		List<ItemStack> items = ItemCatalog.filter(selectedTab, query);
		int cols = gridCols();
		int rows = gridRows();
		int maxScrollRows = Math.max(0, (items.size() + cols - 1) / cols - rows);
		pickerContentHeight = maxScrollRows;

		int startIndex = (int) pickerScroll * cols;
		ItemStack hoveredStack = null;

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int idx = startIndex + row * cols + col;
				int sx = gx + col * slotSize();
				int sy = gridTop + row * slotSize();
				boolean hover = mouseX >= sx && mouseX < sx + slotSize() && mouseY >= sy && mouseY < sy + slotSize();
				boolean sel = idx == selectedGridIndex;
				g.fill(sx, sy, sx + slotSize() - 1, sy + slotSize() - 1, sel ? SLOT_SELECTED : (hover ? SLOT_HOVER : SLOT_BG));
				if (sel) {
					g.fill(sx, sy, sx + slotSize() - 1, sy + 1, SLOT_SELECTED_BORDER);
				}
				if (idx < items.size()) {
					g.item(items.get(idx), sx + 1, sy + 1);
					if (hover) {
						hoveredStack = items.get(idx);
					}
				}
			}
		}

		// Grid Scrollbar — flush to the grid's right edge, inside the content area.
		if (maxScrollRows > 0) {
			int sbX = gx + gridWidth() + 2;
			int sbH = rows * slotSize();
			int thumbH = Math.max(10, sbH * rows / (rows + maxScrollRows));
			int thumbY = gridTop + (int) ((sbH - thumbH) * (pickerScroll / maxScrollRows));
			g.fill(sbX, gridTop, sbX + 2, gridTop + sbH, 0x40000000);
			g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, CARD_SELECTED_BORDER);
		}

		if (hoveredStack != null) {
			g.setTooltipForNextFrame(font, hoveredStack, mouseX, mouseY);
		}

		// Single footer row: Amount field, assignee pill, Add-Update (+ Cancel).
		int bottomY = pickerBottomY();
		int rowH = 14;
		g.text(font, AMOUNT_LABEL, gridLeft(), bottomY + 3, TEXT_WHITE, false);

		// Clearance, not just the glyph width: EditBox pads its text by ~4px inside its
		// outline, so the outline's left border must start well clear of the label or
		// it slices through the final glyph.
		int countX = amountFieldX();
		int countW = AMOUNT_FIELD_W;
		int assignBtnX = countX + countW + 6;

		// Add-Update sits flush on the grid's right edge; the pill fills the space left
		// of it, so the row shares the grid's left and right edges instead of stranding
		// a gap before the scrollbar.
		String actionLabel = isEditing ? "\u2713 Update Task" : "+ Add Task";
		int addBtnW = font.width(actionLabel) + 10;
		int addBtnX = gridRight() - addBtnW;
		int assignBtnW = addBtnX - 6 - assignBtnX;
		boolean assignHover = mouseX >= assignBtnX && mouseX <= assignBtnX + assignBtnW && mouseY >= bottomY && mouseY <= bottomY + rowH;
		g.fill(assignBtnX, bottomY, assignBtnX + assignBtnW, bottomY + rowH, assignHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
		String assignText = "\u25BE " + font.plainSubstrByWidth(selectedAssigneeName, assignBtnW - 12);
		int assignColor = selectedAssigneeId.equals(Task.UNASSIGNED) ? TEXT_RED : TEXT_GREEN;
		g.text(font, assignText, assignBtnX + 4, bottomY + 3, assignColor, false);

		boolean canAct = selectedQuestId != null && !selectedItem.isEmpty();
		boolean actHover = mouseX >= addBtnX && mouseX <= addBtnX + addBtnW && mouseY >= bottomY && mouseY <= bottomY + rowH;
		g.fill(addBtnX, bottomY, addBtnX + addBtnW, bottomY + rowH, canAct ? (actHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY) : 0xFF3E3E42);
		g.text(font, actionLabel, addBtnX + 5, bottomY + 3, canAct ? TEXT_WHITE : TEXT_DIM, false);

		if (isEditing) {
			int cancelBtnW = font.width("Cancel") + 8;
			int cancelBtnX = addBtnX - cancelBtnW - 4;
			boolean cancelHover = mouseX >= cancelBtnX && mouseX <= cancelBtnX + cancelBtnW && mouseY >= bottomY && mouseY <= bottomY + rowH;
			g.fill(cancelBtnX, bottomY, cancelBtnX + cancelBtnW, bottomY + rowH, cancelHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
			g.text(font, "Cancel", cancelBtnX + 4, bottomY + 3, TEXT_MUTED, false);
		}
	}

	private void openItemPicker() {
		itemPickerOpen = true;
		if (itemSearchField != null) {
			itemSearchField.visible = true;
		}
		if (countField != null) {
			countField.visible = true;
			if (editingTaskId == null) {
				countField.setValue("16");
			}
		}
		pickerScroll = 0;
	}

	private void closeItemPicker() {
		itemPickerOpen = false;
		if (itemSearchField != null) {
			itemSearchField.visible = false;
		}
		if (countField != null) {
			countField.visible = false;
		}
		editingTaskId = null;
		selectedItem = ItemStack.EMPTY;
		selectedGridIndex = -1;
	}

	private boolean handlePickerModalClick(net.minecraft.client.input.MouseButtonEvent event, double mx, double my) {
		int left = pickerLeft();
		int top = pickerTop();

		// [✕] Close
		int closeX = gridLeft() + gridWidth() - 8;
		int closeY = top + 6;
		if (mx >= closeX - 2 && mx <= closeX + 10 && my >= closeY - 2 && my <= closeY + 10) {
			closeItemPicker();
			return true;
		}

		// Clicking the backdrop closes the picker
		if (mx < left || mx > left + pickerWidth() || my < top || my > top + pickerHeight()) {
			closeItemPicker();
			return true;
		}

		// Category tabs
		int tabY = pickerTabsY();
		int tabX = gridLeft();
		int tabSize = slotSize();
		List<ItemCatalog.Tab> tabs = ItemCatalog.tabs();
		for (int i = 0; i < Math.min(tabs.size(), 14); i++) {
			if (mx >= tabX && mx <= tabX + tabSize && my >= tabY && my <= tabY + tabSize) {
				selectedTab = i;
				pickerScroll = 0;
				selectedGridIndex = -1;
				return true;
			}
			tabX += tabSize;
		}

		// Item grid slots
		int gx = gridLeft();
		int gridTop = pickerGridY();
		String query = itemSearchField != null ? itemSearchField.getValue() : "";
		List<ItemStack> items = ItemCatalog.filter(selectedTab, query);
		int cols = gridCols();
		int rows = gridRows();
		int startIndex = (int) pickerScroll * cols;

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int idx = startIndex + row * cols + col;
				int sx = gx + col * slotSize();
				int sy = gridTop + row * slotSize();
				if (mx >= sx && mx < sx + slotSize() && my >= sy && my < sy + slotSize()) {
					if (idx < items.size()) {
						selectedGridIndex = idx;
						selectedItem = items.get(idx).copy();
					}
					return true;
				}
			}
		}

		// Footer row: Amount / assignee pill / Add-Update / Cancel. Geometry is
		// recomputed here rather than shared, so it must match the render path exactly
		// or the hit-test drifts from the pixels.
		int bottomY = pickerBottomY();
		int rowH = 14;
		int countX = amountFieldX();
		int countW = AMOUNT_FIELD_W;
		int assignBtnX = countX + countW + 6;

		boolean isEditing = editingTaskId != null;
		String actionLabel = isEditing ? "\u2713 Update Task" : "+ Add Task";
		int addBtnW = font.width(actionLabel) + 10;
		int addBtnX = gridRight() - addBtnW;
		int assignBtnW = addBtnX - 6 - assignBtnX;
		if (mx >= assignBtnX && mx <= assignBtnX + assignBtnW && my >= bottomY && my <= bottomY + rowH) {
			assigneeDropdownOpen = true;
			assigneeDropdownX = assignBtnX;
			assigneeDropdownY = bottomY;
			return true;
		}

		if (mx >= addBtnX && mx <= addBtnX + addBtnW && my >= bottomY && my <= bottomY + rowH) {
			executeTaskAction();
			return true;
		}

		if (isEditing) {
			int cancelBtnW = font.width("Cancel") + 8;
			int cancelBtnX = addBtnX - cancelBtnW - 4;
			if (mx >= cancelBtnX && mx <= cancelBtnX + cancelBtnW && my >= bottomY && my <= bottomY + rowH) {
				closeItemPicker();
				return true;
			}
		}

		// Pass click through so itemSearchField / countField receive cursor clicks
		return super.mouseClicked(event, false);
	}
	// --- MODAL DIALOG: NEW GOAL ----------------------------------------------

	private void renderNewQuestDialog(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		// Backdrop
		g.fill(0, 0, width, height, 0x90000000);

		int modalW = 240;
		int modalH = 88;
		int modalX = (width - modalW) / 2;
		int modalY = (height - modalH) / 2;
		// Frame
		g.fill(modalX - 1, modalY - 1, modalX + modalW + 1, modalY + modalH + 1, 0xFF000000);
		g.fill(modalX, modalY, modalX + modalW, modalY + modalH, PANEL_BG);
		g.fill(modalX, modalY, modalX + modalW, modalY + 20, HEADER_BG);
		g.fill(modalX, modalY + 20, modalX + modalW, modalY + 21, CARD_SELECTED_BORDER);

		// Header
		g.text(font, "Create New Quest", modalX + 8, modalY + 6, TEXT_GOLD, false);

		// [✕] Close
		int closeX = modalX + modalW - 14;
		int closeY = modalY + 6;
		boolean closeHover = mouseX >= closeX - 2 && mouseX <= closeX + 10 && mouseY >= closeY - 2 && mouseY <= closeY + 10;
		g.text(font, "\u2715", closeX, closeY, closeHover ? TEXT_RED : TEXT_MUTED, false);

		// Buttons Row
		int btnY = modalY + 58;

		// [Create Quest]
		int createBtnW = font.width("Create Quest") + 8;
		int createBtnX = modalX + modalW - createBtnW - 12;
		boolean canCreate = newQuestNameField != null && !newQuestNameField.getValue().trim().isEmpty();
		boolean createHover = mouseX >= createBtnX && mouseX <= createBtnX + createBtnW && mouseY >= btnY && mouseY <= btnY + 16;
		g.fill(createBtnX, btnY, createBtnX + createBtnW, btnY + 16, canCreate ? (createHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY) : 0xFF3E3E42);
		g.text(font, "Create Quest", createBtnX + 4, btnY + 4, canCreate ? TEXT_WHITE : TEXT_DIM, false);

		// [Cancel]
		int cancelBtnW = font.width("Cancel") + 8;
		int cancelBtnX = createBtnX - cancelBtnW - 6;
		boolean cancelHover = mouseX >= cancelBtnX && mouseX <= cancelBtnX + cancelBtnW && mouseY >= btnY && mouseY <= btnY + 16;
		g.fill(cancelBtnX, btnY, cancelBtnX + cancelBtnW, btnY + 16, cancelHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
		g.text(font, "Cancel", cancelBtnX + 4, btnY + 4, TEXT_MUTED, false);
	}

	// --- DROPDOWN OVERLAY ----------------------------------------------------

	private void renderAssigneeDropdown(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (!assigneeDropdownOpen) {
			return;
		}

		List<AdminSyncPayload.PlayerEntry> players = ClientAdminState.players();
		int entryCount = players.size() + 1;
		int rowH = 13;
		int boxW = 88;
		int boxH = entryCount * rowH + 4;

		int bx;
		int by;
		if (itemPickerOpen) {
			int left = pickerLeft();
			int top = pickerTop();
			bx = Mth.clamp(assigneeDropdownX, left + 4, left + pickerWidth() - boxW - 4);
			by = Mth.clamp(assigneeDropdownY - boxH - 2, top + 4, top + pickerHeight() - boxH - 4);
		} else {
			bx = Mth.clamp(assigneeDropdownX, panelLeft + 4, panelLeft + panelWidth - boxW - 4);
			by = Mth.clamp(assigneeDropdownY - boxH - 2, panelTop + 4, panelTop + panelHeight - boxH - 4);
		}

		g.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, 0xFF000000);
		g.fill(bx, by, bx + boxW, by + boxH, POPUP_BG);

		for (int i = 0; i < entryCount; i++) {
			int ry = by + 2 + i * rowH;
			boolean hover = mouseX >= bx && mouseX <= bx + boxW && mouseY >= ry && mouseY < ry + rowH;
			if (hover) {
				g.fill(bx + 1, ry, bx + boxW - 1, ry + rowH, CARD_HOVER);
			}

			if (i == 0) {
				g.text(font, "Unassigned", bx + 4, ry + 2, TEXT_RED, false);
			} else {
				AdminSyncPayload.PlayerEntry p = players.get(i - 1);
				g.text(font, font.plainSubstrByWidth(p.name(), boxW - 8), bx + 4, ry + 2, TEXT_WHITE, false);
			}
		}
	}

	private void renderScrollbar(GuiGraphicsExtractor g, int sbX, int top, int height, int contentH, double currentScroll) {
		if (contentH <= height || height <= 0) {
			return;
		}
		int maxScroll = contentH - height;
		int thumbH = Math.max(10, height * height / Math.max(1, contentH));
		int thumbY = top + (int) ((height - thumbH) * (currentScroll / maxScroll));

		g.fill(sbX, top, sbX + 2, top + height, 0x40000000);
		g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, CARD_SELECTED_BORDER);
	}

	// --- CLICKS & INTERACTION ------------------------------------------------

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();

		// Handle Modal New Quest Dialog Clicks First
		if (newQuestDialogOpen) {
			return handleModalDialogClick(event, mx, my);
		}

		// Handle Item Picker Modal Clicks
		if (itemPickerOpen) {
			if (assigneeDropdownOpen) {
				if (handleDropdownClick(mx, my)) {
					return true;
				}
				assigneeDropdownOpen = false;
				return true;
			}
			return handlePickerModalClick(event, mx, my);
		}

		if (assigneeDropdownOpen) {
			if (handleDropdownClick(mx, my)) {
				return true;
			}
			assigneeDropdownOpen = false;
			return true;
		}

		// Close [✕] Main Studio
		int closeX = panelLeft + panelWidth - 14;
		int closeY = panelTop + 6;
		if (mx >= closeX - 2 && mx <= closeX + 10 && my >= closeY - 2 && my <= closeY + 10) {
			onClose();
			return true;
		}

		// [+ New Quest]
		int newBtnW = font.width("+ New Quest") + 8;
		int newBtnH = 14;
		int newBtnX = panelLeft + panelWidth - newBtnW - 22;
		int newBtnY = panelTop + 4;
		if (mx >= newBtnX && mx <= newBtnX + newBtnW && my >= newBtnY && my <= newBtnY + newBtnH) {
			openNewQuestDialog();
			return true;
		}

		// Left Pane Click
		if (mx >= leftPaneLeft && mx <= leftPaneLeft + leftPaneWidth && my >= leftPaneTop && my <= leftPaneTop + leftPaneHeight) {
			if (handleLeftPaneClick(mx, my)) {
				return true;
			}
		}

		// Right Pane Click
		if (mx >= rightPaneLeft && mx <= rightPaneLeft + rightPaneWidth && my >= rightPaneTop && my <= rightPaneTop + rightPaneHeight) {
			if (handleRightPaneClick(mx, my)) {
				return true;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void openNewQuestDialog() {
		newQuestDialogOpen = true;
		if (newQuestNameField != null) {
			newQuestNameField.setValue("");
			newQuestNameField.visible = true;
			newQuestNameField.setFocused(true);
			setFocused(newQuestNameField);
		}
	}

	private void closeNewQuestDialog() {
		newQuestDialogOpen = false;
		if (newQuestNameField != null) {
			newQuestNameField.visible = false;
		}
	}

	private boolean handleModalDialogClick(net.minecraft.client.input.MouseButtonEvent event, double mx, double my) {
		int modalW = 240;
		int modalH = 88;
		int modalX = (width - modalW) / 2;
		int modalY = (height - modalH) / 2;
		// Close [✕]
		int closeX = modalX + modalW - 14;
		int closeY = modalY + 6;
		if (mx >= closeX - 2 && mx <= closeX + 10 && my >= closeY - 2 && my <= closeY + 10) {
			closeNewQuestDialog();
			return true;
		}

		int btnY = modalY + 58;

		// [Create Quest]
		int createBtnW = font.width("Create Quest") + 8;
		int createBtnX = modalX + modalW - createBtnW - 12;
		if (mx >= createBtnX && mx <= createBtnX + createBtnW && my >= btnY && my <= btnY + 16) {
			commitCreateQuest();
			return true;
		}

		// [Cancel]
		int cancelBtnW = font.width("Cancel") + 8;
		int cancelBtnX = createBtnX - cancelBtnW - 6;
		if (mx >= cancelBtnX && mx <= cancelBtnX + cancelBtnW && my >= btnY && my <= btnY + 16) {
			closeNewQuestDialog();
			return true;
		}

		// Pass click through so newQuestNameField receives cursor click & focus
		return super.mouseClicked(event, false);
	}

	private void commitCreateQuest() {
		if (newQuestNameField == null) return;
		String name = newQuestNameField.getValue().trim();
		if (name.isEmpty()) return;

		if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
			ClientPlayNetworking.send(AdminActionPayload.createQuest(name, List.of()));
		}

		closeNewQuestDialog();
	}

	// --- DEBUG HOOKS (development only; used by EditorDebugDriver) ----------

	/** Selects the first quest, so a shot exercises the populated right pane. */
	public void debugSelectFirstQuest() {
		List<AdminSyncPayload.AdminQuestEntry> quests = ClientAdminState.quests();
		if (!quests.isEmpty()) {
			selectedQuestId = quests.get(0).id();
		}
	}

	/** Opens the item picker as if [+ Add Task] had been pressed. */
	public void debugOpenPicker() {
		newQuestDialogOpen = false;
		openItemPicker();
	}

	/**
	 * Puts text in the picker's search field so a capture can photograph the value
	 * path. The placeholder is only half the story: the value is drawn by EditBox
	 * itself, from a different origin, so it needs its own shot.
	 */
	public void debugTypeSearch(String text) {
		if (itemSearchField != null) {
			itemSearchField.setValue(text);
		}
	}

	/** Value drawn in the search field, so a capture can assert it is where it looks. */
	public String debugSearchValue() {
		return itemSearchField == null ? "<null>" : itemSearchField.getValue();
	}

	/** Opens the new-quest dialog. */
	public void debugOpenNewQuestDialog() {
		itemPickerOpen = false;
		if (itemSearchField != null) itemSearchField.visible = false;
		if (countField != null) countField.visible = false;
		openNewQuestDialog();
	}

	/** Cycles focus through the screen's child widgets, avoiding coordinates. */
	public void debugAdvanceFocus() {
		assigneeDropdownOpen = !assigneeDropdownOpen;
	}

	/** One-line state summary, so a capture run can be read from the log alone. */
	public String debugState() {
		return "selected=" + (selectedQuestId != null) + " picker=" + itemPickerOpen
				+ " dialog=" + newQuestDialogOpen + " editing=" + (editingTaskId != null);
	}

	/** One-line geometry summary, for when a shot needs explaining. */
	public String debugGeometry() {
		return String.format("screen=%dx%d panel=%d,%d %dx%d left=%d,%d %dx%d right=%d,%d %dx%d picker=%d,%d %dx%d rows=%d cols=%d grid=%d..%d search=%d,%d %dx%d count=%d,%d %dx%d",
				width, height, panelLeft, panelTop, panelWidth, panelHeight,
				leftPaneLeft, leftPaneTop, leftPaneWidth, leftPaneHeight,
				rightPaneLeft, rightPaneTop, rightPaneWidth, rightPaneHeight,
				pickerLeft(), pickerTop(), pickerWidth(), pickerHeight(), gridRows(), gridCols(),
				gridLeft(), gridLeft() + gridWidth(),
				itemSearchField == null ? -1 : itemSearchField.getX(),
				searchY0(),
				itemSearchField == null ? -1 : itemSearchField.getWidth(),
				itemSearchField == null ? -1 : itemSearchField.getHeight(),
				countField == null ? -1 : countField.getX(),
				countField == null ? -1 : countField.getY(),
				countField == null ? -1 : countField.getWidth(),
				countField == null ? -1 : countField.getHeight());
	}

	private int searchY0() {
		return itemSearchField == null ? -1 : itemSearchField.getY();
	}

	private boolean handleDropdownClick(double mx, double my) {
		List<AdminSyncPayload.PlayerEntry> players = ClientAdminState.players();
		int entryCount = players.size() + 1;
		int rowH = 13;
		int boxW = 88;
		int boxH = entryCount * rowH + 4;

		int bx;
		int by;
		if (itemPickerOpen) {
			int left = pickerLeft();
			int top = pickerTop();
			bx = Mth.clamp(assigneeDropdownX, left + 4, left + pickerWidth() - boxW - 4);
			by = Mth.clamp(assigneeDropdownY - boxH - 2, top + 4, top + pickerHeight() - boxH - 4);
		} else {
			bx = Mth.clamp(assigneeDropdownX, panelLeft + 4, panelLeft + panelWidth - boxW - 4);
			by = Mth.clamp(assigneeDropdownY - boxH - 2, panelTop + 4, panelTop + panelHeight - boxH - 4);
		}

		if (mx >= bx && mx <= bx + boxW && my >= by && my <= by + boxH) {
			int row = (int) ((my - by - 2) / rowH);
			if (row >= 0 && row < entryCount) {
				if (row == 0) {
					selectedAssigneeId = Task.UNASSIGNED;
					selectedAssigneeName = "Unassigned";
				} else {
					AdminSyncPayload.PlayerEntry p = players.get(row - 1);
					selectedAssigneeId = p.id();
					selectedAssigneeName = p.name();
				}
			}
			assigneeDropdownOpen = false;
			return true;
		}
		return false;
	}

	private boolean handleLeftPaneClick(double mx, double my) {
		int listTop = leftPaneTop;
		int y = listTop - (int) leftScroll;

		for (AdminSyncPayload.AdminQuestEntry quest : ClientAdminState.quests()) {
			int cardH = QUEST_CARD_H;
			int cardW = leftPaneWidth - 6;
			int cardX = leftPaneLeft;

			if (my >= y && my < y + cardH) {
				// Delete [✕] Quest
				int delX = cardX + cardW - 10;
				if (mx >= delX - 2 && mx <= delX + 8) {
					if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
						ClientPlayNetworking.send(AdminActionPayload.deleteQuest(quest.id()));
					}
					if (quest.id().equals(selectedQuestId)) {
						selectedQuestId = null;
						editingTaskId = null;
					}
					return true;
				}

				// Select quest (and exit task edit mode)
				selectedQuestId = quest.id();
				editingTaskId = null;
				selectedItem = ItemStack.EMPTY;
				selectedGridIndex = -1;
				rightScroll = 0;
				return true;
			}
			y += questCardPitch();
		}

		return false;
	}

	private void startEditingTask(AdminSyncPayload.AdminQuestEntry quest, AdminSyncPayload.AdminTaskEntry task) {
		selectedQuestId = quest.id();
		editingTaskId = task.id();

		// Load item into picker
		Identifier id = Identifier.tryParse(task.itemId());
		Item item = id != null ? BuiltInRegistries.ITEM.getValue(id) : Items.AIR;
		if (item != null && item != Items.AIR) {
			selectedItem = new ItemStack(item);
		}

		if (countField != null) {
			countField.setValue(String.valueOf(task.need()));
		}

		selectedAssigneeId = task.assignee();
		selectedAssigneeName = task.assigneeName();

		openItemPicker();
	}

	private boolean handleRightPaneClick(double mx, double my) {
		AdminSyncPayload.AdminQuestEntry quest = selectedQuest();
		if (quest == null) {
			return false;
		}

		int listTop = rightPaneTop;
		int listBottom = rightPaneTop + rightPaneHeight;
		int w = rightPaneWidth - 8;
		int x = rightPaneLeft + 4;
		int y = listTop - (int) rightScroll;

		// Skip the header block (title, progress, reward, prereqs, locked, rule)
		y += rightHeaderHeight(quest);

		// Task rows
		for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
			int rowH = TASK_ROW_H;
			if (my >= y && my < y + rowH) {
				// Delete [✕]
				int delX = x + w - 8;
				if (mx >= delX - 2 && mx <= delX + 8) {
					if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
						ClientPlayNetworking.send(AdminActionPayload.deleteTask(quest.id(), task.id()));
					}
					if (task.id().equals(editingTaskId)) {
						editingTaskId = null;
					}
					return true;
				}

				// Click to Edit Task
				startEditingTask(quest, task);
				return true;
			}
			y += taskRowPitch();
		}

		// [+ Add Task]
		int btnW = font.width("+ Add Task") + 10;
		int btnX = x + w - btnW;
		int btnY = listBottom - 14;
		if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + 14) {
			openItemPicker();
			return true;
		}

		return false;
	}

	private void executeTaskAction() {
		if (selectedQuestId == null || selectedItem.isEmpty()) return;

		Identifier id = BuiltInRegistries.ITEM.getKey(selectedItem.getItem());
		if (id == null) return;

		int count = 1;
		try {
			count = Integer.parseInt(countField.getValue().trim());
		} catch (NumberFormatException ignored) {}
		count = Math.max(1, Math.min(1000000, count));

		if (editingTaskId != null) {
			// Update Existing Task
			if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
				ClientPlayNetworking.send(AdminActionPayload.updateTask(selectedQuestId, editingTaskId,
						new AdminActionPayload.NewTaskData(id.toString(), count, selectedAssigneeId)));
			}
		} else {
			// Add New Task
			if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
				ClientPlayNetworking.send(AdminActionPayload.addTask(selectedQuestId,
						new AdminActionPayload.NewTaskData(id.toString(), count, selectedAssigneeId)));
			}
		}

		closeItemPicker();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int keyCode = event.key();
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			if (newQuestDialogOpen) {
				closeNewQuestDialog();
				return true;
			}
			if (assigneeDropdownOpen) {
				assigneeDropdownOpen = false;
				return true;
			}
			if (itemPickerOpen) {
				closeItemPicker();
				return true;
			}
			if (editingTaskId != null) {
				editingTaskId = null;
				return true;
			}
		}

		if (newQuestDialogOpen && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
			commitCreateQuest();
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (newQuestDialogOpen) {
			return true;
		}

		if (itemPickerOpen) {
			pickerScroll = Mth.clamp(pickerScroll - scrollY, 0, Math.max(0, pickerContentHeight));
			return true;
		}

		if (mouseX >= leftPaneLeft && mouseX <= leftPaneLeft + leftPaneWidth) {
			int visible = leftPaneHeight;
			int maxScroll = Math.max(0, leftContentHeight - visible);
			leftScroll = Mth.clamp(leftScroll - scrollY * 16, 0, maxScroll);
			return true;
		}

		if (mouseX >= rightPaneLeft && mouseX <= rightPaneLeft + rightPaneWidth) {
			int visible = rightPaneHeight;
			int maxScroll = Math.max(0, rightContentHeight - visible);
			rightScroll = Mth.clamp(rightScroll - scrollY * 16, 0, maxScroll);
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		open = false;
		super.onClose();
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
