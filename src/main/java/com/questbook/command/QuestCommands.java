package com.questbook.command;

import com.questbook.data.Quest;
import com.questbook.data.PlayerStats;
import com.questbook.discord.DiscordConfig;
import com.questbook.discord.DiscordNotifier;
import com.questbook.network.AdminActionHandler;
import com.questbook.network.QuestNetworking;
import com.questbook.data.QuestStore;
import com.questbook.data.Task;
import com.questbook.storage.QuestSavedData;
import com.questbook.tracking.Quests;
import com.questbook.util.QuestText;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Registers {@code /questbook} and its subcommands.
 *
 * <p>Editing requires OP level 2, because a reward may be a server command and is
 * therefore a privilege-escalation surface. Reading commands need no permission.
 */
public final class QuestCommands {
	private QuestCommands() {
	}

	/**
	 * The {@code quest} argument, with suggestions.
	 *
	 * <p>A shared helper because four branches take a quest and all four should suggest
	 * the same thing. As a plain string Brigadier suggests nothing on its own, so
	 * without this the operator has to run {@code /questbook list} first and copy an
	 * id by hand.
	 */
	private static RequiredArgumentBuilder<CommandSourceStack, String> questArg(String name) {
		return Commands.argument(name, StringArgumentType.string())
				.suggests(QuestCommands::suggestQuests);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("questbook")
				.then(Commands.literal("new")
						.requires(QuestCommands::canEdit)
						.then(Commands.argument("quest", StringArgumentType.greedyString())
								.executes(QuestCommands::newQuest)))
				.then(Commands.literal("add")
						.requires(QuestCommands::canEdit)
						.then(questArg("quest")
								.then(Commands.argument("player", EntityArgument.player())
										.then(Commands.argument("item", IdentifierArgument.id())
												.then(Commands.argument("count", IntegerArgumentType.integer(1))
														.executes(QuestCommands::addTask))))))
				.then(Commands.literal("delete")
						.requires(QuestCommands::canEdit)
						.then(questArg("quest")
								.executes(QuestCommands::deleteQuest)))
				.then(Commands.literal("assign")
						.requires(QuestCommands::canEdit)
						.then(questArg("quest")
								.then(Commands.argument("task", StringArgumentType.string())
										.suggests(QuestCommands::suggestTasks)
										.then(Commands.argument("player", EntityArgument.player())
												.executes(QuestCommands::assign)))))
				// Editor management stays OP-only. An editor who could grant editor
				// access would be able to widen their own rank, which defeats the point
				// of not handing out OP.
				.then(Commands.literal("editor")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("add")
								.then(Commands.argument("player", EntityArgument.player())
										// Suggestions filtered rather than the argument
										// type swapped: EntityArgument gives name
										// completion for free, and only the list of
										// candidates needs to change.
										.suggests(QuestCommands::suggestNonOperator)
										.executes(QuestCommands::editorAdd)))
						.then(Commands.literal("remove")
								.then(Commands.argument("player", EntityArgument.player())
										.executes(QuestCommands::editorRemove))
								// Revoking by id as well as by name, because a name needs
								// the player online and the case that matters is the one
								// where you cannot get them online — or where the grant
								// outlived the last operator who could undo it.
								.then(Commands.literal("id")
										.then(Commands.argument("uuid", StringArgumentType.string())
												.suggests(QuestCommands::suggestEditorIds)
												.executes(QuestCommands::editorRemoveById))))
						.then(Commands.literal("list").executes(QuestCommands::editorList)))
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

	/**
	 * Suggests quest names, or ids when a name is ambiguous.
	 *
	 * <p>Quoted because a quest name is free text and may contain spaces; an unquoted
	 * suggestion for "Build a Cottage" would be re-parsed as three arguments.
	 */
	private static CompletableFuture<Suggestions> suggestQuests(CommandContext<CommandSourceStack> ctx,
			SuggestionsBuilder builder) {
		Optional<QuestSavedData> found = Quests.find(ctx.getSource().getServer());

		if (found.isEmpty()) {
			return builder.buildFuture();
		}

		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

		for (Quest quest : found.get().store().quests()) {
			if (!quest.name().toLowerCase(Locale.ROOT).startsWith(remaining)) {
				continue;
			}

			builder.suggest(StringArgumentType.escapeIfRequired(quest.name()));
		}

		return builder.buildFuture();
	}

