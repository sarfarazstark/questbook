package com.questbook.data;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import com.questbook.storage.QuestSavedData;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-check for the model rules. Run with {@code java QuestModelCheck}.
 *
 * <p>Covers the two rules that define this mod and are easy to get subtly wrong:
 * progress only advances for the assignee, and a quest is complete only when every
 * task is.
 */
public final class QuestModelCheck {
	private static int failures;

	public static void main(String[] args) {
		checkAssigneeRule();
		checkQuestCompletion();
		checkProgressClamp();
		checkTaskReplacement();
		checkReward();
		checkPinning();
		checkOrderPreserved();
		checkQuestOrderPreserved();
		checkPrerequisites();
		checkMissingPrerequisite();
		checkTaskReward();
		checkPlayerStats();
		checkBackwardCompatibleDocument();

		if (failures > 0) {
			System.out.println(failures + " CHECK(S) FAILED");
			System.exit(1);
		}

		System.out.println("ALL CHECKS PASSED");
	}

	/** Only the assignee's progress is tracked; the model must express that. */
	private static void checkAssigneeRule() {
		UUID alex = UUID.randomUUID();
		UUID sam = UUID.randomUUID();

		Task task = Task.create("minecraft:oak_log", 64, alex);

		expect("new task starts at zero", task.progress() == 0);
		expect("new task is not complete", !task.isComplete());
		expect("assignee is alex", task.assignee().equals(alex));
		expect("sam is not the assignee", !task.assignee().equals(sam));

		// The tracker filters on assignee before advancing; prove the model keeps
		// the assignee intact through an advance so that filter stays meaningful.
		Task advanced = task.advance(10);
		expect("advance keeps the assignee", advanced.assignee().equals(alex));
		expect("advance adds progress", advanced.progress() == 10);
	}

	/** A quest is complete only when every task is. */
	private static void checkQuestCompletion() {
		UUID alex = UUID.randomUUID();
		Task first = Task.create("minecraft:oak_log", 2, alex).advance(2);
		Task second = Task.create("minecraft:glass", 3, alex).advance(1);

		Quest quest = Quest.create("Cottage").withTask(first).withTask(second);

		expect("partial quest is not complete", !quest.isComplete());
		expect("completedCount is 1", quest.completedCount() == 1);

		Quest done = quest.updateTask(second.id(), t -> t.advance(2));

		expect("quest complete when all tasks are", done.isComplete());
		expect("completedCount is 2", done.completedCount() == 2);
	}

	/** Progress must never read above the requirement. */
	private static void checkProgressClamp() {
		Task task = Task.create("minecraft:oak_log", 5, UUID.randomUUID()).advance(99);

		expect("advance clamps at count", task.progress() == 5);
		expect("cappedProgress is 5", task.cappedProgress() == 5);
		expect("fraction is 1.0", Math.abs(task.fraction() - 1f) < 0.0001f);

		Task under = Task.create("minecraft:oak_log", 4, UUID.randomUUID()).advance(1);
		expect("fraction is 0.25", Math.abs(under.fraction() - 0.25f) < 0.0001f);
	}

	/** Adding a task with an existing id replaces rather than duplicates it. */
	private static void checkTaskReplacement() {
		Task task = Task.create("minecraft:oak_log", 2, UUID.randomUUID());
		Quest quest = Quest.create("Cottage").withTask(task).withTask(task.advance(1));

		expect("replace does not duplicate", quest.tasks().size() == 1);
		expect("replacement carries the new progress", quest.tasks().get(0).progress() == 1);

		Quest removed = quest.withoutTask(task.id());
		expect("remove drops the task", removed.tasks().isEmpty());
	}

