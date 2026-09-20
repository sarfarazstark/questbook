package com.questbook.client.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Client-side item catalog with robust item classification.
 */
public final class ItemCatalog {
	public record Tab(String id, Component name, ItemStack icon, List<ItemStack> items) {
	}

	private static final List<Tab> TABS = new ArrayList<>();
	private static boolean initialized = false;

	private ItemCatalog() {
	}

	public static void init() {
		if (initialized && !TABS.isEmpty()) {
			return;
		}

		TABS.clear();

		List<ItemStack> allItems = new ArrayList<>();
		List<ItemStack> blocks = new ArrayList<>();
		List<ItemStack> tools = new ArrayList<>();
		List<ItemStack> combat = new ArrayList<>();
		List<ItemStack> food = new ArrayList<>();
		List<ItemStack> redstone = new ArrayList<>();
		List<ItemStack> ingredients = new ArrayList<>();

		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR) continue;
			ItemStack stack = new ItemStack(item);
			allItems.add(stack);

			Identifier key = BuiltInRegistries.ITEM.getKey(item);
			String path = key != null ? key.getPath() : "";

			boolean isBlock = item instanceof BlockItem;

			boolean isFood = stack.has(DataComponents.FOOD)
					|| stack.has(DataComponents.CONSUMABLE)
					|| path.contains("stew")
					|| path.contains("soup")
					|| path.contains("potion")
					|| path.contains("bottle")
					|| path.contains("bread")
					|| path.contains("apple")
					|| path.contains("beef")
					|| path.contains("pork")
					|| path.contains("chicken")
					|| path.contains("mutton")
					|| path.contains("cookie")
					|| path.contains("pie")
					|| path.contains("cake");

			boolean isCombat = stack.has(DataComponents.EQUIPPABLE)
					|| stack.has(DataComponents.DAMAGE)
					|| path.contains("sword")
					|| path.contains("helmet")
					|| path.contains("chestplate")
					|| path.contains("leggings")
					|| path.contains("boots")
					|| path.contains("shield")
					|| path.contains("bow")
					|| path.contains("crossbow")
					|| path.contains("arrow")
					|| path.contains("trident")
					|| path.contains("mace");

			boolean isTool = stack.has(DataComponents.TOOL)
					|| path.contains("pickaxe")
					|| path.contains("axe")
					|| path.contains("shovel")
					|| path.contains("hoe")
					|| path.contains("shears")
					|| path.contains("fishing_rod")
					|| path.contains("flint_and_steel")
					|| path.contains("compass")
					|| path.contains("clock")
					|| path.contains("lead")
					|| path.contains("spyglass")
					|| path.contains("bucket")
					|| path.contains("bundle")
					|| path.contains("elytra");

			boolean isRedstone = path.contains("redstone")
					|| path.contains("repeater")
					|| path.contains("comparator")
					|| path.contains("lever")
					|| path.contains("button")
					|| path.contains("pressure_plate")
					|| path.contains("piston")
					|| path.contains("observer")
					|| path.contains("dropper")
					|| path.contains("dispenser")
					|| path.contains("hopper")
					|| path.contains("tnt")
					|| path.contains("tripwire")
					|| path.contains("target")
					|| path.contains("daylight")
					|| path.contains("sculk_sensor")
					|| path.contains("rail")
					|| path.contains("command_block");

			if (isFood) {
				food.add(stack);
			} else if (isCombat) {
				combat.add(stack);
			} else if (isTool) {
				tools.add(stack);
			} else if (isRedstone) {
				redstone.add(stack);
			} else if (isBlock) {
				blocks.add(stack);
			} else {
				ingredients.add(stack);
			}
		}

		// 1. All Items
		TABS.add(new Tab("all", Component.literal("All Items"), new ItemStack(Items.COMPASS), allItems));

		// 2. Blocks
		TABS.add(new Tab("blocks", Component.literal("Building & Blocks"), new ItemStack(Items.BRICKS), blocks));

		// 3. Tools & Utilities
		TABS.add(new Tab("tools", Component.literal("Tools & Utilities"), new ItemStack(Items.DIAMOND_PICKAXE), tools));

		// 4. Combat & Armor
		TABS.add(new Tab("combat", Component.literal("Combat & Armor"), new ItemStack(Items.NETHERITE_SWORD), combat));

		// 5. Food & Drinks
		TABS.add(new Tab("food", Component.literal("Food & Drinks"), new ItemStack(Items.GOLDEN_APPLE), food));

		// 6. Redstone & Automation
		TABS.add(new Tab("redstone", Component.literal("Redstone & Automation"), new ItemStack(Items.REDSTONE), redstone));

		// 7. Materials & Ingredients
		TABS.add(new Tab("ingredients", Component.literal("Materials & Ingredients"), new ItemStack(Items.IRON_INGOT), ingredients));

		// Also check if vanilla creative tabs have real displayItems populated
		for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
			if (tab.getType() == CreativeModeTab.Type.CATEGORY) {
				Collection<ItemStack> displayItems = tab.getDisplayItems();
				if (displayItems != null && !displayItems.isEmpty()) {
					List<ItemStack> tabItems = new ArrayList<>(displayItems);
					ItemStack icon = tab.getIconItem();
					if (icon.isEmpty()) icon = tabItems.get(0);
					Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
					String key = id != null ? id.getPath() : tab.getDisplayName().getString();
					TABS.add(new Tab(key, tab.getDisplayName(), icon, tabItems));
				}
			}
		}

		initialized = true;
	}

	public static List<Tab> tabs() {
		init();
		return TABS;
	}

	public static List<ItemStack> filter(int tabIndex, String query) {
		init();
		if (TABS.isEmpty()) {
			return List.of();
		}

		int idx = Math.max(0, Math.min(tabIndex, TABS.size() - 1));
		List<ItemStack> source = TABS.get(idx).items();

		String needle = query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return source;
		}

		List<ItemStack> result = new ArrayList<>();
		for (ItemStack stack : source) {
			Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
			String regId = key != null ? key.toString().toLowerCase(Locale.ROOT) : "";
			String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);

			if (name.contains(needle) || regId.contains(needle)) {
				result.add(stack);
			}
		}
		return result;
	}
}
