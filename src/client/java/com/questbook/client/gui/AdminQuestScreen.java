package com.questbook.client.gui;

import com.questbook.QuestBook;
import com.questbook.client.ClientAdminState;
import com.questbook.data.Task;
import com.questbook.network.AdminActionPayload;
import com.questbook.network.AdminSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

	/**
	 * Copied verbatim by the dialog's [AI Prompt] button. Feed any existing task list
	 * to an AI with this and it emits the JSON the import path accepts.
	 */
	private static final String IMPORT_PROMPT = """
			Convert the task list at the end of this message into EXACTLY this JSON shape. Output JSON only, no prose, no markdown fences.

			[{"name":"Quest name","tasks":[{"item":"oak_log","count":64,"player":"Alex"}]}]

			Rules:
			- One object per quest, all quests in one array.
			- "item" is a minecraft item or block id, lowercase snake_case, namespace optional ("oak_log" is fine, "minecraft:oak_log" also works).
			- "count" is the total required, integer.
			- "player" is the assignee's name; omit the key entirely for unassigned.
			- Omit "tasks" for an empty quest.

			Task list:
			""";
	/** Ticks remaining on the dialog's "Copied!" feedback, 0 = hidden. */
	private int aiCopiedTicks = 0;
	private boolean newQuestDialogOpen = false;
	/** Dialog committed as a rename of the selected quest instead of a create. */
	private boolean renameMode = false;
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

	/** Header band, then a gap so the tab strip does not butt against its rule. */
	private static final int PICKER_HEADER_H = 20;
	private static final int PICKER_HEADER_GAP = 5;

	private int pickerTabsY() {
		return pickerTop() + PICKER_HEADER_H + PICKER_HEADER_GAP;
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
		newQuestNameField.setHintText("Quest name or JSON...");
		newQuestNameField.setMaxLength(4096);
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

		private int pinnedX;
		private int pinnedY;
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

		/**
		 * Moves the field. {@code pinnedX/pinnedY} are final so that the render override
		 * can restore them; a field that has to draw in more than one place (the count
		 * box lives in both the picker footer and the inline edit row) needs a way to
		 * change where "pinned" means.
		 */
		void reposition(int x, int y) {
			pinnedX = x;
			pinnedY = y;
			setX(x);
			setY(y);
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

		// Update widget visibility. The count field serves the picker's Amount box and the
		// inline edit row, whose rows are at different y positions, so it must follow
		// whichever is active or it draws inside the wrong container.
		if (itemSearchField != null) itemSearchField.visible = itemPickerOpen;
		if (countField != null) {
			countField.visible = itemPickerOpen || editingTaskId != null;
			if (itemPickerOpen) {
				countField.reposition(amountFieldX(), pickerBottomY());
			} else if (editingTaskId != null) {
				countField.reposition(editModalCountX(), editModalCountY());
			}
		}
		if (newQuestNameField != null) newQuestNameField.visible = newQuestDialogOpen;

		// Modal frames (rendered BEFORE widgets so their EditBoxes are drawn on top)
		if (newQuestDialogOpen) {
			renderNewQuestDialog(g, mouseX, mouseY);
		}
		if (editingTaskId != null) {
			renderTaskEditDialog(g, mouseX, mouseY);
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

	// [+ Add Task] Button. Lives in the header: the old bottom-of-pane button
	// overlapped the last task row, so its clicks opened the edit modal instead.
	boolean canAdd = selectedQuest() != null;
	int newBtnW = font.width("+ Add Task") + 8;
	int newBtnH = 14;
	int newBtnX = panelLeft + panelWidth - newBtnW - 22;
	int newBtnY = panelTop + 4;
	boolean newHover = canAdd && !modalOpen() && mouseX >= newBtnX && mouseX <= newBtnX + newBtnW && mouseY >= newBtnY && mouseY <= newBtnY + newBtnH;

	g.fill(newBtnX, newBtnY, newBtnX + newBtnW, newBtnY + newBtnH, !canAdd ? 0xFF3E3E42 : (newHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY));
	g.text(font, "+ Add Task", newBtnX + 4, newBtnY + 3, canAdd ? TEXT_WHITE : TEXT_DIM, false);

		// [AI Prompt] — copies the import-converter prompt. Feedback lives on the timer.
		int aiW = font.width(aiCopiedTicks > 0 ? "Copied!" : "Prompt") + 8;
		int aiX = newBtnX - aiW - 6;
		int aiY = panelTop + 4;
		boolean aiHover = !modalOpen() && mouseX >= aiX && mouseX <= aiX + aiW && mouseY >= aiY && mouseY <= aiY + newBtnH;
		g.fill(aiX, aiY, aiX + aiW, aiY + newBtnH, aiHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
		g.text(font, aiCopiedTicks > 0 ? "Copied!" : "Prompt", aiX + 4, aiY + 3, aiCopiedTicks > 0 ? TEXT_GREEN : TEXT_WHITE, false);

		// [✕] Close Button
		int closeX = panelLeft + panelWidth - 14;
		int closeY = panelTop + 6;
		boolean closeHover = mouseX >= closeX - 2 && mouseX <= closeX + 10 && mouseY >= closeY - 2 && mouseY <= closeY + 10;
		g.text(font, "\u2715", closeX, closeY, closeHover ? TEXT_RED : TEXT_MUTED, false);
	}

	// --- LEFT PANE (MASTER LIST) ---------------------------------------------

	/** Height of the [+ New Quest] button pinned to the quest pane's bottom. */
	private static final int LEFT_ADD_H = 14;

	/** Bottom edge of the quest list: the add button owns the last rows. */
	private int leftListBottom() {
		return leftPaneTop + leftPaneHeight - LEFT_ADD_H - 2;
	}

	private int leftAddButtonY() {
		return leftPaneTop + leftPaneHeight - LEFT_ADD_H;
	}

	private int leftAddButtonW() {
		return leftPaneWidth - 6;
	}

	private void renderLeftPane(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int listTop = leftPaneTop;
		int listBottom = leftListBottom();
		int y = listTop - (int) leftScroll;

		List<AdminSyncPayload.AdminQuestEntry> quests = ClientAdminState.quests();
		if (quests.isEmpty()) {
			int w = leftPaneWidth - 12;
			g.text(font, font.plainSubstrByWidth("No quests yet.", w), leftPaneLeft + 4, listTop + 8, TEXT_DIM, false);
			g.text(font, font.plainSubstrByWidth("Use '+ New Quest' below.", w), leftPaneLeft + 4, listTop + 20, TEXT_DIM, false);
			leftContentHeight = 0;
		} else {
			// Clip above the add button so cards cannot bleed under it.
			g.enableScissor(leftPaneLeft, listTop, leftPaneLeft + leftPaneWidth, listBottom);
			for (AdminSyncPayload.AdminQuestEntry quest : quests) {
				y = renderQuestCard(g, quest, y, listTop, listBottom, mouseX, mouseY);
			}
			g.disableScissor();

			leftContentHeight = (y + (int) leftScroll) - listTop;
			renderScrollbar(g, leftPaneLeft + leftPaneWidth - 3, listTop, listBottom - listTop, leftContentHeight, leftScroll);
		}

		// [+ New Quest] pinned to the bottom of the quest section, full card width.
		int addBtnY = leftAddButtonY();
		int addBtnW = leftAddButtonW();
		boolean addHover = !modalOpen() && mouseX >= leftPaneLeft && mouseX <= leftPaneLeft + addBtnW && mouseY >= addBtnY && mouseY <= addBtnY + LEFT_ADD_H;
		g.fill(leftPaneLeft, addBtnY, leftPaneLeft + addBtnW, addBtnY + LEFT_ADD_H, addHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY);
		g.text(font, "+ New Quest", leftPaneLeft + 5, addBtnY + 3, TEXT_WHITE, false);
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

			// Pencil — rename, left of the progress tally so neither overlaps.
			int penX = progX - 12;
			boolean penHover = mouseX >= penX - 2 && mouseX <= penX + 8 && mouseY >= y + 2 && mouseY <= y + 14 && !modalOpen();
			g.text(font, "\u270E", penX, y + 4, penHover ? TEXT_GOLD : TEXT_DIM, false);

			// Quest Name — clamped to stop before the pencil.
			int nameMaxW = Math.max(8, penX - 4 - (cardX + 6));
			String name = font.plainSubstrByWidth(quest.name(), nameMaxW);
			g.text(font, name, cardX + 6, y + 4, isSelected ? TEXT_GOLD : TEXT_WHITE, false);

			// Delete [✕] button
			boolean delHover = mouseX >= delX - 2 && mouseX <= delX + 8 && mouseY >= y + 2 && mouseY <= y + 14 && !modalOpen();
			g.text(font, "\u2715", delX, y + 4, delHover ? TEXT_RED : TEXT_DIM, false);

			// Tooltip on quest hover
			if (hover && mouseX < progX - 16) {
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

		// Assignee Chip — same UUID-stub handling as the picker pill. Right cluster
		// first (chip, amount), so the name clamps against them and stays visible.
		String assignee = resolveTaskAssigneeLabel(task);
		int assignW = Math.min(font.width(assignee) + 4, 40);
		int assignX = x + w - assignW - 12;
		g.fill(assignX, y + 2, assignX + assignW, y + rowH - 2, 0x40000000);
		int nameColor = task.assignee().equals(Task.UNASSIGNED) ? TEXT_RED : TEXT_CYAN;
		g.text(font, font.plainSubstrByWidth(assignee, assignW - 2), assignX + 2, y + 3, nameColor, false);

		// Amount, right-aligned just left of the chip.
		String amountStr = "x" + task.need();
		int amountX = assignX - 6 - font.width(amountStr);
		g.text(font, amountStr, amountX, y + 3, task.complete() ? TEXT_MUTED : TEXT_WHITE, false);

		// Name, clamped to the space between the status icon and the amount.
		int nameMax = Math.max(8, amountX - 6 - (x + 12));
		String label = font.plainSubstrByWidth(task.label(), nameMax);
		g.text(font, label, x + 12, y + 3, isTaskEditing ? TEXT_GOLD : (task.complete() ? TEXT_MUTED : TEXT_WHITE), false);

		// Delete [✕] — TEXT_MUTED, not TEXT_DIM. Measured against this row's own
		// background, DIM lands at 2.49:1, about half the readable minimum, so the
		// glyph was drawing but could not be seen. The quest card's delete can use DIM
		// because a card sits on a lighter surface; this row is tinted darker.
		int delX = x + w - 8;
		boolean delHover = mouseX >= delX - 2 && mouseX <= delX + 8 && mouseY >= y + 1 && mouseY <= y + rowH - 1 && !modalOpen();
		g.text(font, "\u2715", delX, y + 3, delHover ? TEXT_RED : TEXT_MUTED, false);

		// Tooltip on task hover
		if (hover && mouseX < delX - 4) {
			String action = isTaskEditing ? "Currently editing" : "Click to edit";
			Component tip = Component.literal(task.label() + " x" + task.need() + " (" + task.have() + "/" + task.need() + ") · " + action);
			g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
		}

		return y + rowH + 1;
	}

	/** Item behind a task's id, or null if the id no longer resolves. */
	private static Item itemOf(AdminSyncPayload.AdminTaskEntry task) {
		Identifier id = Identifier.tryParse(task.itemId());
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getValue(id);
		return item == Items.AIR ? null : item;
	}

	// --- MODAL: EDIT TASK ----------------------------------------------------

	/** Modal geometry, shared by render, click and the count field's placement. */
	private static final int EDIT_MODAL_W = 240;
	private static final int EDIT_MODAL_H = 96;

	private int editModalX() {
		return (width - EDIT_MODAL_W) / 2;
	}

	private int editModalY() {
		return (height - EDIT_MODAL_H) / 2;
	}

	/**
	 * Edits a task's amount and assignee. A modal, not an inline row: the pane is too
	 * narrow for a field and two buttons, and this screen already uses a modal for the
	 * other focused edit. There is deliberately no item picker — changing what a quest
	 * asks for is a different task, not an edit of this one, so the item is shown as a
	 * fixed icon.
	 */
	private void renderTaskEditDialog(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int modalX = editModalX();
		int modalY = editModalY();

		g.fill(0, 0, width, height, 0x90000000);
		g.fill(modalX - 1, modalY - 1, modalX + EDIT_MODAL_W + 1, modalY + EDIT_MODAL_H + 1, 0xFF000000);
		g.fill(modalX, modalY, modalX + EDIT_MODAL_W, modalY + EDIT_MODAL_H, PANEL_BG);
		g.fill(modalX, modalY, modalX + EDIT_MODAL_W, modalY + 20, HEADER_BG);
		g.fill(modalX, modalY + 20, modalX + EDIT_MODAL_W, modalY + 21, CARD_SELECTED_BORDER);

		// Modal header: just the quest name — the icon/amount/assignee already say
		// the rest.
		AdminSyncPayload.AdminQuestEntry quest = selectedQuest();
		AdminSyncPayload.AdminTaskEntry task = editingTask(quest);

		g.text(font, font.plainSubstrByWidth(quest == null ? "Edit Task" : quest.name(),
				EDIT_MODAL_W - 40), modalX + 8, modalY + 6, TEXT_GOLD, false);

		// [✕] Close
		int closeX = modalX + EDIT_MODAL_W - 14;
		int closeY = modalY + 6;
		boolean closeHover = mouseX >= closeX - 2 && mouseX <= closeX + 10 && mouseY >= closeY - 2 && mouseY <= closeY + 10;
		g.text(font, "\u2715", closeX, closeY, closeHover ? TEXT_RED : TEXT_MUTED, false);

		if (task == null) {
			return;
		}

		// The item, fixed. Icon + name, so it is obvious which task is being edited
		// without offering a way to change it.
		Item item = itemOf(task);
		if (item != null) {
			g.item(new ItemStack(item), modalX + 10, modalY + 26);
		}
		g.text(font, font.plainSubstrByWidth(task.label(), EDIT_MODAL_W - 46),
				modalX + 30, modalY + 30, TEXT_WHITE, false);

		// Amount row. The field itself is the count EditBox, positioned by initControls.
		g.text(font, AMOUNT_LABEL, modalX + 10, modalY + 48, TEXT_MUTED, false);

		// Assignee pill
		int pillY = modalY + 45;
		int pillX = modalX + 10 + font.width(AMOUNT_LABEL) + LABEL_GAP + AMOUNT_FIELD_W + 6;
		int pillW = modalX + EDIT_MODAL_W - 10 - pillX;
		boolean pillHover = mouseX >= pillX && mouseX <= pillX + pillW && mouseY >= pillY && mouseY <= pillY + 14;
		g.fill(pillX, pillY, pillX + pillW, pillY + 14, pillHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
		String assignLabel = resolveAssigneeLabel();
		int assignColor = selectedAssigneeId.equals(Task.UNASSIGNED) ? TEXT_RED : TEXT_GREEN;
		g.text(font, font.plainSubstrByWidth("\u25BE " + assignLabel, pillW - 8), pillX + 4, pillY + 3, assignColor, false);

		// Buttons
		int btnY = modalY + EDIT_MODAL_H - 22;
		int saveW = font.width("Save") + 10;
		int saveX = modalX + EDIT_MODAL_W - saveW - 10;
		boolean saveHover = mouseX >= saveX && mouseX <= saveX + saveW && mouseY >= btnY && mouseY <= btnY + 16;
		g.fill(saveX, btnY, saveX + saveW, btnY + 16, saveHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY);
		g.text(font, "Save", saveX + 5, btnY + 4, TEXT_WHITE, false);

		int cancelW = font.width("Cancel") + 8;
		int cancelX = saveX - cancelW - 6;
		boolean cancelHover = mouseX >= cancelX && mouseX <= cancelX + cancelW && mouseY >= btnY && mouseY <= btnY + 16;
		g.fill(cancelX, btnY, cancelX + cancelW, btnY + 16, cancelHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
		g.text(font, "Cancel", cancelX + 4, btnY + 4, TEXT_MUTED, false);
	}

	/** The task currently open in the edit modal, or null. */
	private AdminSyncPayload.AdminTaskEntry editingTask(AdminSyncPayload.AdminQuestEntry quest) {
		if (quest == null || editingTaskId == null) {
			return null;
		}
		for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
			if (task.id().equals(editingTaskId)) {
				return task;
			}
		}
		return null;
	}

	/** Where the modal's count field goes, so the widget and the drawn label agree. */
	private int editModalCountX() {
		return editModalX() + 10 + font.width(AMOUNT_LABEL) + LABEL_GAP;
	}

	private int editModalCountY() {
		return editModalY() + 45;
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

		// No quest title, no divider: the selected card on the left already says
		// which quest this is. Rows start after a small gap.
		int headerH = rightHeaderHeight();

		// Tasks live below the header and are clipped to the content box, so the
		// scissor top is the content edge, not the pane top.
		int contentTop = listTop + headerH;
		int y = contentTop - (int) rightScroll;
		g.enableScissor(rightPaneLeft, contentTop, x + w, listBottom);
		if (quest.tasks().isEmpty()) {
			g.text(font, "No tasks yet.", x, y + 2, TEXT_DIM, false);
			y += 14;
		} else {
			for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
				if (y + TASK_ROW_H > contentTop && y < listBottom) {
					y = renderTaskRow(g, quest, task, x, y, w, mouseX, mouseY);
				} else {
					y += taskRowPitch();
				}
			}
		}
		g.disableScissor();

		rightContentHeight = (y + (int) rightScroll) - contentTop;
		renderScrollbar(g, x + w + 1, contentTop, listBottom - contentTop, rightContentHeight, rightScroll);
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
	 */
	private static final int HDR_TITLE_Y = 1;
	/** Glyph height of the 9px font, used to keep rules clear of descenders. */
	private static final int FONT_H = 9;
	/** Gap between the last header line's baseline and the divider rule. */
	private static final int HDR_RULE_GAP = FONT_H + 3;
	private static final int HDR_RULE_H = 1;
	/** Gap between the rule and the first task row. */
	private static final int HDR_RULE_TO_TASKS = 6;

	/** Small gap between the pane top and the first task row. */
	private int rightHeaderHeight() {
		return HDR_RULE_TO_TASKS;
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
		return newQuestDialogOpen || itemPickerOpen || editingTaskId != null;
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
		g.fill(left, top, left + pickerWidth(), top + PICKER_HEADER_H, HEADER_BG);
		g.fill(left, top + PICKER_HEADER_H, left + pickerWidth(), top + PICKER_HEADER_H + 1, CARD_SELECTED_BORDER);

		// Header — the picker only ever adds, so there is one title and no edit mode.
		String title = "Add Task Item";
		g.text(font, title, gridLeft(), top + 6, TEXT_GOLD, false);

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
				// The tab's own name. Passing the icon stack here showed that stack's
				// hover tooltip — the item's name — which is not what the tab means.
				g.setTooltipForNextFrame(font, tab.name(), mouseX, mouseY);
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

		// Footer row geometry. Render and hit-test both read these, because the two
		// used to be written out separately and drifted.
		int bottomY = pickerBottomY();
		int rowH = 14;
		g.text(font, AMOUNT_LABEL, gridLeft(), bottomY + 3, TEXT_WHITE, false);
		int countX = amountFieldX();
		int countW = AMOUNT_FIELD_W;
		int assignBtnX = countX + countW + 6;

		String actionLabel = "+ Add Task";
		int addBtnW = font.width(actionLabel) + 10;
		int addBtnX = gridRight() - addBtnW;
		// Pill fills whatever the add button leaves, down to the count field.
		int assignBtnW = addBtnX - 6 - assignBtnX;
		String assignLabel = resolveAssigneeLabel();
		boolean showAssign = assignBtnW >= font.width("\u25BE " + assignLabel) + 10;

		if (showAssign) {
			boolean assignHover = mouseX >= assignBtnX && mouseX <= assignBtnX + assignBtnW && mouseY >= bottomY && mouseY <= bottomY + rowH;
			g.fill(assignBtnX, bottomY, assignBtnX + assignBtnW, bottomY + rowH, assignHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
			// Label from the live player list when the id is still online. The server's
			// fallback is a truncated UUID so a task never loses its attribution, but a
			// UUID fragment is not something to offer as the current assignee.
			int assignColor = selectedAssigneeId.equals(Task.UNASSIGNED) ? TEXT_RED : TEXT_GREEN;
			g.text(font, "\u25BE " + assignLabel, assignBtnX + 4, bottomY + 3, assignColor, false);
		}

		boolean canAct = selectedQuestId != null && !selectedItem.isEmpty();
		boolean actHover = mouseX >= addBtnX && mouseX <= addBtnX + addBtnW && mouseY >= bottomY && mouseY <= bottomY + rowH;
		g.fill(addBtnX, bottomY, addBtnX + addBtnW, bottomY + rowH, canAct ? (actHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY) : 0xFF3E3E42);
		g.text(font, actionLabel, addBtnX + 5, bottomY + 3, canAct ? TEXT_WHITE : TEXT_DIM, false);
	}

	/**
	 * Name to show for a task's assignee. The server now sends the name recorded at
	 * assignment time, which survives a logout — so this only has to prefer the live
	 * name for an assignee who is online right now (a player may have renamed).
	 */
	private String resolveTaskAssigneeLabel(AdminSyncPayload.AdminTaskEntry task) {
		if (task.assignee().equals(Task.UNASSIGNED)) {
			return "Unassigned";
		}
		for (AdminSyncPayload.PlayerEntry p : ClientAdminState.players()) {
			if (p.id().equals(task.assignee())) {
				return p.name();
			}
		}
		String stored = task.assigneeName();
		return stored == null || stored.isEmpty() ? "Unknown player" : stored;
	}

	/**
	 * Name to show on the assignee pill. Same rule as {@link #resolveTaskAssigneeLabel}:
	 * live name when the assignee is online, otherwise the name recorded when they were
	 * assigned — which is what makes the label survive a logout.
	 */
	private String resolveAssigneeLabel() {
		if (selectedAssigneeId.equals(Task.UNASSIGNED)) {
			return "Unassigned";
		}
		for (AdminSyncPayload.PlayerEntry p : ClientAdminState.players()) {
			if (p.id().equals(selectedAssigneeId)) {
				return p.name();
			}
		}
		return selectedAssigneeName == null || selectedAssigneeName.isEmpty()
				? "Unknown player" : selectedAssigneeName;
	}

	/** Opens the add-task picker. It never edits: see {@link #startEditingTask}. */
	private void openItemPicker() {
		itemPickerOpen = true;
		editingTaskId = null;
		if (itemSearchField != null) {
			itemSearchField.visible = true;
			itemSearchField.setValue("");
		}
		if (countField != null) {
			countField.visible = true;
			countField.setValue("16");
		}
		selectedItem = ItemStack.EMPTY;
		selectedGridIndex = -1;
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
		selectedItem = ItemStack.EMPTY;
		selectedGridIndex = -1;
	}

	/** Selects a grid slot from the list currently on screen. */
	private void selectGridIndex(int idx) {
		String query = itemSearchField != null ? itemSearchField.getValue() : "";
		List<ItemStack> items = ItemCatalog.filter(selectedTab, query);
		if (idx < 0 || idx >= items.size()) {
			selectedGridIndex = -1;
			selectedItem = ItemStack.EMPTY;
			return;
		}
		selectedGridIndex = idx;
		selectedItem = items.get(idx).copy();
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
						selectGridIndex(idx);
					}
					return true;
				}
			}
		}

		// Footer row: Amount / assignee pill / Add. Geometry must match the render path
		// exactly or the hit-test drifts from the pixels.
		int bottomY = pickerBottomY();
		int rowH = 14;
		int countX = amountFieldX();
		int countW = AMOUNT_FIELD_W;
		int assignBtnX = countX + countW + 6;

		String actionLabel = "+ Add Task";
		int addBtnW = font.width(actionLabel) + 10;
		int addBtnX = gridRight() - addBtnW;
		int assignBtnW = addBtnX - 6 - assignBtnX;
		if (assignBtnW >= font.width("\u25BE " + resolveAssigneeLabel()) + 10
				&& mx >= assignBtnX && mx <= assignBtnX + assignBtnW && my >= bottomY && my <= bottomY + rowH) {
			assigneeDropdownOpen = true;
			assigneeDropdownX = assignBtnX;
			assigneeDropdownY = bottomY;
			return true;
		}

		if (mx >= addBtnX && mx <= addBtnX + addBtnW && my >= bottomY && my <= bottomY + rowH) {
			executeAddTask();
			return true;
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
		g.text(font, renameMode ? "Rename Quest" : "Create New Quest", modalX + 8, modalY + 6, TEXT_GOLD, false);

		// [✕] Close
		int closeX = modalX + modalW - 14;
		int closeY = modalY + 6;
		boolean closeHover = mouseX >= closeX - 2 && mouseX <= closeX + 10 && mouseY >= closeY - 2 && mouseY <= closeY + 10;
		g.text(font, "\u2715", closeX, closeY, closeHover ? TEXT_RED : TEXT_MUTED, false);

		// Buttons Row
		int btnY = modalY + 58;

		// [Create Quest] / [Rename]
		String createLabel = renameMode ? "Rename" : "Create Quest";
		int createBtnW = font.width(createLabel) + 8;
		int createBtnX = modalX + modalW - createBtnW - 12;
		boolean canCreate = newQuestNameField != null && !newQuestNameField.getValue().trim().isEmpty();
		boolean createHover = mouseX >= createBtnX && mouseX <= createBtnX + createBtnW && mouseY >= btnY && mouseY <= btnY + 16;
		g.fill(createBtnX, btnY, createBtnX + createBtnW, btnY + 16, canCreate ? (createHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY) : 0xFF3E3E42);
		g.text(font, createLabel, createBtnX + 4, btnY + 4, canCreate ? TEXT_WHITE : TEXT_DIM, false);

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
		} else if (editingTaskId != null) {
			bx = Mth.clamp(assigneeDropdownX, editModalX() + 4, editModalX() + EDIT_MODAL_W - boxW - 4);
			by = Mth.clamp(assigneeDropdownY - boxH - 2, editModalY() + 4,
					editModalY() + EDIT_MODAL_H - boxH - 4);
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

		// Edit Task modal
		if (editingTaskId != null) {
			if (assigneeDropdownOpen) {
				if (handleDropdownClick(mx, my)) {
					return true;
				}
				assigneeDropdownOpen = false;
				return true;
			}
			return handleEditModalClick(event, mx, my);
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

		// [+ Add Task] — header spot; needs a selected quest to add to.
		int newBtnW = font.width("+ Add Task") + 8;
		int newBtnH = 14;
		int newBtnX = panelLeft + panelWidth - newBtnW - 22;
		int newBtnY = panelTop + 4;
		if (selectedQuest() != null && mx >= newBtnX && mx <= newBtnX + newBtnW && my >= newBtnY && my <= newBtnY + newBtnH) {
			openItemPicker();
			return true;
		}

		// [AI Prompt] — same geometry as the render path.
		int aiW = font.width(aiCopiedTicks > 0 ? "Copied!" : "Prompt") + 8;
		int aiX = newBtnX - aiW - 6;
		if (!modalOpen() && mx >= aiX && mx <= aiX + aiW && my >= newBtnY && my <= newBtnY + newBtnH) {
			minecraft.keyboardHandler.setClipboard(IMPORT_PROMPT);
			aiCopiedTicks = 40;
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
		renameMode = false;
		newQuestDialogOpen = true;
		if (newQuestNameField != null) {
			newQuestNameField.setValue("");
			newQuestNameField.visible = true;
			newQuestNameField.setFocused(true);
			setFocused(newQuestNameField);
		}
	}

	/** Same dialog, committed as a rename instead of a create. */
	private void openRenameDialog(AdminSyncPayload.AdminQuestEntry quest) {
		renameMode = true;
		newQuestDialogOpen = true;
		if (newQuestNameField != null) {
			newQuestNameField.setValue(quest.name());
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

		// [Create Quest] / [Rename] — label must match the render path's width.
		String createLabel = renameMode ? "Rename" : "Create Quest";
		int createBtnW = font.width(createLabel) + 8;
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
		String raw = newQuestNameField.getValue().trim();
		if (raw.isEmpty()) return;

		if (renameMode) {
			if (selectedQuestId != null && ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
				ClientPlayNetworking.send(AdminActionPayload.renameQuest(selectedQuestId, raw));
			}
			closeNewQuestDialog();
			return;
		}

		// Text starting with { or [ is a bulk import, not a name.
		if (raw.startsWith("{") || raw.startsWith("[")) {
			if (!importQuestsJson(raw)) {
				return; // keep the dialog open on bad JSON
			}
			closeNewQuestDialog();
			return;
		}

		if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
			ClientPlayNetworking.send(AdminActionPayload.createQuest(raw, List.of()));
		}

		closeNewQuestDialog();
	}

	/**
	 * Creates quests from JSON. Accepted shape: an object or array of objects, each
	 * with a required "name" and optional "tasks" —
	 * {@code [{"name":"Wood","tasks":[{"item":"oak_log","count":64,"player":"Alex"}]}]}.
	 * A bare item id gets the minecraft: namespace; unknown players leave the task
	 * unassigned. Returns whether anything was sent.
	 */
	private boolean importQuestsJson(String json) {
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(json);
		} catch (Exception e) {
			QuestBook.LOGGER.warn("Quest import: not valid JSON");
			return false;
		}

		List<JsonElement> questEls = new ArrayList<>();
		if (parsed.isJsonArray()) {
			parsed.getAsJsonArray().forEach(questEls::add);
		} else if (parsed.isJsonObject()) {
			questEls.add(parsed);
		} else {
			return false;
		}

		boolean sent = false;
		for (JsonElement qe : questEls) {
			if (!qe.isJsonObject()) continue;
			JsonObject q = qe.getAsJsonObject();
			String name = q.has("name") && q.get("name").isJsonPrimitive() ? q.get("name").getAsString().trim() : "";
			if (name.isEmpty()) continue;

			List<AdminActionPayload.NewTaskData> tasks = new ArrayList<>();
			if (q.has("tasks") && q.get("tasks").isJsonArray()) {
				for (JsonElement te : q.get("tasks").getAsJsonArray()) {
					if (!te.isJsonObject()) continue;
					JsonObject t = te.getAsJsonObject();
					String item = t.has("item") && t.get("item").isJsonPrimitive() ? t.get("item").getAsString().trim() : "";
					if (item.isEmpty() || !item.contains(":")) item = "minecraft:" + item;
					if (Identifier.tryParse(item) == null) continue;
					int count = t.has("count") && t.get("count").isJsonPrimitive() ? Math.max(1, t.get("count").getAsInt()) : 1;
					tasks.add(new AdminActionPayload.NewTaskData(item, count, resolvePlayer(t)));
				}
			}

			// A name that matches an existing quest appends to it; otherwise create.
			UUID existing = null;
			for (AdminSyncPayload.AdminQuestEntry quest : ClientAdminState.quests()) {
				if (quest.name().equalsIgnoreCase(name)) {
					existing = quest.id();
					break;
				}
			}

			if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
				if (existing != null) {
					for (AdminActionPayload.NewTaskData task : tasks) {
						ClientPlayNetworking.send(AdminActionPayload.addTask(existing, task));
					}
				} else {
					ClientPlayNetworking.send(AdminActionPayload.createQuest(name, tasks));
				}
				sent = true;
			}
		}
		return sent;
	}

	/** Player named in a task's "player" key, or unassigned when not online. */
	private UUID resolvePlayer(JsonObject t) {
		if (!t.has("player") || !t.get("player").isJsonPrimitive()) {
			return Task.UNASSIGNED;
		}
		String wanted = t.get("player").getAsString();
		for (AdminSyncPayload.PlayerEntry p : ClientAdminState.players()) {
			if (p.name().equalsIgnoreCase(wanted)) {
				return p.id();
			}
		}
		return Task.UNASSIGNED;
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

	/** Puts text in the picker's search field so a capture can photograph the value
	 * path. The placeholder is only half the story: the value is drawn by EditBox
	 * itself, from a different origin, so it needs its own shot. */
	public void debugTypeSearch(String text) {
		if (itemSearchField != null) {
			itemSearchField.setValue(text);
		}
	}

	/**
	 * Opens the first task in the edit modal. The state to capture is the modal: the
	 * item icon, the amount field, the assignee pill and Save/Cancel.
	 */
	public void debugOpenEditTask() {
		AdminSyncPayload.AdminQuestEntry quest = selectedQuest();
		if (quest == null) {
			QuestBook.LOGGER.warn("Editor debug: no quest selected for edit-task state");
			return;
		}
		if (quest.tasks().isEmpty()) {
			QuestBook.LOGGER.warn("Editor debug: quest '{}' has no tasks to edit", quest.name());
			return;
		}
		// Clear the previous step's picker first: only one modal owns the screen.
		closeItemPicker();
		startEditingTask(quest, quest.tasks().get(0));
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

	/**
	 * Grid selection state. The shot alone cannot say whether a slot is unhighlighted
	 * because the index is wrong or because the item is off the visible page, and those
	 * need opposite fixes.
	 */
	public String debugGridState() {
		ItemStack sel = selectedItem;
		List<ItemStack> items = ItemCatalog.filter(selectedTab, itemSearchField == null ? "" : itemSearchField.getValue());
		int idx = selectedGridIndex;
		int cols = Math.max(1, gridCols());
		int rows = gridRows();
		int start = (int) pickerScroll * cols;
		boolean visible = idx >= start && idx < start + cols * rows;
		String at = idx >= 0 && idx < items.size()
				? String.valueOf(BuiltInRegistries.ITEM.getKey(items.get(idx).getItem()))
				: "<out of range>";
		return "tab=" + selectedTab + " index=" + idx + " firstVisible=" + start
				+ " visible=" + visible + " size=" + items.size()
				+ " itemAt(index)=" + at
				+ " selectedItem=" + (sel.isEmpty() ? "<empty>" : sel.getHoverName().getString())
				+ " editingTask=" + (editingTaskId != null)
				+ " countValue=" + (countField == null ? "<null>" : countField.getValue())
				+ " countVisible=" + (countField != null && countField.visible);
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
		} else if (editingTaskId != null) {
			// The edit modal centres on the screen, not the panel: clamping to the
			// panel put the hit box where the dropdown is not. Mirrors the render path.
			bx = Mth.clamp(assigneeDropdownX, editModalX() + 4, editModalX() + EDIT_MODAL_W - boxW - 4);
			by = Mth.clamp(assigneeDropdownY - boxH - 2, editModalY() + 4,
					editModalY() + EDIT_MODAL_H - boxH - 4);
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

		// [+ New Quest] at the pane bottom, before the cards.
		int addBtnY = leftAddButtonY();
		if (mx >= leftPaneLeft && mx <= leftPaneLeft + leftAddButtonW() && my >= addBtnY && my <= addBtnY + LEFT_ADD_H) {
			openNewQuestDialog();
			return true;
		}

		for (AdminSyncPayload.AdminQuestEntry quest : ClientAdminState.quests()) {
			int cardH = QUEST_CARD_H;
			int cardW = leftPaneWidth - 6;
			int cardX = leftPaneLeft;

			if (my >= y && my < y + cardH) {
				// Pencil — rename, left of the progress tally.
				int progStrW = font.width(quest.tasks().size() + "/" + quest.tasks().size());
				int progX2 = cardX + cardW - 10 - 4 - progStrW;
				int penX = progX2 - 12;
				if (mx >= penX - 2 && mx <= penX + 8 && my >= y + 2 && my <= y + 14) {
					openRenameDialog(quest);
					return true;
				}

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

	/**
	 * Opens a task for editing in {@link #renderTaskEditDialog}. Editing means its
	 * amount and its assignee — the item is fixed once a task exists, because changing
	 * what a quest asks for is not the same act as correcting how many: it is a
	 * different task. Swapping the item is delete + add, which keeps the two intentions
	 * distinct instead of hiding a replacement behind an "update".
	 *
	 * <p>So there is no picker here. The modal shows the item as a static icon.
	 */
	private void startEditingTask(AdminSyncPayload.AdminQuestEntry quest, AdminSyncPayload.AdminTaskEntry task) {
		selectedQuestId = quest.id();
		// Close either other modal: only one owns the screen at a time.
		closeItemPicker();
		newQuestDialogOpen = false;
		if (newQuestNameField != null) {
			newQuestNameField.visible = false;
		}
		editingTaskId = task.id();

		selectedAssigneeId = task.assignee();
		selectedAssigneeName = task.assigneeName();
		assigneeDropdownOpen = false;

		if (countField != null) {
			countField.setValue(String.valueOf(task.need()));
		}
	}

	/** Closes the edit modal, leaving the selection alone. */
	private void closeTaskEditDialog() {
		editingTaskId = null;
		assigneeDropdownOpen = false;
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

		// Pinned header: clicks above the content edge belong to nothing.
		int contentTop = listTop + rightHeaderHeight();
		if (my < contentTop) {
			return false;
		}
		int y = contentTop - (int) rightScroll;

		// Task rows
		for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
			if (y + TASK_ROW_H > contentTop && y < listBottom && my >= y && my < y + TASK_ROW_H) {
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

				// Click to Edit Task — opens the modal; amount and assignee only.
				startEditingTask(quest, task);
				return true;
			}
			y += taskRowPitch();
		}

		return false;
	}

	/**
	 * Modal clicks: pill, Save, Cancel, close. Geometry mirrors
	 * {@link #renderTaskEditDialog} and must be kept in step with it.
	 */
	private boolean handleEditModalClick(net.minecraft.client.input.MouseButtonEvent event, double mx, double my) {
		int modalX = editModalX();
		int modalY = editModalY();

		int closeX = modalX + EDIT_MODAL_W - 14;
		int closeY = modalY + 6;
		if (mx >= closeX - 2 && mx <= closeX + 10 && my >= closeY - 2 && my <= closeY + 10) {
			closeTaskEditDialog();
			return true;
		}

		// Assignee pill
		int pillY = modalY + 45;
		int pillX = modalX + 10 + font.width(AMOUNT_LABEL) + LABEL_GAP + AMOUNT_FIELD_W + 6;
		int pillW = modalX + EDIT_MODAL_W - 10 - pillX;
		if (mx >= pillX && mx <= pillX + pillW && my >= pillY && my <= pillY + 14) {
			assigneeDropdownOpen = true;
			assigneeDropdownX = pillX;
			assigneeDropdownY = pillY;
			return true;
		}

		int btnY = modalY + EDIT_MODAL_H - 22;
		int saveW = font.width("Save") + 10;
		int saveX = modalX + EDIT_MODAL_W - saveW - 10;
		if (mx >= saveX && mx <= saveX + saveW && my >= btnY && my <= btnY + 16) {
			commitTaskEdit();
			return true;
		}

		int cancelW = font.width("Cancel") + 8;
		int cancelX = saveX - cancelW - 6;
		if (mx >= cancelX && mx <= cancelX + cancelW && my >= btnY && my <= btnY + 16) {
			closeTaskEditDialog();
			return true;
		}

		// Pass click through so the count field can take focus.
		return super.mouseClicked(event, false);
	}

	/**
	 * Commits the inline edit: amount and assignee only. The item is not read from the
	 * picker, because an edit cannot change it.
	 */
	private void commitTaskEdit() {
		if (selectedQuestId == null || editingTaskId == null) {
			return;
		}
		if (!ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
			return;
		}
		AdminSyncPayload.AdminQuestEntry quest = selectedQuest();
		if (quest == null) {
			return;
		}
		String itemId = null;
		for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
			if (task.id().equals(editingTaskId)) {
				itemId = task.itemId();
				break;
			}
		}
		if (itemId == null) {
			return;
		}
		ClientPlayNetworking.send(AdminActionPayload.updateTask(selectedQuestId, editingTaskId,
				new AdminActionPayload.NewTaskData(itemId, parseCount(), selectedAssigneeId)));
		closeTaskEditDialog();
	}

	/** Count field as a bounded int; falls back to 1 on anything unparseable. */
	private int parseCount() {
		int count = 1;
		if (countField != null) {
			try {
				count = Integer.parseInt(countField.getValue().trim());
			} catch (NumberFormatException ignored) {
				// Keep the default.
			}
		}
		return Math.max(1, Math.min(1000000, count));
	}

	/** Commits a new task from the picker: item, amount and assignee. */
	private void executeAddTask() {
		if (selectedQuestId == null || selectedItem.isEmpty()) {
			return;
		}

		Identifier id = BuiltInRegistries.ITEM.getKey(selectedItem.getItem());
		if (id == null) {
			return;
		}

		if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
			ClientPlayNetworking.send(AdminActionPayload.addTask(selectedQuestId,
					new AdminActionPayload.NewTaskData(id.toString(), parseCount(), selectedAssigneeId)));
		}

		closeItemPicker();
	}

	@Override
	public void tick() {
		if (aiCopiedTicks > 0) {
			aiCopiedTicks--;
		}
		super.tick();
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
				closeTaskEditDialog();
				return true;
			}
		}

		if (editingTaskId != null && !assigneeDropdownOpen
				&& (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
			commitTaskEdit();
			return true;
		}

		if (newQuestDialogOpen && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
			commitCreateQuest();
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// A modal owns the screen; the panes behind it must not scroll under it.
		if (newQuestDialogOpen || editingTaskId != null) {
			return true;
		}

		if (itemPickerOpen) {
			pickerScroll = Mth.clamp(pickerScroll - scrollY, 0, Math.max(0, pickerContentHeight));
			return true;
		}

		if (mouseX >= leftPaneLeft && mouseX <= leftPaneLeft + leftPaneWidth) {
			int visible = leftListBottom() - leftPaneTop;
			int maxScroll = Math.max(0, leftContentHeight - visible);
			leftScroll = Mth.clamp(leftScroll - scrollY * 16, 0, maxScroll);
			return true;
		}

		if (mouseX >= rightPaneLeft && mouseX <= rightPaneLeft + rightPaneWidth) {
			int visible = rightPaneHeight - rightHeaderHeight();
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
