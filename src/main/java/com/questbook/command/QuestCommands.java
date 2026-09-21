package com.questbook.command;

import com.questbook.data.Quest;
import com.questbook.data.PlayerStats;
import com.questbook.discord.DiscordConfig;
import com.questbook.discord.DiscordNotifier;
import com.questbook.network.QuestNetworking;
import com.questbook.data.QuestStore;
import com.questbook.data.Task;
import com.questbook.storage.QuestSavedData;
import com.questbook.tracking.Quests;
import com.questbook.util.QuestText;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers {@code /questbook} and its subcommands.
 *
 * <p>Editing requires OP level 2, because a reward may be a server command and is
 * therefore a privilege-escalation surface. Reading commands need no permission.
 */
public final class QuestCommands {
	private QuestCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("questbook")
				.then(Commands.literal("new")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("quest", StringArgumentType.greedyString())
								.executes(QuestCommands::newQuest)))
				.then(Commands.literal("add")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("quest", StringArgumentType.string())
								.then(Commands.argument("player", EntityArgument.player())
										.then(Commands.argument("item", IdentifierArgument.id())
												.then(Commands.argument("count", IntegerArgumentType.integer(1))
														.executes(QuestCommands::addTask))))))
				.then(Commands.literal("delete")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("quest", StringArgumentType.string())
								.executes(QuestCommands::deleteQuest)))
				.then(Commands.literal("assign")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("quest", StringArgumentType.string())
								.then(Commands.argument("task", StringArgumentType.string())
										.then(Commands.argument("player", EntityArgument.player())
												.executes(QuestCommands::assign)))))
				.then(Commands.literal("discord")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("url")
								.then(Commands.argument("webhook", StringArgumentType.greedyString())
										.executes(QuestCommands::discordUrl)))
						.then(Commands.literal("off")
								.executes(QuestCommands::discordOff))
						.then(Commands.literal("test")
								.executes(QuestCommands::discordTest))
						.then(Commands.literal("status")
								.executes(QuestCommands::discordStatus)))
				.then(Commands.literal("list").executes(QuestCommands::list))
				.then(Commands.literal("tree").executes(QuestCommands::tree))
				.then(Commands.literal("top")
						.then(Commands.argument("limit", IntegerArgumentType.integer(1, 100))
								.executes(QuestCommands::top))
						.executes(QuestCommands::top))
				.then(Commands.literal("stats")
						.then(Commands.argument("player", EntityArgument.player())
								.executes(QuestCommands::statsOf))
						.executes(QuestCommands::statsOf))
				.then(Commands.literal("mine").executes(QuestCommands::mine)));
	}

	// --- editing -------------------------------------------------------------

	private static int newQuest(CommandContext<CommandSourceStack> ctx) {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "quest");
		Quest quest = Quest.create(name);
		data.setStore(data.store().withQuest(quest));
		sync(ctx);

		// The id is printed so later subcommands can address the quest unambiguously,
		// even when two quests share a name.
		success(ctx, "Created quest '" + name + "' (" + quest.id() + ")");

		return 1;
	}

	private static int addTask(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		Optional<Quest> found = findQuest(data.store(), StringArgumentType.getString(ctx, "quest"));

		if (found.isEmpty()) {
			return noSuchQuest(ctx);
		}

		ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
		Identifier item = ctx.getArgument("item", Identifier.class);
		int count = IntegerArgumentType.getInteger(ctx, "count");

		Quest quest = found.get();
		Task task = Task.create(item.toString(), count, player.getUUID());
		data.setStore(data.store().withQuest(quest.withTask(task)));
		sync(ctx);

		success(ctx, "Added " + count + "x " + QuestText.displayName(item.toString())
				+ " for " + player.getGameProfile().name() + " \u00a78(" + task.id() + ")");

		return 1;
	}

	private static int deleteQuest(CommandContext<CommandSourceStack> ctx) {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		Optional<Quest> found = findQuest(data.store(), StringArgumentType.getString(ctx, "quest"));

		if (found.isEmpty()) {
			return noSuchQuest(ctx);
		}

		data.setStore(data.store().withoutQuest(found.get().id()));
		sync(ctx);
		success(ctx, "Deleted quest '" + found.get().name() + "'");

		return 1;
	}

	private static int assign(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		Optional<Quest> found = findQuest(data.store(), StringArgumentType.getString(ctx, "quest"));

		if (found.isEmpty()) {
			return noSuchQuest(ctx);
		}

		Optional<Task> task = findTask(found.get(), StringArgumentType.getString(ctx, "task"));

		if (task.isEmpty()) {
			failure(ctx, "No such task. Tasks are named by UUID prefix — see /questbook list");
			return 0;
		}

		ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
		Quest quest = found.get();

		data.setStore(data.store().updateTask(quest.id(), task.get().id(),
				t -> t.withAssignee(player.getUUID(), player.getGameProfile().name())));
		sync(ctx);
		success(ctx, "Assigned to " + player.getGameProfile().name());

		return 1;
	}

	// --- quest list ----------------------------------------------------------

	/** Lists every quest and what it asks for. */
	private static int tree(CommandContext<CommandSourceStack> ctx) {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		QuestStore store = data.store();

		if (store.quests().isEmpty()) {
			info(ctx, "No quests yet. Use /questbook new <name>");
			return 0;
		}

		info(ctx, "Quests");

		for (Quest quest : store.quests()) {
			MutableComponent msg = Component.empty();
			msg.append(QuestText.treeRow(quest.name(), quest.completedCount(), quest.tasks().size()));
			ctx.getSource().sendSuccess(() -> msg, false);
		}

		return store.quests().size();
	}

	// --- leaderboard ---------------------------------------------------------

	private static int top(CommandContext<CommandSourceStack> ctx) {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		int limit = 10;

		try {
			limit = IntegerArgumentType.getInteger(ctx, "limit");
		} catch (IllegalArgumentException noLimit) {
			// The bare form has no argument; keep the default.
		}

		List<java.util.Map.Entry<UUID, PlayerStats>> ranked = data.rankedStats();

		if (ranked.isEmpty()) {
			info(ctx, "Nobody has completed anything yet");
			return 0;
		}

		info(ctx, "Top " + Math.min(limit, ranked.size()) + " contributors");

		int rank = 1;

		for (java.util.Map.Entry<UUID, PlayerStats> entry : ranked) {
			if (rank > limit) {
				break;
			}

			PlayerStats stats = entry.getValue();
			final int finalRank = rank;
			ctx.getSource().sendSuccess(() -> QuestText.topRow(finalRank,
					playerName(ctx, entry.getKey()), stats.points(), stats.tasks(), stats.quests()), false);
			rank++;
		}

		return ranked.size();
	}

	private static int statsOf(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		ServerPlayer target;

		try {
			target = EntityArgument.getPlayer(ctx, "player");
		} catch (IllegalArgumentException noArgument) {
			target = ctx.getSource().getPlayerOrException();
		}

		PlayerStats stats = data.statsOf(target.getUUID());

		info(ctx, target.getGameProfile().name() + ": " + stats.points() + " points, "
				+ stats.tasks() + " tasks, " + stats.quests() + " quests");

		return 1;
	}

	// --- discord -------------------------------------------------------------

	private static int discordUrl(CommandContext<CommandSourceStack> ctx) {
		String url = StringArgumentType.getString(ctx, "webhook").trim();

		if (!(url.startsWith("https://discord.com/api/webhooks/")
				|| url.startsWith("https://discordapp.com/api/webhooks/")
				|| url.startsWith("https://ptb.discord.com/api/webhooks/")
				|| url.startsWith("https://canary.discord.com/api/webhooks/"))) {
			failure(ctx, "That does not look like a Discord webhook URL");
			return 0;
		}

		DiscordConfig config = DiscordConfig.get();
		config.setWebhookUrl(url);
		config.save();

		success(ctx, "Webhook saved. Test it with /questbook discord test");

		return 1;
	}

	private static int discordOff(CommandContext<CommandSourceStack> ctx) {
		DiscordConfig config = DiscordConfig.get();
		config.setWebhookUrl("");
		config.save();

		success(ctx, "Webhook cleared");

		return 1;
	}

	private static int discordStatus(CommandContext<CommandSourceStack> ctx) {
		info(ctx, "Discord: " + DiscordConfig.get().describe());

		return 1;
	}

	private static int discordTest(CommandContext<CommandSourceStack> ctx) {
		DiscordNotifier.test();

		info(ctx, "Test message sent (async). Check the channel, and the log if it failed.");

		return 1;
	}

	// --- reading -------------------------------------------------------------

	private static int list(CommandContext<CommandSourceStack> ctx) {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		QuestStore store = data.store();

		if (store.quests().isEmpty()) {
			info(ctx, "No quests yet. Use /questbook new <name>");
			return 0;
		}

		for (Quest quest : store.quests()) {
			MutableComponent head = Component.empty();
			head.append(QuestText.treeRow(quest.name(),
					quest.completedCount(), quest.tasks().size()));
			head.append(Component.literal(" \u00a78[" + quest.id() + "]"));
			ctx.getSource().sendSuccess(() -> head, false);

			for (Task task : quest.tasks()) {
				ctx.getSource().sendSuccess(() -> QuestText.sub(QuestText.displayName(task.itemId())
						+ "  " + task.cappedProgress() + "/" + task.count()
						+ "  " + task.id() + "  player=" + task.assignee()), false);
			}
		}

		return store.quests().size();
	}

	private static int mine(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		ServerPlayer player = ctx.getSource().getPlayerOrException();
		List<Task> tasks = data.store().tasksOf(player.getUUID());

		if (tasks.isEmpty()) {
			info(ctx, "You have no assigned tasks");
			return 0;
		}

		for (Task task : tasks) {
			ctx.getSource().sendSuccess(() -> QuestText.sub(QuestText.displayName(task.itemId())
					+ "  " + task.cappedProgress() + "/" + task.count()
					+ (task.isComplete() ? "  (done)" : "")), false);
		}

		return tasks.size();
	}

	// --- helpers -------------------------------------------------------------

	/** A player's name, falling back to a short id when they are offline. */
	private static String playerName(CommandContext<CommandSourceStack> ctx, UUID id) {
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(id);

		if (online != null) {
			return online.getGameProfile().name();
		}

		return id.toString().substring(0, 8);
	}

	/** A quest name in the same gradient as the book, so output matches the UI. */
	private static Component chromaticName(String name) {
		return QuestText.chromatic(name, 0x4FC3F7, 0x81C784);
	}

	/** The saved data for the command's server, or null after reporting the problem. */
	private static QuestSavedData data(CommandContext<CommandSourceStack> ctx) {
		Optional<QuestSavedData> found = Quests.find(ctx.getSource().getServer());

		if (found.isEmpty()) {
			failure(ctx, "Quest data is not loaded yet");
			return null;
		}

		return found.get();
	}

	/** Resolves a quest by name, or by id if the name matches nothing. */
	private static Optional<Quest> findQuest(QuestStore store, String nameOrId) {
		for (Quest quest : store.quests()) {
			if (quest.name().equalsIgnoreCase(nameOrId)) {
				return Optional.of(quest);
			}
		}

		try {
			return store.quest(UUID.fromString(nameOrId));
		} catch (IllegalArgumentException notAUuid) {
			return Optional.empty();
		}
	}

	/** Resolves a task by full id, or by an id prefix long enough to be unique. */
	private static Optional<Task> findTask(Quest quest, String idOrPrefix) {
		return quest.tasks().stream()
				.filter(t -> t.id().toString().startsWith(idOrPrefix))
				.findFirst();
	}

	private static int noSuchQuest(CommandContext<CommandSourceStack> ctx) {
		failure(ctx, "No such quest. Use /questbook list");
		return 0;
	}

	/** Pushes the new quest state to every online client, so an open book updates. */
	private static void sync(CommandContext<CommandSourceStack> ctx) {
		QuestNetworking.syncAll(ctx.getSource().getServer());
	}

	/** A command that carried out. */
	private static int success(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendSuccess(() -> QuestText.success(message), false);
		return 1;
	}

	/**
	 * A command that could not be carried out.
	 *
	 * <p>{@code sendFailure} rather than {@code sendSuccess}: a refusal is an
	 * error, the console sees it, and the command's exit code stays 0.
	 */
	private static int failure(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendFailure(QuestText.failure(message));
		return 0;
	}

	/** Command output that is neither good nor bad news. */
	private static int info(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendSuccess(() -> QuestText.info(message), false);
		return 1;
	}
}
