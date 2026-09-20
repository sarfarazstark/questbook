package com.questbook.tracking;

import com.questbook.QuestBook;
import com.questbook.data.Quest;
import com.questbook.data.Reward;
import com.questbook.data.Task;
import com.questbook.discord.DiscordNotifier;
import com.questbook.storage.QuestSavedData;
import com.questbook.util.QuestText;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Grants quest rewards.
 *
 * <p>Runs on the server tick rather than at the moment of completion, because a
 * command reward must execute on the server thread and the tracker can be invoked
 * from contexts where that is not guaranteed.
 */
public final class RewardGranter {
	private RewardGranter() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(RewardGranter::onTick);
	}

	private static void onTick(MinecraftServer server) {
		QuestSavedData data = Quests.find(server).orElse(null);

		if (data == null) {
			return;
		}

		boolean changed = false;

		for (Quest quest : data.store().quests()) {
			if (!quest.isComplete() || data.isPaid(quest.id())) {
				continue;
			}

			Optional<Reward> reward = data.reward(quest);

			// Mark paid either way, so a quest with no reward is not re-examined every
			// tick, and a reward added later does not retroactively pay out.
			data.markPaid(quest.id());
			changed = true;

			// Credit every player who holds a task in the quest. By the assignee rule
			// that is exactly the set of people whose effort completed it.
			List<UUID> contributors = contributors(quest);

			for (UUID contributor : contributors) {
				data.recordQuestCompletion(contributor);
			}

			reward.filter(Reward::isSane).ifPresent(r -> grant(server, quest, r));

			if (!contributors.isEmpty()) {
				DiscordNotifier.questCompleted(quest.name(), contributors.size());
			}
		}

		for (Quest quest : data.store().quests()) {
			for (Task task : quest.tasks()) {
				if (!task.isComplete() || data.isTaskPaid(task.id())) {
					continue;
				}

				Optional<Reward> reward = task.reward().filter(Reward::isSane);
				ServerPlayer player = server.getPlayerList().getPlayer(task.assignee());

				// Marked even when nobody is online, so an offline assignee does not
				// leave the task re-examined every tick. The alternative — holding it
				// until they log in — needs a persistent pending list, which is what
				// the quest path already refuses to do for the same reason.
				data.markTaskPaid(task.id());
				changed = true;

				if (reward.isPresent() && player != null && !task.isUnassigned()) {
					grantTo(server, player, reward.get());
				}
			}
		}

		if (changed) {
			data.setDirty();
		}
	}

	/** Distinct assignees of {@code quest}'s tasks, unassigned tasks excluded. */
	private static List<UUID> contributors(Quest quest) {
		return quest.tasks().stream().map(Task::assignee).filter(a -> !a.equals(Task.UNASSIGNED))
				.distinct().toList();
	}

	/** Gives {@code reward} to each player holding a task in {@code quest}. */
	private static void grant(MinecraftServer server, Quest quest, Reward reward) {
		List<UUID> recipients = contributors(quest);

		if (recipients.isEmpty()) {
			return;
		}

		for (UUID id : recipients) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);

			if (player == null) {
				// Offline: skipped rather than queued. Queuing needs a persistent
				// per-player pending list; out of scope for this phase.
				QuestBook.LOGGER.info("Reward for quest '{}' skipped: {} is offline", quest.name(), id);
				continue;
			}

			grantTo(server, player, reward);
		}

		String desc = switch (reward.kind()) {
			case ITEM -> reward.amount() + "x " + QuestText.displayName(reward.value());
			case XP -> reward.amount() + " Levels XP";
			case COMMAND -> "Bonus Reward";
		};
		server.getPlayerList().broadcastSystemMessage(
				QuestText.rewardGranted(quest.name(), desc), false);
		DiscordNotifier.rewardGranted(quest.name(), desc, recipients.size());
	}

	/**
	 * Gives {@code reward} to one player.
	 *
	 * <p>Public because the task path grants its own reward: a task reward is
	 * assignee-only, so it cannot go through {@link #grant}'s recipient walk.
	 */
	public static void grantTo(MinecraftServer server, ServerPlayer player, Reward reward) {
		switch (reward.kind()) {
			case ITEM -> giveItem(player, reward);
			case XP -> player.giveExperienceLevels(reward.amount());
			case COMMAND -> runCommand(server, player, reward);
		}
	}

	private static void giveItem(ServerPlayer player, Reward reward) {
		Identifier itemId = Identifier.tryParse(reward.value());

		if (itemId == null) {
			QuestBook.LOGGER.warn("Reward item id is not a valid identifier: {}", reward.value());
			return;
		}

		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId);

		if (item.isEmpty()) {
			QuestBook.LOGGER.warn("Reward item does not exist: {}", reward.value());
			return;
		}

		ItemStack stack = new ItemStack(item.get(), reward.amount());

		if (!player.getInventory().add(stack)) {
			// Remainder did not fit; drop it rather than silently losing it.
			player.drop(stack, false);
		}
	}

	/**
	 * Runs a command reward as the server.
	 *
	 * <p>{@code %player%} is replaced with the recipient's name, taken from the
	 * player object rather than any user input, so it cannot inject syntax.
	 */
	private static void runCommand(MinecraftServer server, ServerPlayer player, Reward reward) {
		reward.commandFor(player.getGameProfile().name()).ifPresent(command -> {
			CommandSourceStack source = server.createCommandSourceStack()
					.withSuppressedOutput()
					.withMaximumPermission(PermissionSet.ALL_PERMISSIONS);

			server.getCommands().performPrefixedCommand(source, command);
		});
	}
}
