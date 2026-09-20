package com.questbook.mixin;

import com.questbook.tracking.ProgressTracker;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports item pickups to {@link ProgressTracker}.
 *
 * <p>There is no Fabric API event for item pickup, so a mixin is required. It
 * injects immediately before {@code Player.onItemPickup}, which
 * {@code ItemEntity.playerTouch} calls only after the inventory has actually
 * accepted the stack — so a full inventory or a pickup-delay item never counts.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Inject(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;onItemPickup(Lnet/minecraft/world/entity/item/ItemEntity;)V"))
	private void questbook$onPickup(Player player, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		ItemStack stack = ((ItemEntity) (Object) this).getItem();

		if (stack.isEmpty()) {
			return;
		}

		ProgressTracker.onPickup(serverPlayer, stack);
	}
}