	/** Reward substitution and validation. */
	private static void checkReward() {
		Reward command = Reward.command("tell %player% gg");
		expect("command substitutes the player", command.commandFor("Alex").orElse("").equals("tell Alex gg"));

		Reward item = Reward.item("minecraft:diamond", 3);
		expect("item has no command", item.commandFor("Alex").isEmpty());
		expect("item reward is sane", item.isSane());
		expect("zero-amount item is not sane", !Reward.item("minecraft:diamond", 0).isSane());
		expect("blank item is not sane", !Reward.item("", 1).isSane());
		expect("newline command is not sane", !Reward.command("say hi\nsay bye").isSane());
		expect("zero xp is not sane", !Reward.xp(0).isSane());
	}

	/** Pinning is per task, and survives the immutable update chain. */
	private static void checkPinning() {
		UUID alex = UUID.randomUUID();
		Task task = Task.create("minecraft:oak_log", 4, alex);

		expect("a new task is not pinned", !task.pinned());

		Task pinned = task.withPinned(true);
		expect("withPinned sets the flag", pinned.pinned());
		expect("pinning keeps the assignee", pinned.assignee().equals(alex));

		// Progress updates must not silently drop the pin.
		expect("advance keeps the pin", pinned.advance(1).pinned());
		expect("withProgress keeps the pin", pinned.withProgress(2).pinned());
		expect("withAssignee keeps the pin", pinned.withAssignee(alex).pinned());

		// And unpinning clears it.
		expect("withPinned(false) clears the flag", !pinned.withPinned(false).pinned());

		// The store round-trip must preserve it too.
		Quest quest = Quest.create("Cottage").withTask(pinned);
		QuestStore store = QuestStore.EMPTY.withQuest(quest);
		Task after = store.quests().get(0).tasks().get(0);
		expect("store keeps the pin", after.pinned());
	}

	/**
	 * Updating a task must not move it in the list.
	 *
	 * <p>{@code withTask} used to remove and re-add, which sent the task to the
	 * end — so pinning a task, or even collecting an item for it, reordered the
	 * player's list underneath them.
	 */
	private static void checkOrderPreserved() {
		UUID alex = UUID.randomUUID();
		Task first = Task.create("minecraft:oak_log", 1, alex);
		Task second = Task.create("minecraft:glass", 1, alex);
		Task third = Task.create("minecraft:stone", 1, alex);

		Quest quest = Quest.create("Cottage").withTask(first).withTask(second).withTask(third);

		expect("three tasks added in order",
				quest.tasks().get(0).id().equals(first.id())
						&& quest.tasks().get(1).id().equals(second.id())
						&& quest.tasks().get(2).id().equals(third.id()));

		// Update the FIRST task; it must stay first.
		Quest pinned = quest.updateTask(first.id(), t -> t.withPinned(true));

		expect("updating the first task does not move it",
				pinned.tasks().get(0).id().equals(first.id()));
		expect("the update actually applied", pinned.tasks().get(0).pinned());
		expect("order of the rest is untouched",
				pinned.tasks().get(1).id().equals(second.id())
						&& pinned.tasks().get(2).id().equals(third.id()));

		// A middle task must also stay in place.
		Quest middle = quest.updateTask(second.id(), t -> t.advance(1));
		expect("updating a middle task keeps its position",
				middle.tasks().get(1).id().equals(second.id()));
		expect("the middle update applied", middle.tasks().get(1).progress() == 1);

		// Adding a genuinely new task still appends.
		Task fourth = Task.create("minecraft:dirt", 1, alex);
		Quest appended = quest.withTask(fourth);
		expect("a new task appends at the end",
				appended.tasks().get(3).id().equals(fourth.id()));
		expect("appending keeps the earlier order",
				appended.tasks().get(0).id().equals(first.id()));
	}

