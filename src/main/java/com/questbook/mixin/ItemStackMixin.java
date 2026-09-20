package com.questbook.mixin;

import com.questbook.tracking.ProgressTracker;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports crafting output to {@link ProgressTracker}.
 *
 * <p>{@code ItemStack.onCraftedBy} is the single canonical place in Minecraft
 * where crafting output is awarded to a player. It runs on the server for both
 * normal inventory crafting, crafting table clicks, and shift-click (quick-craft)
 * batch operations, with the exact count of items produced.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "onCraftedBy", at = @At("HEAD"))
	private void questbook$onCraftedBy(Player player, int amount, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (amount <= 0) {
			return;
		}

		ItemStack stack = (ItemStack) (Object) this;
		ItemStack craftedBatch = stack.copyWithCount(amount);

		ProgressTracker.onCraft(serverPlayer, craftedBatch);
	}
}
