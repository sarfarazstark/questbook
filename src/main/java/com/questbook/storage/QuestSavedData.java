package com.questbook.storage;

import com.questbook.QuestBook;
import com.questbook.data.PlayerStats;
import com.questbook.data.Quest;
import com.questbook.data.QuestStore;
import com.questbook.data.Task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	/** Wire/disk form. */
	public record Document(List<Quest> quests, Map<String, PlayerStats> stats, List<String> editors) {
		public static final Codec<Document> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Quest.CODEC.listOf().fieldOf("goals").forGetter(Document::quests),
				Codec.unboundedMap(Codec.STRING, PlayerStats.CODEC)
						.optionalFieldOf("stats", Map.of())
						.forGetter(Document::stats),
				// Optional so a world saved before editor access existed still decodes.
				Codec.STRING.listOf().optionalFieldOf("editors", List.of()).forGetter(Document::editors)
		).apply(instance, Document::new));
	}

	public static final SavedDataType<QuestSavedData> TYPE = new SavedDataType<>(
			QuestBook.id("goals"),
			QuestSavedData::new,
			Document.CODEC.xmap(QuestSavedData::new, QuestSavedData::toDocument),
			DataFixTypes.SAVED_DATA_MAP_DATA);

	private QuestStore store;
	private Map<UUID, PlayerStats> stats;
	/**
	 * Players granted editor access, beyond anyone holding OP level 2.
	 *
	 * <p>Stored here rather than as a vanilla permission because vanilla has no
	 * "may edit quests" concept, and the point is to grant this one ability without
	 * handing over the OP tag.
	 */
	private Set<UUID> editors;

	public QuestSavedData() {
		this.store = QuestStore.EMPTY;
		this.stats = Map.of();
		this.editors = Set.of();
	}

	/**
	 * Disk form. Public so {@code QuestModelCheck} can prove the editor set survives a
	 * reload without standing up a server; it is the same pair the codec uses.
	 */
	public QuestSavedData(Document document) {
		this.store = new QuestStore(document.quests());

		// Keys are parsed defensively: one malformed entry must not make the whole
		// world's data unreadable.
		this.stats = new LinkedHashMap<>();

		for (Map.Entry<String, PlayerStats> entry : document.stats().entrySet()) {
			try {
				this.stats.put(UUID.fromString(entry.getKey()), entry.getValue());
			} catch (IllegalArgumentException malformed) {
				QuestBook.LOGGER.warn("Ignoring stats entry with malformed player id {}", entry.getKey());
			}
		}

		this.editors = new LinkedHashSet<>();

		for (String id : document.editors()) {
			try {
				this.editors.add(UUID.fromString(id));
			} catch (IllegalArgumentException malformed) {
				QuestBook.LOGGER.warn("Ignoring editor entry with malformed player id {}", id);
			}
		}
	}

	/** Disk form. Public for the same reason as the constructor above. */
	public Document toDocument() {
		Map<String, PlayerStats> encoded = new LinkedHashMap<>();

		for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
			encoded.put(entry.getKey().toString(), entry.getValue());
		}

		return new Document(store.quests(), encoded,
				editors.stream().map(UUID::toString).toList());
	}

	/**
	 * Whether {@code player} may edit quests without OP.
	 *
	 * <p>Callers must still allow anyone holding OP 2; this answers only the extra
	 * grant, so the two checks are combined at the call site rather than hidden here.
	 */
	public boolean isEditor(UUID player) {
		return editors.contains(player);
	}

	/** Grantees, for {@code /questbook editors}. */
	public Set<UUID> editors() {
		return Set.copyOf(editors);
	}

	/** Grants editor access. No-op if already granted. */
	public void addEditor(UUID player) {
		Set<UUID> next = new LinkedHashSet<>(editors);

		if (next.add(player)) {
			this.editors = Set.copyOf(next);
			setDirty();
		}
	}

	/** Revokes editor access. No-op if not granted. */
	public void removeEditor(UUID player) {
		Set<UUID> next = new LinkedHashSet<>(editors);

		if (next.remove(player)) {
			this.editors = Set.copyOf(next);
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

	/** Every player's contribution tally, ranked best first. */
	public List<Map.Entry<UUID, PlayerStats>> rankedStats() {
		return PlayerStats.ranked(stats);
	}

	public PlayerStats statsOf(UUID player) {
		return stats.getOrDefault(player, PlayerStats.EMPTY);
	}

	/** Credits one completed task to {@code player}. Idempotent in effect, not in fact. */
	public void recordTaskCompletion(UUID player) {
		Map<UUID, PlayerStats> next = new LinkedHashMap<>(stats);
		next.put(player, statsOf(player).withTask());
		this.stats = Map.copyOf(next);
		setDirty();
	}

	/** Credits one completed quest to {@code player}. */
	public void recordQuestCompletion(UUID player) {
		Map<UUID, PlayerStats> next = new LinkedHashMap<>(stats);
		next.put(player, statsOf(player).withQuest());
		this.stats = Map.copyOf(next);
		setDirty();
	}
}