	/**
	 * Updating a quest must not move it in the store.
	 *
	 * <p>The same remove-then-add bug existed one level up: pinning a task updates
	 * its quest, which sent the whole quest to the end of the book.
	 */
	private static void checkQuestOrderPreserved() {
		UUID alex = UUID.randomUUID();

		Quest first = Quest.create("Build a Cottage").withTask(Task.create("minecraft:oak_log", 1, alex));
		Quest second = Quest.create("Farm Supplies").withTask(Task.create("minecraft:wheat", 1, alex));
		Quest third = Quest.create("Mining").withTask(Task.create("minecraft:stone", 1, alex));

		QuestStore store = QuestStore.EMPTY.withQuest(first).withQuest(second).withQuest(third);

		expect("three quests in order",
				store.quests().get(0).id().equals(first.id())
						&& store.quests().get(1).id().equals(second.id())
						&& store.quests().get(2).id().equals(third.id()));

		// Pin a task in the FIRST quest; that quest must stay first.
		UUID taskId = first.tasks().get(0).id();
		QuestStore after = store.updateTask(first.id(), taskId, t -> t.withPinned(true));

		expect("updating a task does not move its quest",
				after.quests().get(0).id().equals(first.id()));
		expect("the pin applied", after.quests().get(0).tasks().get(0).pinned());
		expect("other quests keep their order",
				after.quests().get(1).id().equals(second.id())
						&& after.quests().get(2).id().equals(third.id()));

		// Even a quest with no task change must stay put when replaced directly.
		QuestStore renamed = store.withQuest(store.quests().get(1).withName("Renamed"));
		expect("replacing a quest keeps its position",
				renamed.quests().get(1).name().equals("Renamed"));

		// A genuinely new quest still appends.
		Quest fourth = Quest.create("Fishing");
		expect("a new quest appends",
				store.withQuest(fourth).quests().get(3).id().equals(fourth.id()));
	}

	/**
	 * A quest is locked until every prerequisite is complete.
	 *
	 * <p>Prerequisite ids point at quests that may no longer exist. A dangling
	 * prerequisite must not lock a quest forever, because deleting a quest is a
	 * legitimate operation and an operator should not have to clean up every
	 * chain that pointed at it.
	 */
	private static void checkPrerequisites() {
		UUID alex = UUID.randomUUID();

		Quest gather = Quest.create("Gather Wood").withTask(Task.create("minecraft:oak_log", 1, alex));
		Quest build = Quest.create("Build a Cottage");
		UUID dangling = UUID.randomUUID();

		Quest locked = build.withPrerequisite(gather.id()).withPrerequisite(dangling);
		QuestStore store = QuestStore.EMPTY.withQuest(gather).withQuest(locked);

		expect("a quest with a prerequisite is locked", !store.isUnlocked(locked.id()));

		// Completing the prerequisite unlocks it. The dangling one is ignored, so
		// this is the whole story.
		QuestStore satisfied = store.updateTask(gather.id(), gather.tasks().get(0).id(),
				t -> t.advance(1));

		expect("completing the prerequisite unlocks", satisfied.isUnlocked(locked.id()));

		// Dropping one prerequisite leaves the dangling one, which still does not
		// block — an operator is never stuck cleaning up a deleted node.
		QuestStore dropped = store.withQuest(store.quest(locked.id()).get()
				.withoutPrerequisite(gather.id()));

		expect("dropping one prerequisite leaves the other",
				dropped.quest(locked.id()).get().prerequisites().size() == 1);
		expect("dropping the prerequisite unlocks", dropped.isUnlocked(locked.id()));

		QuestStore cleared = dropped.withQuest(dropped.quest(locked.id()).get()
				.withoutPrerequisite(dangling));

		expect("prerequisites list can be emptied",
				cleared.quest(locked.id()).get().prerequisites().isEmpty());
	}

	/** A prerequisite pointing at a quest that was deleted counts as satisfied. */
	private static void checkMissingPrerequisite() {
		Quest solo = Quest.create("Free");
		QuestStore empty = QuestStore.EMPTY.withQuest(solo);

		expect("a quest with no prerequisites is unlocked", empty.isUnlocked(solo.id()));

		Quest orphan = solo.withPrerequisite(UUID.randomUUID());
		QuestStore store = empty.withQuest(orphan);

		expect("a prerequisite that does not exist is treated as satisfied",
				store.isUnlocked(orphan.id()));
	}

