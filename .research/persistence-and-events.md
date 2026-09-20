# Strict Research — Part 2: Persistence, Lifecycle, Events (MC 26.2)

All verified from bytecode in the local Gradle cache. Nothing guessed.

---

## 1. CRITICAL 26.2 CHANGE: `PersistentState` is now `SavedData`

**This is exactly the trap that breaks older tutorials.**

| Old (pre-26.x) | 26.2 |
|---|---|
| `net.minecraft.world.level.saveddata.PersistentState` | `net.minecraft.world.level.saveddata.SavedData` |
| `PersistentState.Type<T>` | `SavedDataType<T>` (a **record**) |
| `DimensionDataStorage` | `SavedDataStorage` |

Evidence: the common jar contains `SavedData.class` and `SavedDataType.class`
and **no** `PersistentState.class`.

### `SavedData` — the base class (26.2)

```java
public abstract class net.minecraft.world.level.saveddata.SavedData {
    public SavedData();
    public void setDirty();
    public void setDirty(boolean);
    public boolean isDirty();
}
```

Note: it has **no** abstract `save(CompoundTag)` method any more. Serialisation is
supplied externally via a Codec in `SavedDataType`.

### `SavedDataType<T>` — a record

```java
public final class SavedDataType<T extends SavedData> extends Record {
    SavedDataType(Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType)
    public Identifier id()
    public Supplier<T> constructor()
    public Codec<T> codec()
    public DataFixTypes dataFixType()
}
```

So a mod declares its data type with: an id, a constructor, **a Mojang `Codec`**,
and a `DataFixTypes`. That means the codec/serialisation is written by hand
(records + `RecordCodecBuilder` is the idiomatic route).

### `MinecraftServer.getDataStorage()` — PUBLIC access point

```java
public net.minecraft.world.level.storage.SavedDataStorage getDataStorage();
```

### `SavedDataStorage`

```java
public <T extends SavedData> T computeIfAbsent(SavedDataType<T>);
public <T extends SavedData> T get(SavedDataType<T>);
public <T extends SavedData> void set(SavedDataType<T>, T);
```

**Verdict:** world-attached persistence IS available and public in 26.2, via
`server.getDataStorage().computeIfAbsent(TYPE)`. It survives restarts and is
written to the world `data/` folder. This is the correct choice for goal/task data.

---

## 2. IMPORTANT: Fabric API has NO PersistentState/SavedData wrapper

Verified by scanning every fabric-api jar in the cache for `PersistentState` and
`SavedData` — **zero matches**.

So there is no `fabric-persistent-state-api`. A mod must use vanilla's `SavedData` +
`SavedDataType` directly. (The old `fabric-persistent-state-api-v1` does not
appear to be part of fabric-api 0.161.0+26.2.)

---

## 3. Server lifecycle events — exact signatures

Package: `net.fabricmc.fabric.api.event.lifecycle.v1` (verified — NOT `...lifecycle.v1` under `api/`)

```java
ServerLifecycleEvents.SERVER_STARTING   -> void onServerStarting(MinecraftServer)
ServerLifecycleEvents.SERVER_STARTED    -> void onServerStarted(MinecraftServer)
ServerLifecycleEvents.SERVER_STOPPING   -> void onServerStopping(MinecraftServer)
ServerLifecycleEvents.SERVER_STOPPED    -> void onServerStopped(MinecraftServer)
ServerLifecycleEvents.BEFORE_SAVE       -> void onBeforeSave(MinecraftServer, boolean, boolean)
ServerLifecycleEvents.AFTER_SAVE        -> void onAfterSave(MinecraftServer, boolean, boolean)
```

**Rule:** mark `SavedData` dirty on mutation (`setDirty()`, inherited); do NOT
hand-roll save-on-stop. Vanilla flushes dirty data. Use `SERVER_STOPPING` only for
non-world state.

### Tick events (for debounced work)

```java
ServerTickEvents.START_SERVER_TICK / END_SERVER_TICK
ServerTickEvents.START_LEVEL_TICK  / END_LEVEL_TICK
```

### Entity events

```java
ServerEntityEvents.ENTITY_LOAD
ServerEntityEvents.ENTITY_UNLOAD
ServerEntityEvents.ALLOW_LOAD
ServerEntityEvents.EQUIPMENT_CHANGE
```

**Note:** `ENTITY_LOAD` is *not* an inventory-change event. There is still **no
Fabric API event for item pickup or inventory change** — confirming the previous
mod's need for mixins (`ItemEntityMixin` before `Player.onItemPickup`,
`ResultSlotMixin` for craft output). That finding stands.

---

## 4. Thread safety

Network packet handlers run on the **netty thread**, not the server thread.
Any game-state mutation MUST be rescheduled:

```java
server.execute(() -> { /* safe */ });
```

This is the standard vanilla `MinecraftServer.execute(Runnable)` (an
`Executor`). Do not mutate world/player state directly in a packet handler.

---

## 5. Still not verified

- **JDA / Discord4J current versions, licences, release recency** — requires web
  access; the research attempt failed on infrastructure, and I will not guess versions.
- **`ServerPlayNetworking` exact signature in fabric-networking-api-v1 6.3.4** —
  not yet read, though the previous mod uses it successfully so the pattern is proven.

---

## Source of truth

- Common jar: `minecraft-common-deobf-26.2.jar`
- Client jar: `minecraft-clientonly-deobf-26.2.jar`
- `fabric-lifecycle-events-v1-4.1.4+29b6eb019e.jar`
- All under `~/.gradle/caches/`
