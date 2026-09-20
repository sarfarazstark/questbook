package com.questbook.client.gui;

import com.questbook.client.ClientAdminState;
import com.questbook.data.Task;
import com.questbook.network.AdminActionPayload;
import com.questbook.network.AdminSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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

	// Color Palette
	private static final int BG_BACKDROP = 0x88000000;
	private static final int PANEL_BG = 0xF0181A20;
	private static final int PANEL_BORDER = 0xFF2A2D37;
	private static final int HEADER_BG = 0xFF121318;

	private static final int TEXT_WHITE = 0xFFFFFFFF;
	private static final int TEXT_MUTED = 0xFFB0B4C0;
	private static final int TEXT_DIM = 0xFF6B7280;
	private static final int TEXT_GOLD = 0xFFFFD54F;
	private static final int TEXT_GREEN = 0xFF81C784;
	private static final int TEXT_RED = 0xFFE57373;
	private static final int TEXT_CYAN = 0xFF80DEEA;

	private static final int CARD_BG = 0xFF1C1E26;
	private static final int CARD_HOVER = 0xFF282B37;
	private static final int CARD_SELECTED = 0xFF2A3142;
	private static final int CARD_SELECTED_BORDER = 0xFF5C8DF6;

	private static final int BTN_PRIMARY = 0xFF2E7D32;
	private static final int BTN_PRIMARY_HOVER = 0xFF388E3C;
	private static final int BTN_SECONDARY = 0xFF2A2C33;
	private static final int BTN_SECONDARY_HOVER = 0xFF383A44;

	private static final int SLOT_BG = 0xFF1F2026;
	private static final int SLOT_HOVER = 0xFF363944;
	private static final int SLOT_SELECTED = 0xFF2E5E3A;
	private static final int SLOT_SELECTED_BORDER = 0xFF81C784;

	private static final int SLOT_SIZE = 18;

	private static boolean open;

	public static boolean isOpen() {
		return open;
	}

	// Layout bounds
	private int panelLeft;
	private int panelTop;
	private int panelWidth = 490;
	private int panelHeight = 236;

	private int leftPaneLeft;
	private int leftPaneTop;
	private int leftPaneWidth = 184;
	private int leftPaneHeight;

	private int rightPaneLeft;
	private int rightPaneTop;
	private int rightPaneWidth;
	private int rightPaneHeight;

	// Master State (Left Pane)
	private final Set<UUID> expandedQuests = new HashSet<>();
	private UUID selectedQuestId = null;
	private double leftScroll = 0;
	private int leftContentHeight = 0;

	// Editing Task State
	private UUID editingTaskId = null; // null = adding new task, non-null = editing existing

	// Detail Controls (Right Pane)
	private EditBox itemSearchField;
	private EditBox countField;

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
	private EditBox newQuestNameField;

	public AdminQuestScreen() {
		super(Component.literal("Quest Editor"));
	}

	private int gridCols() {
		return Math.max(1, (rightPaneWidth - 8) / SLOT_SIZE);
	}

	private int gridRows() {
		return Math.max(1, (getBottomControlsY() - getGridY() - 4) / SLOT_SIZE);
	}

	private int gridRightEdge() {
		return rightPaneLeft + gridCols() * SLOT_SIZE + 4;
	}
	@Override
	protected void init() {
		open = true;
		ItemCatalog.init();

		panelWidth = Math.min(width - 16, 490);
		panelHeight = Math.min(height - 16, 236);
		panelLeft = (width - panelWidth) / 2;
		panelTop = (height - panelHeight) / 2;

		int headerH = 22;
		int contentY = panelTop + headerH + 2;
		int contentH = panelHeight - headerH - 4;

		leftPaneLeft = panelLeft + 4;
		leftPaneTop = contentY;
		leftPaneWidth = 184;
		leftPaneHeight = contentH;

		rightPaneLeft = leftPaneLeft + leftPaneWidth + 6;
		rightPaneTop = leftPaneTop;
		rightPaneWidth = panelLeft + panelWidth - 4 - rightPaneLeft;
		rightPaneHeight = leftPaneHeight;

		// Default selection to first quest if none selected
		List<AdminSyncPayload.AdminQuestEntry> quests = ClientAdminState.quests();
		if (selectedQuestId == null && !quests.isEmpty()) {
			selectedQuestId = quests.get(0).id();
			expandedQuests.add(selectedQuestId);
		}

		initControls();
	}

	private int getHeaderInfoY() {
		return rightPaneTop + 2;
	}

	private int getTabsY() {
		return getHeaderInfoY() + 20;
	}

	private int getSearchY() {
		return getTabsY() + 18;
	}

	private int getGridY() {
		return getSearchY() + 17;
	}

	private int getBottomControlsY() {
		return rightPaneTop + rightPaneHeight - 16;
	}

	private void initControls() {
		clearWidgets();

		// Search Field (perfectly aligned to grid right edge)
		int searchY = getSearchY();
		int searchW = gridRightEdge() - rightPaneLeft;
		itemSearchField = new EditBox(font, rightPaneLeft, searchY, searchW, 14, Component.literal("Search"));
		itemSearchField.setHint(Component.literal("Search items & blocks..."));
		itemSearchField.setMaxLength(32);
		itemSearchField.setResponder(s -> {
			pickerScroll = 0;
			selectedGridIndex = -1;
		});
		addRenderableWidget(itemSearchField);

		// Amount / Count Input Box (spacious 54px width)
		int bottomY = getBottomControlsY();
		int countX = rightPaneLeft + font.width("Amount:") + 4;
		int countW = 54;
		countField = new EditBox(font, countX, bottomY, countW, 14, Component.literal("Amount"));
		countField.setValue("16");
		countField.setMaxLength(6);
		addRenderableWidget(countField);

		// Modal New Quest Name Field (generous width)
		int modalW = 240;
		int modalH = 88;
		int modalX = (width - modalW) / 2;
		int modalY = (height - modalH) / 2;
		newQuestNameField = new EditBox(font, modalX + 12, modalY + 30, modalW - 24, 16, Component.literal("Quest Name"));
		newQuestNameField.setHint(Component.literal("Enter quest name..."));
		newQuestNameField.setMaxLength(64);
		addRenderableWidget(newQuestNameField);
		newQuestNameField.visible = newQuestDialogOpen;
	}

	// --- RENDERING -----------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, BG_BACKDROP);

		// Main Studio Frame
		renderStudioFrame(g, mouseX, mouseY);

		// Left: Master Quest List
		renderLeftPane(g, mouseX, mouseY);

		// Separator
		g.fill(rightPaneLeft - 3, leftPaneTop, rightPaneLeft - 2, leftPaneTop + leftPaneHeight, PANEL_BORDER);

		// Right: Task Editor / Item Picker
		renderRightPane(g, mouseX, mouseY);

		// Update widget visibility
		if (itemSearchField != null) itemSearchField.visible = !newQuestDialogOpen;
		if (countField != null) countField.visible = !newQuestDialogOpen;
		if (newQuestNameField != null) newQuestNameField.visible = newQuestDialogOpen;

		// Modal Dialog Frame (rendered BEFORE widgets so newQuestNameField is drawn on top)
		if (newQuestDialogOpen) {
			renderNewQuestDialog(g, mouseX, mouseY);
		}

		// Render widgets (EditBox fields) on top of panels/dialog
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		// Dropdown overlays (on top of everything)
		renderAssigneeDropdown(g, mouseX, mouseY);
	}

	private void renderStudioFrame(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		// Main Box
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
			g.text(font, "No quests yet.", leftPaneLeft + 8, listTop + 14, TEXT_DIM, false);
			g.text(font, "Click '+ New Quest' above.", leftPaneLeft + 8, listTop + 26, TEXT_DIM, false);
			leftContentHeight = 0;
			return;
		}

		for (AdminSyncPayload.AdminQuestEntry quest : quests) {
			y = renderQuestCard(g, quest, y, listTop, listBottom, mouseX, mouseY);
		}

		leftContentHeight = (y + (int) leftScroll) - listTop;
		renderScrollbar(g, leftPaneLeft + leftPaneWidth - 3, listTop, listBottom - listTop, leftContentHeight, leftScroll);
	}

	private int renderQuestCard(GuiGraphicsExtractor g, AdminSyncPayload.AdminQuestEntry quest, int y, int listTop, int listBottom, int mouseX, int mouseY) {
		boolean isSelected = quest.id().equals(selectedQuestId);
		boolean isExpanded = expandedQuests.contains(quest.id());

		int cardH = 17;
		int cardW = leftPaneWidth - 6;
		int cardX = leftPaneLeft;

		if (y + cardH > listTop && y < listBottom) {
			boolean hover = mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= y && mouseY < y + cardH && !newQuestDialogOpen;
			int bg = isSelected ? CARD_SELECTED : (hover ? CARD_HOVER : CARD_BG);
			g.fill(cardX, y, cardX + cardW, y + cardH, bg);
			if (isSelected) {
				g.fill(cardX, y, cardX + 2, y + cardH, CARD_SELECTED_BORDER);
			}

			// Caret
			String caret = isExpanded ? "\u25BE" : "\u25B8";
			g.text(font, caret, cardX + 4, y + 4, isSelected ? CARD_SELECTED_BORDER : TEXT_MUTED, false);

			// Quest Name
			int nameMaxW = cardW - 54;
			String name = font.plainSubstrByWidth(quest.name(), nameMaxW);
			g.text(font, name, cardX + 13, y + 4, isSelected ? TEXT_GOLD : TEXT_WHITE, false);

			// Progress Tally
			long doneCount = quest.tasks().stream().filter(AdminSyncPayload.AdminTaskEntry::complete).count();
			String progStr = doneCount + "/" + quest.tasks().size();
			int progX = cardX + cardW - 24 - font.width(progStr);
			g.text(font, progStr, progX, y + 4, doneCount == quest.tasks().size() && !quest.tasks().isEmpty() ? TEXT_GREEN : TEXT_MUTED, false);

			// Delete [✕] button
			int delX = cardX + cardW - 10;
			boolean delHover = mouseX >= delX - 2 && mouseX <= delX + 8 && mouseY >= y + 2 && mouseY <= y + 14 && !newQuestDialogOpen;
			g.text(font, "\u2715", delX, y + 4, delHover ? TEXT_RED : TEXT_DIM, false);

			// Tooltip on quest hover
			if (hover && mouseX < delX - 4) {
				Component tip = Component.literal(quest.name() + " (" + progStr + " done) · Click to select");
				g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
			}
		}
		y += cardH + 2;

		if (isExpanded) {
			if (quest.tasks().isEmpty()) {
				if (y + 12 > listTop && y < listBottom) {
					g.text(font, "No tasks. Add on right \u2192", cardX + 12, y + 2, TEXT_DIM, false);
				}
				y += 14;
			} else {
				for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
					if (y + 15 > listTop && y < listBottom) {
						y = renderTaskRow(g, quest, task, cardX + 6, y, cardW - 8, mouseX, mouseY);
					} else {
						y += 17;
					}
				}
			}
			y += 1;
		}

		return y;
	}

	private int renderTaskRow(GuiGraphicsExtractor g, AdminSyncPayload.AdminQuestEntry quest, AdminSyncPayload.AdminTaskEntry task, int x, int y, int w, int mouseX, int mouseY) {
		int rowH = 15;
		boolean isTaskEditing = task.id().equals(editingTaskId);
		boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH && !newQuestDialogOpen;

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
		boolean delHover = mouseX >= delX - 2 && mouseX <= delX + 8 && mouseY >= y + 1 && mouseY <= y + rowH - 1 && !newQuestDialogOpen;
		g.text(font, "\u2715", delX, y + 3, delHover ? TEXT_RED : TEXT_DIM, false);

		// Tooltip on task hover
		if (hover && mouseX < delX - 4) {
			String action = isTaskEditing ? "Currently editing" : "Click to edit";
			Component tip = Component.literal(task.label() + " x" + task.need() + " (" + task.have() + "/" + task.need() + ") · " + action);
			g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
		}

		return y + rowH + 1;
	}

	// --- RIGHT PANE (DETAIL / ITEM PICKER & TASK EDITOR) --------------------

	private void renderRightPane(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		AdminSyncPayload.AdminQuestEntry selectedQuest = null;
		if (selectedQuestId != null) {
			for (AdminSyncPayload.AdminQuestEntry quest : ClientAdminState.quests()) {
				if (quest.id().equals(selectedQuestId)) {
					selectedQuest = quest;
					break;
				}
			}
		}

		// Top Explanatory Header Banner
		renderRightPaneHeader(g, selectedQuest);

		// Category Tabs Row
		List<ItemCatalog.Tab> tabs = ItemCatalog.tabs();
		int tabX = rightPaneLeft;
		int tabY = getTabsY();
		int tabIconSize = 14;
		int visibleTabCount = Math.min(tabs.size(), 14);

		for (int i = 0; i < visibleTabCount; i++) {
			ItemCatalog.Tab tab = tabs.get(i);
			boolean isSel = i == selectedTab;
			boolean hover = mouseX >= tabX && mouseX <= tabX + tabIconSize + 2 && mouseY >= tabY && mouseY <= tabY + tabIconSize + 2 && !newQuestDialogOpen;

			g.fill(tabX, tabY, tabX + tabIconSize + 2, tabY + tabIconSize + 2, isSel ? CARD_SELECTED : (hover ? CARD_HOVER : SLOT_BG));
			if (isSel) {
				g.fill(tabX, tabY + tabIconSize + 1, tabX + tabIconSize + 2, tabY + tabIconSize + 2, CARD_SELECTED_BORDER);
			}

			g.item(tab.icon(), tabX + 1, tabY + 1);
			if (hover) {
				g.setTooltipForNextFrame(font, tab.icon(), mouseX, mouseY);
			}

			tabX += tabIconSize + 4;
		}

		// Item Grid — at getGridY()
		int gridLeft = rightPaneLeft;
		int gridTop = getGridY();
		String query = itemSearchField != null ? itemSearchField.getValue() : "";
		List<ItemStack> items = ItemCatalog.filter(selectedTab, query);

		int totalSlots = items.size();
		int cols = gridCols();
		int rows = gridRows();
		int maxScrollRows = Math.max(0, (totalSlots + cols - 1) / cols - rows);
		pickerContentHeight = maxScrollRows;

		int scrollRowOffset = (int) pickerScroll;
		int startIndex = scrollRowOffset * cols;
		ItemStack hoveredStack = null;

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int idx = startIndex + row * cols + col;
				int sx = gridLeft + col * SLOT_SIZE;
				int sy = gridTop + row * SLOT_SIZE;

				boolean isSlotHover = mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE && !newQuestDialogOpen;
				boolean isSlotSel = idx == selectedGridIndex;

				g.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, isSlotSel ? SLOT_SELECTED : (isSlotHover ? SLOT_HOVER : SLOT_BG));
				if (isSlotSel) {
					g.fill(sx, sy, sx + SLOT_SIZE - 1, sy + 1, SLOT_SELECTED_BORDER);
				}

				if (idx < items.size()) {
					ItemStack stack = items.get(idx);
					g.item(stack, sx + 1, sy + 1);

					if (isSlotHover) {
						hoveredStack = stack;
					}
				}
			}
		}

		// Grid Scrollbar
		if (maxScrollRows > 0) {
			int sbX = gridLeft + cols * SLOT_SIZE + 2;
			int sbH = rows * SLOT_SIZE;
			int thumbH = Math.max(10, sbH * rows / (rows + maxScrollRows));
			int thumbY = gridTop + (int) ((sbH - thumbH) * (pickerScroll / maxScrollRows));
			g.fill(sbX, gridTop, sbX + 2, gridTop + sbH, 0x40000000);
			g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, CARD_SELECTED_BORDER);
		}

		if (hoveredStack != null) {
			g.setTooltipForNextFrame(font, hoveredStack, mouseX, mouseY);
		}

		// Bottom Controls Row
		int bottomY = getBottomControlsY();
		int rightEdge = gridRightEdge();
		g.text(font, "Amount:", rightPaneLeft, bottomY + 3, TEXT_WHITE, false);

		// Assignee Dropdown Button (spacious 92px width)
		int countX = rightPaneLeft + font.width("Amount:") + 4;
		int countW = 54;
		int assignBtnX = countX + countW + 6;
		int assignBtnW = 92;
		boolean assignHover = mouseX >= assignBtnX && mouseX <= assignBtnX + assignBtnW && mouseY >= bottomY && mouseY <= bottomY + 14 && !newQuestDialogOpen;
		g.fill(assignBtnX, bottomY, assignBtnX + assignBtnW, bottomY + 14, assignHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
		String assignText = "\u25BE " + font.plainSubstrByWidth(selectedAssigneeName, assignBtnW - 12);
		int assignColor = selectedAssigneeId.equals(Task.UNASSIGNED) ? TEXT_RED : TEXT_GREEN;
		g.text(font, assignText, assignBtnX + 4, bottomY + 3, assignColor, false);

		// Action Buttons: [+ Add Task] or [✓ Update Task] + [Cancel] (aligned flush to rightEdge)
		boolean isEditing = editingTaskId != null;
		String actionLabel = isEditing ? "\u2713 Update Task" : "+ Add Task";
		int addBtnW = font.width(actionLabel) + 10;
		int addBtnX = rightEdge - addBtnW;
		boolean canAct = selectedQuestId != null && !selectedItem.isEmpty();
		boolean actHover = mouseX >= addBtnX && mouseX <= addBtnX + addBtnW && mouseY >= bottomY && mouseY <= bottomY + 14 && !newQuestDialogOpen;

		g.fill(addBtnX, bottomY, addBtnX + addBtnW, bottomY + 14, canAct ? (actHover ? BTN_PRIMARY_HOVER : BTN_PRIMARY) : 0xFF3E3E42);
		g.text(font, actionLabel, addBtnX + 5, bottomY + 3, canAct ? TEXT_WHITE : TEXT_DIM, false);

		if (isEditing) {
			int cancelBtnW = font.width("Cancel") + 8;
			int cancelBtnX = addBtnX - cancelBtnW - 4;
			boolean cancelHover = mouseX >= cancelBtnX && mouseX <= cancelBtnX + cancelBtnW && mouseY >= bottomY && mouseY <= bottomY + 14 && !newQuestDialogOpen;
			g.fill(cancelBtnX, bottomY, cancelBtnX + cancelBtnW, bottomY + 14, cancelHover ? BTN_SECONDARY_HOVER : BTN_SECONDARY);
			g.text(font, "Cancel", cancelBtnX + 4, bottomY + 3, TEXT_MUTED, false);
		}
	}

	private void renderRightPaneHeader(GuiGraphicsExtractor g, AdminSyncPayload.AdminQuestEntry quest) {
		int hy = getHeaderInfoY();
		int maxW = rightPaneWidth - 4;

		if (quest == null) {
			g.text(font, "Select a Quest on the left to manage tasks.", rightPaneLeft, hy + 4, TEXT_DIM, false);
			return;
		}
		if (editingTaskId != null) {
			// Editing Task Header
			String pill = "[EDITING TASK]";
			int pillW = font.width(pill);
			g.text(font, pill, rightPaneLeft, hy + 2, TEXT_GOLD, false);

			String questName = "Quest: " + quest.name();
			g.text(font, font.plainSubstrByWidth(questName, maxW - pillW - 6), rightPaneLeft + pillW + 6, hy + 2, TEXT_WHITE, false);

			String itemDisplay = !selectedItem.isEmpty() ? selectedItem.getHoverName().getString() : "None";
			String hint = "Editing item: " + itemDisplay;
			g.text(font, font.plainSubstrByWidth(hint, maxW), rightPaneLeft, hy + 11, TEXT_GREEN, false);
		} else {
			// Adding Task Header
			String pill = "[ADD TASK]";
			int pillW = font.width(pill);
			g.text(font, pill, rightPaneLeft, hy + 2, TEXT_CYAN, false);

			String questName = "Quest: " + quest.name();
			g.text(font, font.plainSubstrByWidth(questName, maxW - pillW - 6), rightPaneLeft + pillW + 6, hy + 2, TEXT_WHITE, false);

			if (!selectedItem.isEmpty()) {
				String itemDisplay = selectedItem.getHoverName().getString();
				String hint = "Selected: " + itemDisplay;
				g.text(font, font.plainSubstrByWidth(hint, maxW), rightPaneLeft, hy + 11, TEXT_GREEN, false);
			} else {
				String hint = "Click an item below to select it";
				g.text(font, font.plainSubstrByWidth(hint, maxW), rightPaneLeft, hy + 11, TEXT_MUTED, false);
			}
		}
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

		int bx = Mth.clamp(assigneeDropdownX, panelLeft + 4, panelLeft + panelWidth - boxW - 4);
		int by = Mth.clamp(assigneeDropdownY - boxH - 2, panelTop + 4, panelTop + panelHeight - boxH - 4);

		g.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, 0xFF000000);
		g.fill(bx, by, bx + boxW, by + boxH, HEADER_BG);

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

	private boolean handleDropdownClick(double mx, double my) {
		List<AdminSyncPayload.PlayerEntry> players = ClientAdminState.players();
		int entryCount = players.size() + 1;
		int rowH = 13;
		int boxW = 88;
		int boxH = entryCount * rowH + 4;

		int bx = Mth.clamp(assigneeDropdownX, panelLeft + 4, panelLeft + panelWidth - boxW - 4);
		int by = Mth.clamp(assigneeDropdownY - boxH - 2, panelTop + 4, panelTop + panelHeight - boxH - 4);

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
			int cardH = 17;
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

				// Caret expand
				if (mx >= cardX && mx <= cardX + 12) {
					if (expandedQuests.contains(quest.id())) {
						expandedQuests.remove(quest.id());
					} else {
						expandedQuests.add(quest.id());
					}
					return true;
				}

				// Select quest (and exit task edit mode)
				selectedQuestId = quest.id();
				editingTaskId = null;
				selectedItem = ItemStack.EMPTY;
				selectedGridIndex = -1;
				return true;
			}
			y += cardH + 2;

			if (expandedQuests.contains(quest.id())) {
				if (quest.tasks().isEmpty()) {
					y += 14;
				} else {
					for (AdminSyncPayload.AdminTaskEntry task : quest.tasks()) {
						int rowH = 15;
						if (my >= y && my < y + rowH) {
							// Delete [✕] Task
							int delX = cardX + 6 + cardW - 8 - 8;
							if (mx >= delX - 2 && mx <= delX + 8) {
								if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
									ClientPlayNetworking.send(AdminActionPayload.deleteTask(quest.id(), task.id()));
								}
								if (task.id().equals(editingTaskId)) {
									editingTaskId = null;
								}
								return true;
							}

							// Click to Edit Task!
							startEditingTask(quest, task);
							return true;
						}
						y += rowH + 1;
					}
				}
				y += 1;
			}
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
	}

	private boolean handleRightPaneClick(double mx, double my) {
		int tabIconSize = 14;
		int tabY = getTabsY();
		int tabX = rightPaneLeft;
		List<ItemCatalog.Tab> tabs = ItemCatalog.tabs();

		for (int i = 0; i < Math.min(tabs.size(), 14); i++) {
			if (mx >= tabX && mx <= tabX + tabIconSize + 2 && my >= tabY && my <= tabY + tabIconSize + 2) {
				selectedTab = i;
				pickerScroll = 0;
				selectedGridIndex = -1;
				return true;
			}
			tabX += tabIconSize + 4;
		}

		// Item Grid slots click — at getGridY()
		int gridLeft = rightPaneLeft;
		int gridTop = getGridY();
		String query = itemSearchField != null ? itemSearchField.getValue() : "";
		List<ItemStack> items = ItemCatalog.filter(selectedTab, query);
		int cols = gridCols();
		int rows = gridRows();
		int scrollRowOffset = (int) pickerScroll;
		int startIndex = scrollRowOffset * cols;

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int idx = startIndex + row * cols + col;
				int sx = gridLeft + col * SLOT_SIZE;
				int sy = gridTop + row * SLOT_SIZE;

				if (mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE) {
					if (idx < items.size()) {
						selectedGridIndex = idx;
						selectedItem = items.get(idx).copy();
						return true;
					}
				}
			}
		}

		// Bottom row
		int bottomY = getBottomControlsY();
		int rightEdge = gridRightEdge();

		// Assignee Dropdown trigger
		int countX = rightPaneLeft + font.width("Amount:") + 4;
		int countW = 54;
		int assignBtnX = countX + countW + 6;
		int assignBtnW = 92;
		if (mx >= assignBtnX && mx <= assignBtnX + assignBtnW && my >= bottomY && my <= bottomY + 14) {
			assigneeDropdownOpen = true;
			assigneeDropdownX = assignBtnX;
			assigneeDropdownY = bottomY;
			return true;
		}

		// Action Button: [+ Add Task] or [✓ Update Task] (flush right)
		boolean isEditing = editingTaskId != null;
		String actionLabel = isEditing ? "\u2713 Update Task" : "+ Add Task";
		int addBtnW = font.width(actionLabel) + 10;
		int addBtnX = rightEdge - addBtnW;
		if (mx >= addBtnX && mx <= addBtnX + addBtnW && my >= bottomY && my <= bottomY + 14) {
			executeTaskAction();
			return true;
		}

		// [Cancel] button when editing
		if (isEditing) {
			int cancelBtnW = font.width("Cancel") + 8;
			int cancelBtnX = addBtnX - cancelBtnW - 4;
			if (mx >= cancelBtnX && mx <= cancelBtnX + cancelBtnW && my >= bottomY && my <= bottomY + 14) {
				editingTaskId = null;
				return true;
			}
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
			editingTaskId = null;
			selectedItem = ItemStack.EMPTY;
			selectedGridIndex = -1;
		} else {
			// Add New Task
			if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
				ClientPlayNetworking.send(AdminActionPayload.addTask(selectedQuestId,
						new AdminActionPayload.NewTaskData(id.toString(), count, selectedAssigneeId)));
			}
			selectedItem = ItemStack.EMPTY;
			selectedGridIndex = -1;
		}
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

		if (mouseX >= leftPaneLeft && mouseX <= leftPaneLeft + leftPaneWidth) {
			int visible = leftPaneHeight;
			int maxScroll = Math.max(0, leftContentHeight - visible);
			leftScroll = Mth.clamp(leftScroll - scrollY * 16, 0, maxScroll);
			return true;
		}

		if (mouseX >= rightPaneLeft && mouseX <= rightPaneLeft + rightPaneWidth) {
			pickerScroll = Mth.clamp(pickerScroll - scrollY, 0, Math.max(0, pickerContentHeight));
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