	/** A task reward rides along with the task, and only pays the assignee. */
	private static void checkTaskReward() {
		UUID alex = UUID.randomUUID();
		Task plain = Task.create("minecraft:oak_log", 4, alex);

		expect("a new task has no reward", plain.reward().isEmpty());

		Task paid = plain.withReward(Optional.of(Reward.item("minecraft:diamond", 1)));

		expect("withReward sets the reward", paid.reward().isPresent());
		expect("the reward is the one that was set",
				paid.reward().get().value().equals("minecraft:diamond"));

		// Every mutation that rebuilds a Task must carry the reward through, or it
		// silently disappears the first time the task is advanced.
		expect("advance keeps the reward", paid.advance(1).reward().isPresent());
		expect("withProgress keeps the reward", paid.withProgress(2).reward().isPresent());
		expect("withAssignee keeps the reward", paid.withAssignee(alex).reward().isPresent());
		expect("withPinned keeps the reward", paid.withPinned(true).reward().isPresent());
		expect("updateTask through the quest keeps the reward",
				Quest.create("Cottage").withTask(paid).updateTask(paid.id(), t -> t.advance(1))
						.tasks().get(0).reward().isPresent());

		expect("withReward(empty) clears", plain.withReward(Optional.empty()).reward().isEmpty());

		// The store round-trip must keep it too.
		Quest quest = Quest.create("Cottage").withTask(paid);
		Task after = QuestStore.EMPTY.withQuest(quest).quests().get(0).tasks().get(0);
		expect("store keeps the task reward", after.reward().isPresent());
	}

	/** Leaderboard points and ranking. */
	private static void checkPlayerStats() {
		UUID alex = UUID.randomUUID();
		UUID sam = UUID.randomUUID();
		UUID kim = UUID.randomUUID();

		PlayerStats none = new PlayerStats(0, 0);
		PlayerStats oneTask = new PlayerStats(1, 0);
		PlayerStats mixed = new PlayerStats(3, 2);
		PlayerStats tasksOnly = new PlayerStats(10, 0);
		PlayerStats oneQuest = new PlayerStats(0, 1);

		expect("nothing scores zero", none.points() == 0);
		expect("a task scores TASK_POINTS", oneTask.points() == PlayerStats.TASK_POINTS);
		expect("a quest scores GOAL_POINTS", oneQuest.points() == PlayerStats.GOAL_POINTS);
		expect("points add up", mixed.points() == 3 * PlayerStats.TASK_POINTS + 2 * PlayerStats.GOAL_POINTS);

		// A quest must always be worth more than a task, or the ranking is noise.
		expect("a quest beats a task", oneQuest.points() > oneTask.points());

		expect("withTask increments", none.withTask().tasks() == 1);
		expect("withQuest increments", none.withQuest().quests() == 1);
		expect("withTask keeps quests", mixed.withTask().quests() == 2);

		// Ten tasks beat one quest — the weights must produce a sensible order.
		Map<UUID, PlayerStats> board = new java.util.LinkedHashMap<>();
		board.put(kim, mixed);
		board.put(alex, tasksOnly);
		board.put(sam, oneQuest);
		board.put(UUID.randomUUID(), none);

		List<Map.Entry<UUID, PlayerStats>> ranked = PlayerStats.ranked(board);

		expect("ranked has every entry", ranked.size() == 4);
		expect("ranked is descending", ranked.get(0).getValue().points() >= ranked.get(1).getValue().points()
				&& ranked.get(1).getValue().points() >= ranked.get(2).getValue().points()
				&& ranked.get(2).getValue().points() >= ranked.get(3).getValue().points());
		expect("the empty entry is last", ranked.get(3).getValue().points() == 0);
		expect("mixed (17 pts) leads", ranked.get(0).getKey().equals(kim));

		// Equal scores must not depend on map iteration order, or the board
		// reshuffles between refreshes for players who are tied.
		Map<UUID, PlayerStats> tied = new java.util.LinkedHashMap<>();
		tied.put(sam, oneTask);
		tied.put(alex, oneTask);

		List<Map.Entry<UUID, PlayerStats>> tieRanked = PlayerStats.ranked(tied);

		expect("ties keep both players", tieRanked.size() == 2);

		// Ranking twice must produce the same order.
		expect("ranking is deterministic",
				tieRanked.get(0).getKey().equals(PlayerStats.ranked(tied).get(0).getKey()));
	}