	/**
	 * Suggests task names for the quest already typed.
	 *
	 * <p>The name, not the id. A task has no name field — it is derived from the item
	 * like everywhere else — but an operator recognises "Oak log" and does not
	 * recognise a UUID, and the id stays available for the one case a name cannot
	 * cover: two tasks in the same quest asking for the same item.
	 *
	 * <p>Each suggestion carries the requirement as a tooltip, which is what
	 * distinguishes two same-named tasks before you pick one.
	 */
	private static CompletableFuture<Suggestions> suggestTasks(CommandContext<CommandSourceStack> ctx,
			SuggestionsBuilder builder) {
		Optional<QuestSavedData> found = Quests.find(ctx.getSource().getServer());

		if (found.isEmpty()) {
			return builder.buildFuture();
		}

		String questArg;

		try {
			questArg = StringArgumentType.getString(ctx, "quest");
		} catch (IllegalArgumentException notYetTyped) {
			return builder.buildFuture();
		}

		Optional<Quest> quest = findQuest(found.get().store(), questArg);

		if (quest.isEmpty()) {
			return builder.buildFuture();
		}

		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

		for (Task task : quest.get().tasks()) {
			String name = QuestText.displayName(task.itemId());

			if (!name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				continue;
			}

			// A duplicate name would give two identical entries the player cannot tell
			// apart, so the id is added for those, which is also what makes the command
			// runnable for them.
			boolean duplicate = quest.get().tasks().stream()
					.filter(other -> !other.id().equals(task.id()))
					.anyMatch(other -> QuestText.displayName(other.itemId()).equalsIgnoreCase(name));

			builder.suggest(StringArgumentType.escapeIfRequired(
					duplicate ? name + " " + task.id().toString().substring(0, 8) : name), tooltip(task));
		}

		return builder.buildFuture();
	}

	/** What a task suggestion tells you about itself, so names are not blind guesses. */
	private static Message tooltip(Task task) {
		return Component.literal("needs " + task.count() + "  ("
				+ task.cappedProgress() + " collected)");
	}

