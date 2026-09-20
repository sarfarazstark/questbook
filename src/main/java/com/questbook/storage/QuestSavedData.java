package com.questbook.storage;

import com.questbook.QuestBook;
import com.questbook.data.Quest;
import com.questbook.data.PlayerStats;
import com.questbook.data.QuestStore;
import com.questbook.data.Reward;
import com.questbook.data.Task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The mod's saved data, attached to the overworld.
 *
 * <p>Uses vanilla's {@link SavedData} rather than Fabric API: as of MC 26.2 there
 * is no Fabric wrapper, and {@code PersistentState} no longer exists — it was
 * renamed to {@code SavedData}, and its serialisation moved from an overridden
 * {@code save} method to an external {@link Codec}. Mutation marks the data dirty
 * via {@link #setDirty()}; vanilla then flushes it on the world save. There is no
 * hand-rolled save method to call.
 */
public final class QuestSavedData extends SavedData {
	/** Wire/disk form: quests, their rewards, and which quests have paid out. */
	public record Document(List<Quest> quests, Map<String, Reward> rewards, List<String> paid,
			List<String> paidTasks, Map<String, PlayerStats> stats) {
		public static final Codec<Document> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Quest.CODEC.listOf().fieldOf("goals").forGetter(Document::quests),
				Codec.unboundedMap(Codec.STRING, Reward.CODEC)
						.optionalFieldOf("rewards", Map.of())
						.forGetter(Document::rewards),
				Codec.STRING.listOf().optionalFieldOf("paid", List.of()).forGetter(Document::paid),
				Codec.STRING.listOf().optionalFieldOf("paidTasks", List.of()).forGetter(Document::paidTasks),
				Codec.unboundedMap(Codec.STRING, PlayerStats.CODEC)
						.optionalFieldOf("stats", Map.of())
						.forGetter(Document::stats)
		).apply(instance, Document::new));
	}

	public static final SavedDataType<QuestSavedData> TYPE = new SavedDataType<>(
			QuestBook.id("goals"),
			QuestSavedData::new,
			Document.CODEC.xmap(QuestSavedData::new, QuestSavedData::toDocument),
			DataFixTypes.SAVED_DATA_MAP_DATA);

	private QuestStore store;
	private Map<String, Reward> rewards;
	private final java.util.Set<UUID> paid;
	private final java.util.Set<UUID> paidTasks;
	private Map<UUID, PlayerStats> stats;

	public QuestSavedData() {
		this.store = QuestStore.EMPTY;
		this.rewards = Map.of();
		this.paid = new java.util.LinkedHashSet<>();
		this.paidTasks = new java.util.LinkedHashSet<>();
		this.stats = Map.of();
	}

	private QuestSavedData(Document document) {
		this.store = new QuestStore(document.quests());
		this.rewards = Map.copyOf(document.rewards());
		this.paid = new java.util.LinkedHashSet<>();

		for (String id : document.paid()) {
			try {
				this.paid.add(UUID.fromString(id));
			} catch (IllegalArgumentException malformed) {
				QuestBook.LOGGER.warn("Ignoring malformed paid-quest id {}", id);
			}
		}

		this.paidTasks = new java.util.LinkedHashSet<>();

		for (String id : document.paidTasks()) {
			try {
				this.paidTasks.add(UUID.fromString(id));
			} catch (IllegalArgumentException malformed) {
				QuestBook.LOGGER.warn("Ignoring malformed paid-task id {}", id);
			}
		}

		// Keys are parsed defensively for the same reason as `paid`: one malformed
		// entry must not make the whole world's data unreadable.
		this.stats = new java.util.LinkedHashMap<>();

		for (Map.Entry<String, PlayerStats> entry : document.stats().entrySet()) {
			try {
				this.stats.put(UUID.fromString(entry.getKey()), entry.getValue());
			} catch (IllegalArgumentException malformed) {
				QuestBook.LOGGER.warn("Ignoring stats entry with malformed player id {}", entry.getKey());
			}
		}
	}

	private Document toDocument() {
		java.util.Map<String, PlayerStats> encoded = new java.util.LinkedHashMap<>();

		for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
			encoded.put(entry.getKey().toString(), entry.getValue());
		}

		return new Document(store.quests(), rewards, paid.stream().map(UUID::toString).toList(),
				paidTasks.stream().map(UUID::toString).toList(), encoded);
	}

	/**
	 * Whether {@code quest}'s reward has already been granted.
	 *
	 * <p>Persisted, not in-memory: an in-memory set would re-grant every completed
	 * quest's reward on each server restart.
	 */
	public boolean isPaid(UUID quest) {
		return paid.contains(quest);
	}

	public void markPaid(UUID quest) {
		if (paid.add(quest)) {
			setDirty();
		}
	}

	/**
	 * Whether {@code task}'s own reward has already been granted.
	 *
	 * <p>Persisted like {@link #isPaid}: a task reward is a one-shot side effect,
	 * and an in-memory set would re-pay every completed task on restart.
	 */
	public boolean isTaskPaid(UUID task) {
		return paidTasks.contains(task);
	}

	public void markTaskPaid(UUID task) {
		if (paidTasks.add(task)) {
			setDirty();
		}
	}

	public QuestStore store() {
		return store;
	}

	/** Replaces the store and marks the data for saving. */
	public void setStore(QuestStore next) {
		this.store = next;
		setDirty();
	}

	public Optional<Reward> reward(Quest quest) {
		return Optional.ofNullable(rewards.get(quest.id().toString()));
	}

	public void setReward(Quest quest, Optional<Reward> reward) {
		Map<String, Reward> next = new java.util.LinkedHashMap<>(rewards);
		reward.ifPresentOrElse(r -> next.put(quest.id().toString(), r), () -> next.remove(quest.id().toString()));
		this.rewards = Map.copyOf(next);
		setDirty();
	}

	/** Every player's contribution tally, ranked best first. */
	public java.util.List<Map.Entry<UUID, PlayerStats>> rankedStats() {
		return PlayerStats.ranked(stats);
	}

	public PlayerStats statsOf(UUID player) {
		return stats.getOrDefault(player, PlayerStats.EMPTY);
	}

	/** Credits one completed task to {@code player}. Idempotent in effect, not in fact. */
	public void recordTaskCompletion(UUID player) {
		Map<UUID, PlayerStats> next = new java.util.LinkedHashMap<>(stats);
		next.put(player, statsOf(player).withTask());
		this.stats = Map.copyOf(next);
		setDirty();
	}

	/** Credits one completed quest to {@code player}. */
	public void recordQuestCompletion(UUID player) {
		Map<UUID, PlayerStats> next = new java.util.LinkedHashMap<>(stats);
		next.put(player, statsOf(player).withQuest());
		this.stats = Map.copyOf(next);
		setDirty();
	}
}
