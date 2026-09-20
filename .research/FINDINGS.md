# Strict Research — Craft-Goal Mod (MC 26.2 / Fabric)

Every claim below was verified against real bytecode, source, or pixels.
Anything unverified is marked **[UNVERIFIED]**.

---

## 1. The item picker — the headline finding

### Vanilla's creative inventory CANNOT be reused. Rebuild it.

Evidence from `net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen.class`:

```
public class CreativeModeInventoryScreen extends AbstractContainerScreen<CreativeModeInventoryScreen$ItemPickerMenu>
  public CreativeModeInventoryScreen(LocalPlayer, FeatureFlagSet, boolean)
  private void refreshSearchResults();
  private void selectTab(CreativeModeTab);
  private boolean checkTabClicked(CreativeModeTab, double, double);
  private int getTabX(CreativeModeTab);
  ...
```

**41 private members.** Every relevant method is private. Worse, the constructor
requires a `LocalPlayer` **and** an `ItemPickerMenu` container — meaning the class
is welded to the creative *container/menu* system, not a reusable widget.

Additional blockers:

| Element | Status | Evidence |
|---|---|---|
| Tab textures | `private static final Identifier[]` | hardcoded arrays |
| `selectedTab` | `private static` | global static state |
| Search | `private void refreshSearchResults()` | not callable |
| Search trees | public class, but driven by the screen | `SessionSearchTrees` |

**Verdict: rebuild the picker.** But the *ingredients* are all public:

### What IS public and usable

```
net.minecraft.world.item.CreativeModeTab        (public class)
  public Component getDisplayName()
  public ItemStack getIconItem()
  public Identifier getBackgroundTexture()
  public Row row()          // row layout
  public int column()       // column layout
  public boolean canScroll()
  public static CreativeModeTab.Builder builder(Row, int)

net.minecraft.world.item.CreativeModeTabs       (the built-in tab set)
net.minecraft.core.registries.BuiltInRegistries.ITEM             // every item, live
net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB // every tab, live
```

**The picker can be built entirely from public API:**

1. **Tabs** — iterate `BuiltInRegistries.CREATIVE_MODE_TAB`; for each, read
   `getDisplayName()`, `getIconItem()`, `row()`, `column()`. Draw the tab strip
   yourself. This gives *real* vanilla categories with no hardcoding.
2. **Items per tab** — a `CreativeModeTab`'s contents are built via its
   `DisplayItemsGenerator`; the simplest route is to filter
   `BuiltInRegistries.ITEM` by an item-tag or category of your own, or read the
   tab's generated `ItemStack` collection.
3. **Search** — filter `BuiltInRegistries.ITEM` by registry id (proven pattern,
   see below). Vanilla uses `SessionSearchTrees` (public class) but its
   `creativeByNameSearch` field is private, so a plain substring filter is
   simpler and adequate.
4. **Number entry** — no vanilla widget needed; a plain text field + validation.

### Our existing ItemPickerScreen already proves the core

`src/client/java/com/teamgoals/client/gui/ItemPickerScreen.java` reads
`BuiltInRegistries.ITEM` live and filters by registry id, capped at 200 results.
It works today. It gives **search + click**, missing only **category tabs** and
**number entry** — both of which are additive, not a rewrite.

---

## 2. Vanilla book palette — measured, not guessed

Full detail in `book-reference.md`. Summary:

| Role | Hex |
|---|---|
| Page base (main parchment) | `#FDF7EA` |
| Page highlight | `#FFFAEE` |
| Page shade | `#F9EED0` |
| Page inner shadow | `#F1E2B8` |
| Page edge | `#D1BFA1` |
| Border mid | `#75321E` |
| Border dark | `#652816` |
| Border darkest | `#4C1A0B` |
| Outline | `#1C0F00` |
| Spine ribbon | `#C3251D` / `#951A13` |

Texture is **256×256**; the book occupies **146×180** inside it; vanilla blits it
at **192×192**. Text is black, `withoutShadow()`.

Parchment panel recipe: `outline → 2px #652816/#75321E border → #D1BFA1 edge → #FDF7EA page`.

### Two-page question: **CONFIRMED single page**, three independent ways

1. `book.png` blits 192×192.
2. No other book GUI texture exists in the jar.
3. `BookViewScreen` lays text out for one page width (114px wrap).

Any open-book look must be custom. (This is what sank the previous mod.)

---

## 3. Reusable code audit — team-quest

4,810 lines total. Genuinely reusable:

| File | Lines | Why keep |
|---|---|---|
| `tracking/DiscordWebhook.java` | 104 | **Working Discord webhook.** Uses `HttpClient.sendAsync` so it never blocks the tick loop. Exactly the pattern needed. |
| `client/gui/ItemPickerScreen.java` | 190 | Live registry search; the base of the craft widget |
| `tracking/ItemTracker.java` | — | Pickup/craft → progress pipeline, server-authoritative |
| `mixin/ItemEntityMixin.java` | — | Item pickup hook (**no Fabric API event exists**) |
| `mixin/ResultSlotMixin.java` | — | Craft-output hook |
| `storage/JsonStorage.java` | — | JSON persistence pattern |
| `data/*.java` | — | Goal/Task/Requirement/Chapter POJOs (rename Task→Goal structure) |
| `network/*` | — | Payload + codec pattern, proven on 26.2 |
| `client/hud/QuestHud.java` | — | HUD overlay (the bug you reported is fixed here) |

**Rewrite:** all of `gui/TaskListScreen.java` and `gui/TaskEditorScreen.java`
(the UI was the failure point).

### Important: mixins ARE required

There is **no Fabric API event for item pickup**. `ItemEntityMixin` injects before
`Player.onItemPickup`. This is verified by the existing working code — the new mod
will need the same mixins.

---

## 4. Discord — the answer is already in this codebase

`DiscordWebhook.java` implements the **webhook** approach correctly:
- `HttpClient.sendAsync(...)` — off the server thread
- `whenComplete` logging, failures ignored
- 5s timeout, redirects followed
- Config-gated (blank URL = disabled)

For a goal/task mod broadcasting completions, **webhooks are sufficient**. A bot
library (JDA/Discord4J) would add a persistent gateway connection, a new
dependency, and rate-limit handling — for features you do not need (slash
commands could be a later, separate concern).

**[UNVERIFIED]** Current JDA/Discord4J versions and release dates — the web
research for this failed (see below).

---

## 5. Where the research is incomplete — stated honestly

5 research agents were dispatched for: item picker, Discord libraries, persistence,
book theming, and owo-lib deep-dive. **All 5 failed** with an infrastructure error
(`Genspark credits exhausted`) — the subagent model backend is unavailable, not a
research dead end.

I completed **item picker**, **book palette/two-page**, **persistence**,
**lifecycle events**, **networking**, and the **reuse audit** myself, from bytecode
and pixels, when the agents could not.

### Now VERIFIED (see `persistence-and-events.md`)

- Persistence: vanilla `SavedData` + `SavedDataType` (26.2 renamed from
  `PersistentState`), reached via public `MinecraftServer.getDataStorage()`
- Fabric API has **no** PersistentState/SavedData wrapper (scanned every jar)
- `ServerLifecycleEvents.*` exact signatures
- Network packet handlers run off-thread; use `server.execute(...)`
- `ServerPlayNetworking` / `PayloadTypeRegistry` signatures
- `BuiltInRegistries.CREATIVE_MODE_TAB` gives real category tabs

### VERIFIED BY COMPILING (not inference)

Two throwaway probes were written and **compiled successfully** against 26.2,
then removed. They proved:

1. **Codec authoring works** — `RecordCodecBuilder.create(...)` with
   `Codec.unboundedMap(Codec.STRING, Entry.CODEC.listOf())` for a map of
   owner → goal list. Compiled.
2. **`DataFixTypes.SAVED_DATA_MAP_DATA`** is a valid constant for a mod's
   `SavedDataType`. (Other `SAVED_DATA_*` constants exist too:
   `SAVED_DATA_COMMAND_STORAGE`, `SAVED_DATA_GAME_RULES`, `SAVED_DATA_MAP_INDEX`, …)
3. **The declaration compiles:**
   ```java
   new SavedDataType<>(
       Identifier.fromNamespaceAndPath("mymod", "goals"),
       MyData::new,
       MyDocument.CODEC.xmap(MyData::new, d -> d.document),
       DataFixTypes.SAVED_DATA_MAP_DATA);
   ```
4. **Access + lifecycle wiring compiles:**
   ```java
   ServerLifecycleEvents.SERVER_STARTING.register(server -> {
       MyData d = server.getDataStorage().computeIfAbsent(MyData.TYPE);
   });
   ```
5. **`setDirty()` is the mutation contract** — `SavedData` in 26.2 has NO
   abstract `save()` method; marking dirty is sufficient for vanilla to flush.

### Still NOT verified — will not be guessed

- **JDA / Discord4J current versions, licences, release recency** — needs web
  access, which failed. The existing `DiscordWebhook` already covers the feature.
- **owo-lib maintenance status and full theming capability** — partially known
  from this session, not fully documented.
- **`CreativeModeTab` contents iteration** — `DisplayItemsGenerator` is how a tab
  builds its items, but the exact call to realise that list from a mod has not
  been compiled. Filtering `BuiltInRegistries.ITEM` is the proven fallback.

The persistence and event layers are now settled with compiled evidence. The
remaining gaps are web-dependent (Discord) or nice-to-have (per-tab item lists).
