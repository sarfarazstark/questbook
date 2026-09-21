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
		checkPinning();
		checkOrderPreserved();
		checkQuestOrderPreserved();
		checkPlayerStats();
		checkAssigneeNamePersists();
		checkEditorAccess();
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
		expect("withAssignee keeps the pin", pinned.withAssignee(alex, "Alex").pinned());

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
	 * The assignee's name must outlive their session.
	 *
	 * <p>A UUID cannot be turned back into a name, so a name resolved only from the
	 * online player list shows a stub the moment the assignee logs out. Recording it on
	 * the task is what makes the label survive, and it must do so through every mutation
	 * and through the disk round trip.
	 */
	private static void checkAssigneeNamePersists() {
		UUID alex = UUID.randomUUID();
		Task task = Task.create("minecraft:oak_log", 4, alex, "Alex");

		expect("create records the name", task.assigneeName().equals("Alex"));

		// Every rebuild must carry it, or the name is lost the first time progress ticks.
		expect("advance keeps the name", task.advance(1).assigneeName().equals("Alex"));
		expect("withProgress keeps the name", task.withProgress(1).assigneeName().equals("Alex"));
		expect("withPinned keeps the name", task.withPinned(true).assigneeName().equals("Alex"));

		// Reassignment records the new name at the same moment as the new id, so the
		// two cannot disagree about who the assignee was.
		UUID sam = UUID.randomUUID();
		Task reassigned = task.withAssignee(sam, "Sam");
		expect("reassign updates the name", reassigned.assigneeName().equals("Sam"));
		expect("reassign updates the id", reassigned.assignee().equals(sam));

		// An edit changes amount and assignee together, never the item.
		Task edited = task.withCountAndAssignee(10, sam, "Sam");
		expect("edit updates the count", edited.count() == 10);
		expect("edit updates the assignee", edited.assignee().equals(sam));
		expect("edit updates the name", edited.assigneeName().equals("Sam"));
		expect("edit leaves the item alone", edited.itemId().equals("minecraft:oak_log"));

		// Shrinking below current progress must clamp, not read 70/64.
		Task shrunk = task.advance(4).withCountAndAssignee(2, alex, "Alex");
		expect("shrinking clamps progress", shrunk.progress() == 2);
		expect("a clamped task reads complete", shrunk.isComplete());

		// Null is normalised, so no caller can store a null into a non-null field.
		expect("null name normalises to empty",
				Task.create("minecraft:stone", 1, alex, null).assigneeName().isEmpty());

		// The disk round trip is the whole point: this is what survives a restart.
		Quest quest = Quest.create("Cottage").withTask(task);
		Task loaded = QuestStore.EMPTY.withQuest(quest).quests().get(0).tasks().get(0);
		expect("store keeps the assignee name", loaded.assigneeName().equals("Alex"));
	}

	/**
	 * Editor grants survive the round trip, and revoking is exact.
	 *
	 * <p>An editor is the one thing here that widens who may write, so the set has to
	 * persist across a restart: an in-memory set would silently revoke every grant on
	 * the next world load and look like a bug in the command.
	 */
	private static void checkEditorAccess() {
		UUID alex = UUID.randomUUID();
		UUID sam = UUID.randomUUID();

		QuestSavedData data = new QuestSavedData();

		expect("nobody is an editor by default", !data.isEditor(alex));
		expect("the default set is empty", data.editors().isEmpty());

		data.addEditor(alex);
		expect("a granted player is an editor", data.isEditor(alex));
		expect("a different player is not", !data.isEditor(sam));
		expect("the set holds the grant", data.editors().size() == 1);

		// Granting twice must not duplicate, or the list output shows repeats.
		data.addEditor(alex);
		expect("granting twice does not duplicate", data.editors().size() == 1);

		data.addEditor(sam);
		expect("a second grant lands", data.editors().size() == 2);

		// Revoking one must leave the other: an off-by-one here would either keep
		// access for a revoked player or drop an unrelated grant.
		data.removeEditor(alex);
		expect("revoking removes that player", !data.isEditor(alex));
		expect("revoking leaves the others", data.isEditor(sam));

		data.removeEditor(alex);
		expect("revoking twice is harmless", data.editors().size() == 1);

		// The disk round trip is the point: this is what outlives a restart.
		QuestSavedData reloaded = new QuestSavedData(data.toDocument());
		expect("grants survive a reload", reloaded.isEditor(sam));
		expect("revocations survive a reload", !reloaded.isEditor(alex));

		// A malformed id must be skipped, not crash the world load.
		QuestSavedData.Document dirty = new QuestSavedData.Document(List.of(), Map.of(),
				List.of("not-a-uuid", sam.toString()));
		QuestSavedData repaired = new QuestSavedData(dirty);
		expect("a malformed editor id is ignored", repaired.editors().size() == 1);
		expect("the valid grant beside it survives", repaired.isEditor(sam));
	}

	/**
	 * A world saved before the reward and prerequisite systems were removed.
	 *
	 * <p>Those keys are no longer in the codec, so they are ignored on load rather
	 * than rejected, and they stop being written. The quest and task data around
	 * them must survive untouched.
	 */
	private static void checkBackwardCompatibleDocument() {
		UUID alex = UUID.randomUUID();
		Quest quest = Quest.create("Gather Wood").withTask(Task.create("minecraft:oak_log", 2, alex));

		// A save from when rewards and prerequisites existed. UUIDs are written the way
		// UUIDUtil.CODEC reads them, as an int pair rather than a dashed string.
		String legacy = "{\"goals\":[{\"id\":" + asUuidArray(quest.id()) + ",\"name\":\"Gather Wood\","
				+ "\"prerequisites\":[" + asUuidArray(UUID.randomUUID()) + "],"
				+ "\"tasks\":[{\"id\":" + asUuidArray(quest.tasks().get(0).id())
				+ ",\"item\":\"minecraft:oak_log\","
				+ "\"count\":2,\"assignee\":" + asUuidArray(alex) + ","
				+ "\"reward\":{\"kind\":\"XP\",\"value\":\"\",\"amount\":5}}]}],"
				+ "\"rewards\":{},\"paid\":[],\"paidTasks\":[]}";

		JsonElement parsed = JsonParser.parseString(legacy);
		DataResult<QuestSavedData.Document> decoded =
				QuestSavedData.Document.CODEC.parse(JsonOps.INSTANCE, parsed);

		expect("an old document with reward keys decodes", decoded.result().isPresent());

		if (decoded.error().isPresent()) {
			System.out.println("       decode error: " + decoded.error().get().message());
		}

		decoded.result().ifPresent(document -> {
			expect("old quests survive", document.quests().size() == 1);
			expect("old tasks survive", document.quests().get(0).tasks().size() == 1);
			expect("old task keeps its count",
					document.quests().get(0).tasks().get(0).count() == 2);
			expect("old stats default to empty", document.stats().isEmpty());
		});

		// Stats are all that is left of the document beyond the quests themselves.
		QuestSavedData data = new QuestSavedData();
		data.recordTaskCompletion(alex);
		data.recordQuestCompletion(alex);

		expect("stats are recorded", data.statsOf(alex).points() == PlayerStats.TASK_POINTS
				+ PlayerStats.GOAL_POINTS);
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