	/**
	 * Command gate for everything that edits quests: OP level 2, or an editor grant.
	 *
	 * <p>Opting out of {@code Commands.hasPermission} rather than widening it, so the
	 * grant stays scoped to quest editing and does not leak into any other command that
	 * happens to share the level.
	 *
	 * <p>Denies rather than erroring when the data is not loaded, so an unloaded world
	 * cannot be edited into.
	 */
	private static boolean canEdit(CommandSourceStack source) {
		Optional<QuestSavedData> found = Quests.find(source.getServer());

		if (found.isEmpty()) {
			return false;
		}

		ServerPlayer player = source.getPlayer();

		if (player == null) {
			// Console and command blocks have no UUID to grant, so they keep the plain
			// permission test and are never treated as editors.
			return Commands.LEVEL_GAMEMASTERS.check(source.permissions());
		}

		return AdminActionHandler.mayEdit(player, found.get());
	}

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
		success(ctx, "Created " + name + "  " + shortId(quest.id()));

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
				+ " for " + player.getGameProfile().name() + "  " + shortId(task.id()));

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
		success(ctx, "Deleted " + found.get().name());

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

		TaskLookup lookup = findTask(found.get(), StringArgumentType.getString(ctx, "task"));

		if (!lookup.found()) {
			if (lookup.miss() == TaskMiss.AMBIGUOUS) {
				failure(ctx, "That name matches several tasks ("
						+ duplicateNames(found.get()) + "). Use the exact id.");
			} else {
				failure(ctx, "No such task. Use /questbook list");
			}

			return 0;
		}

		Task task = lookup.task();

		ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
		Quest quest = found.get();

		data.setStore(data.store().updateTask(quest.id(), task.id(),
				t -> t.withAssignee(player.getUUID(), player.getGameProfile().name())));
		sync(ctx);
		success(ctx, "Assigned to " + player.getGameProfile().name());
		return 1;
	}

	// --- editor access -------------------------------------------------------

	/**
	 * Grants editor access. Writes to saved data, not to any player attribute, so the
	 * grant survives a re-login and needs no re-application at join time.
	 */
	private static int editorAdd(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		String name = target.getGameProfile().name();

		if (data.isEditor(target.getUUID())) {
			info(ctx, name + " is already an editor");
			return 0;
		}

		// Checked here as well as in the suggestion filter: the filter only shapes what
		// the menu offers, and this is the one write that must not happen. A grant to an
		// operator is redundant now and dangerous later — it survives /deop, so taking
		// away their OP level would leave them an editor by saved-data grant alone.
		if (Commands.LEVEL_GAMEMASTERS.check(target.permissions())) {
			failure(ctx, name + " already has access through OP level 2+ — no grant needed");
			return 0;
		}

		data.addEditor(target.getUUID());
		success(ctx, name + " can edit quests  " + shortId(target.getUUID()));

		// Tell them. A grant that arrives silently leaves the player unaware they can
		// now open the editor, which is the whole point of granting it.
		target.sendSystemMessage(QuestText.editorGranted());
		AdminActionHandler.syncAllAdmins(ctx.getSource().getServer(), data);

		return 1;
	}

	private static int editorRemove(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		String name = target.getGameProfile().name();

		if (!data.isEditor(target.getUUID())) {
			// An OP is not on the grant list but does hold access, so "not an editor"
			// would be wrong and would send the operator looking for a bug. Point at
			// the thing that actually grants them instead.
			if (Commands.LEVEL_GAMEMASTERS.check(target.permissions())) {
				info(ctx, name + " has no grant — their access comes from OP level 2. "
						+ "Use /deop " + name + " to take it away.");
				return 0;
			}

			info(ctx, name + " is not an editor");
			return 0;
		}

		data.removeEditor(target.getUUID());
		success(ctx, name + " can no longer edit quests");

		target.sendSystemMessage(QuestText.editorRevoked());
		AdminActionHandler.syncAllAdmins(ctx.getSource().getServer(), data);

		return 1;
	}

	/**
	 * Revokes by UUID, for an editor who is offline.
	 *
	 * <p>Exists because {@link EntityArgument} can only resolve a player who is
	 * connected: without this, a grant made by an operator who has since been de-opped
	 * could not be undone at all until that player happened to log in.
	 */
	private static int editorRemoveById(CommandContext<CommandSourceStack> ctx) {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		String raw = StringArgumentType.getString(ctx, "uuid");

		Optional<UUID> parsed = resolveEditorId(data, raw);

		if (parsed.isEmpty()) {
			failure(ctx, "Not a player id. Use /questbook editor list");
			return 0;
		}

		UUID id = parsed.get();

		if (!data.isEditor(id)) {
			info(ctx, shortId(id) + " is not an editor");
			return 0;
		}

		data.removeEditor(id);

		String name = playerName(ctx, id);
		success(ctx, name + " can no longer edit quests");

		// Only tell them if they are here to be told.
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(id);

		if (online != null) {
			online.sendSystemMessage(QuestText.editorRevoked());
		}

		AdminActionHandler.syncAllAdmins(ctx.getSource().getServer(), data);

		return 1;
	}

	/**
	 * Resolves a full UUID or the 8-char prefix that {@link #editorList} prints.
	 *
	 * <p>The prefix is what the list actually shows, so accepting it is the difference
	 * between copying an id straight out of the output and having to look the full UUID
	 * up somewhere else. Only grantees are matched, which is the set being managed and
	 * keeps a short prefix from resolving to an unrelated player.
	 */
	private static Optional<UUID> resolveEditorId(QuestSavedData data, String raw) {
		String trimmed = raw.trim();

		if (trimmed.isEmpty()) {
			return Optional.empty();
		}

		// A full id is unambiguous, so it wins even if it is not a current grantee —
		// the caller reports "not an editor" rather than "not a player id".
		try {
			return Optional.of(UUID.fromString(trimmed));
		} catch (IllegalArgumentException notAFullUuid) {
			// fall through
		}

		if (!isHexPrefix(trimmed)) {
			return Optional.empty();
		}

		String lower = trimmed.toLowerCase(Locale.ROOT);

		return data.editors().stream()
				.filter(id -> id.toString().toLowerCase(Locale.ROOT).startsWith(lower))
				.findFirst();
	}

	/**
	 * Suggests players who do not already have access through OP level.
	 *
	 * <p>Excluded rather than merely deprecated, because a grant to an operator is a
	 * trap: it looks like a no-op, but it lives in saved data independently of their
	 * OP, so if that OP level is later taken away the player keeps editor access they
	 * were never supposed to hold on their own.
	 *
	 * <p>Vanilla's {@code /deop} makes this exact mistake hard to undo — once the grant
	 * exists the only route back is the operator remembering it is there.
	 */
	private static CompletableFuture<Suggestions> suggestNonOperator(CommandContext<CommandSourceStack> ctx,
			SuggestionsBuilder builder) {
		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

		for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
			if (Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
				continue;
			}

			String name = player.getGameProfile().name();

			if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				builder.suggest(name);
			}
		}

		return builder.buildFuture();
	}

	/** Suggests editor ids, so revoking an offline editor needs no guesswork. */
	private static CompletableFuture<Suggestions> suggestEditorIds(CommandContext<CommandSourceStack> ctx,
			SuggestionsBuilder builder) {
		Optional<QuestSavedData> found = Quests.find(ctx.getSource().getServer());

		if (found.isEmpty()) {
			return builder.buildFuture();
		}

		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

		for (UUID id : found.get().editors()) {
			String full = id.toString();

			if (full.startsWith(remaining)) {
				String label = playerName(ctx, id);
				builder.suggest(full, Component.literal(label + "  (full id)"));
			}
		}

		return builder.buildFuture();
	}

	/**
	 * Lists everyone who can edit, and by what.
	 *
	 * <p>Deliberately not just the grantee list. OP level 2 passes the same gate without
	 * a grant, so showing only grants would hide real access — an operator looking at
	 * this to decide who to revoke would conclude that an OP has none and that removing
	 * is therefore unnecessary.
	 *
	 * <p>Names are resolved live, so an editor who is offline shows their stored id
	 * prefix rather than nothing — the grant is by UUID and does not expire when they
	 * log out.
	 */
	private static int editorList(CommandContext<CommandSourceStack> ctx) {
		QuestSavedData data = data(ctx);

		if (data == null) {
			return 0;
		}

		List<ServerPlayer> ops = ctx.getSource().getServer().getPlayerList().getPlayers().stream()
				.filter(p -> Commands.LEVEL_GAMEMASTERS.check(p.permissions()))
				.toList();

		if (data.editors().isEmpty() && ops.isEmpty()) {
			info(ctx, "No editors");
			return 0;
		}

		if (!ops.isEmpty()) {
			info(ctx, "Editors (operator — access granted by OP level, cannot be removed here)");

			for (ServerPlayer op : ops) {
				ctx.getSource().sendSuccess(() -> QuestText.sub(
						op.getGameProfile().name() + "  OP level 2+"), false);
			}
		}

		if (!data.editors().isEmpty()) {
			info(ctx, "Editors (granted)");

			for (UUID id : data.editors()) {
				ctx.getSource().sendSuccess(() -> QuestText.sub(playerName(ctx, id)
						+ "  " + shortId(id)), false);
			}
		}

		return data.editors().size() + ops.size();
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
			head.append(Component.literal("  " + shortId(quest.id())));
			ctx.getSource().sendSuccess(() -> head, false);

			for (Task task : quest.tasks()) {
				// Short id, not the full UUID: this line is read, not copied whole, and
				// findTask matches the printed prefix.
				ctx.getSource().sendSuccess(() -> QuestText.sub(QuestText.displayName(task.itemId())
						+ " " + task.cappedProgress() + "/" + task.count()
						+ "  " + task.id().toString().substring(0, 8)
						+ "  " + task.assigneeName()), false);
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

	/**
	 * First segment of a UUID, for command output.
	 *
	 * <p>A full UUID is 36 characters and is what made these lines unreadable in chat.
	 * Eight hex digits is what {@link #findTask} already matches on as a prefix, so a
	 * printed id stays copy-pasteable into the next command.
	 */
	private static String shortId(UUID id) {
		return "\u00a78" + id.toString().substring(0, 8);
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

	/**
	 * Finds a task by id, id prefix, name, or the {@code "name <idprefix>"} form.
	 *
	 * <p>Several spellings because the operator can arrive here two ways: typing a
	 * suggestion, which gives a name, or copying an id out of {@code /questbook list}.
	 * Both must keep working.
	 *
	 * <p>Returns the reason for a miss rather than an empty Optional so the caller can
	 * distinguish "no such task" from "that name is ambiguous" — an ambiguity means the
	 * player has to disambiguate, which is a different instruction than a typo.
	 */
	private static TaskLookup findTask(Quest quest, String idOrPrefix) {
		String wanted = idOrPrefix.trim();

		// "Oak log 1a2b3c4d" — the disambiguated form produced for duplicate names.
		int lastSpace = wanted.lastIndexOf(' ');

		if (lastSpace > 0) {
			String prefix = wanted.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
			String name = wanted.substring(0, lastSpace);

			if (isHexPrefix(prefix)) {
				Optional<Task> named = quest.tasks().stream()
						.filter(t -> QuestText.displayName(t.itemId()).equalsIgnoreCase(name))
						.filter(t -> t.id().toString().toLowerCase(Locale.ROOT).startsWith(prefix))
						.findFirst();

				if (named.isPresent()) {
					return new TaskLookup(named.get(), null);
				}
			}
		}

		// Id or id prefix, checked first so a UUID can never be shadowed by a name.
		Optional<Task> byId = quest.tasks().stream()
				.filter(t -> t.id().toString().toLowerCase(Locale.ROOT)
						.startsWith(wanted.toLowerCase(Locale.ROOT)))
				.findFirst();

		if (byId.isPresent()) {
			return new TaskLookup(byId.get(), null);
		}

		List<Task> byName = quest.tasks().stream()
				.filter(t -> QuestText.displayName(t.itemId()).equalsIgnoreCase(wanted))
				.toList();

		if (byName.size() == 1) {
			return new TaskLookup(byName.getFirst(), null);
		}

		if (byName.size() > 1) {
			return new TaskLookup(null, TaskMiss.AMBIGUOUS);
		}

		return new TaskLookup(null, TaskMiss.NOT_FOUND);
	}

	/** Whether every character is a hex digit, i.e. it could be an id prefix. */
	private static boolean isHexPrefix(String s) {
		if (s.isEmpty()) {
			return false;
		}

		for (int i = 0; i < s.length(); i++) {
			if (Character.digit(s.charAt(i), 16) < 0) {
				return false;
			}
		}

		return true;
	}

	/** Why a task lookup failed, when it did. */
	private enum TaskMiss {
		NOT_FOUND,
		AMBIGUOUS
	}

	/** A found task, or the reason there isn't one. */
	private record TaskLookup(Task task, TaskMiss miss) {
		boolean found() {
			return task != null;
		}
	}

	/** Names of the tasks in a quest that share a name, for the ambiguity message. */
	private static String duplicateNames(Quest quest) {
		Map<String, Long> counts = new LinkedHashMap<>();

		for (Task task : quest.tasks()) {
			String name = QuestText.displayName(task.itemId());
			counts.merge(name.toLowerCase(Locale.ROOT), 1L, Long::sum);
		}

		return counts.entrySet().stream()
				.filter(e -> e.getValue() > 1)
				.map(Map.Entry::getKey)
				.reduce((a, b) -> a + ", " + b)
				.orElse("?");
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