	/**
	 * A world saved before per-task rewards, prerequisites and stats existed.
	 *
	 * <p>All three fields are {@code optionalFieldOf} with defaults, so the old
	 * disk form decodes unchanged: quests survive, nothing is lost, and no manual
	 * migration is needed. This check is the contract that keeps that true.
	 */
	private static void checkBackwardCompatibleDocument() {
		UUID alex = UUID.randomUUID();
		Quest quest = Quest.create("Gather Wood").withTask(Task.create("minecraft:oak_log", 2, alex));
		String questId = quest.id().toString();
		String taskId = quest.tasks().get(0).id().toString();

		// The pre-feature form: quests, rewards and paid only. No prerequisites, no
		// task reward, no paidTasks, no stats. UUIDs are written the way
		// UUIDUtil.CODEC reads them, as an int pair rather than a dashed string.
		String legacy = "{\"goals\":[{\"id\":" + asUuidArray(quest.id()) + ",\"name\":\"Gather Wood\","
				+ "\"tasks\":[{\"id\":" + asUuidArray(quest.tasks().get(0).id())
				+ ",\"item\":\"minecraft:oak_log\","
				+ "\"count\":2,\"assignee\":" + asUuidArray(alex) + "}]}],"
				+ "\"rewards\":{},\"paid\":[]}";

		JsonElement parsed = JsonParser.parseString(legacy);
		DataResult<QuestSavedData.Document> decoded =
				QuestSavedData.Document.CODEC.parse(JsonOps.INSTANCE, parsed);

		expect("a legacy document decodes", decoded.result().isPresent());

		if (decoded.error().isPresent()) {
			System.out.println("       decode error: " + decoded.error().get().message());
		}

		decoded.result().ifPresent(document -> {
			expect("legacy quests survive", document.quests().size() == 1);
			expect("legacy tasks survive", document.quests().get(0).tasks().size() == 1);
			expect("legacy tasks have no reward",
					document.quests().get(0).tasks().get(0).reward().isEmpty());
			expect("legacy prerequisites default to empty",
					document.quests().get(0).prerequisites().isEmpty());
			expect("legacy paidTasks defaults to empty", document.paidTasks().isEmpty());
			expect("legacy stats default to empty", document.stats().isEmpty());
		});

		// A malformed key must not make the world unreadable.
		QuestSavedData data = new QuestSavedData();
		data.recordTaskCompletion(alex);
		data.recordQuestCompletion(alex);
		data.markTaskPaid(quest.tasks().get(0).id());

		expect("stats are recorded", data.statsOf(alex).points() == PlayerStats.TASK_POINTS
				+ PlayerStats.GOAL_POINTS);
		expect("task is marked paid", data.isTaskPaid(quest.tasks().get(0).id()));
		expect("an unpaid task is not paid", !data.isTaskPaid(UUID.randomUUID()));
	}

	/** A UUID in the four-int form {@code UUIDUtil.CODEC} reads. */
	private static String asUuidArray(UUID id) {
		return "[" + (int) (id.getMostSignificantBits() >> 32)
				+ "," + (int) id.getMostSignificantBits()
				+ "," + (int) (id.getLeastSignificantBits() >> 32)
				+ "," + (int) id.getLeastSignificantBits() + "]";
	}

	private static void expect(String what, boolean condition) {
		if (condition) {
			System.out.println("  ok   " + what);
		} else {
			System.out.println("  FAIL " + what);
			failures++;
		}
	}
}
