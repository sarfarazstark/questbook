# Quest Book

A Fabric mod for Minecraft 26.2. Admins create **quests**, each containing
**tasks** assigned to individual players. Progress tracks automatically.

- **Mod id:** `questbook`
- **Package:** `com.questbook`
- **MC:** 26.2 · **Loader:** 0.19.5 · **Fabric API:** 0.161.0+26.2 · **Java:** 25

## Model

```
Quest  "Build a Cottage"
  ├─ Task  minecraft:oak_log    64  → Alex   0/64
  ├─ Task  minecraft:cobblestone 32 → Sam    12/32
  └─ Task  minecraft:glass      16  → Alex   16/16  done
```

Two rules, taken literally:

1. **One task has exactly one assignee.** A task stores a single `assignee` UUID.
2. **One player may hold many tasks.** Hence one-to-many from the task side —
   no join table, and each player's progress is naturally independent.

A **quest completes** when *every* task in it completes. Its reward then fires.

A quest may also declare **prerequisites** — other quests that must complete
first. `/questbook tree` shows the dependency graph; a locked quest is marked
`[locked]` until every prerequisite is done.

## Tracking

Progress advances only when the **assignee** collects the required item. Another
player's pickups are ignored — that is the assignee rule made real.

Two hooks, both server-side:

| Event | Hook | Why |
|---|---|---|
| Item pickup | `ItemEntityMixin` | **No Fabric API event exists for pickup** |
| Craft output | `ItemStackMixin` | No API event covers craft output |

Both are required; neither is optional.

## Persistence

Uses vanilla `SavedData` + a Mojang `Codec`, written to
`world/data/questbook/goals.dat`.

**MC 26.2 renamed this API.** `PersistentState` no longer exists:

| Old | 26.2 |
|---|---|
| `PersistentState` | `SavedData` |
| `PersistentState.Type` | `SavedDataType` (a record) |
| `DimensionDataStorage` | `SavedDataStorage` |

There is also **no Fabric API wrapper** for it — vanilla's is used directly.
`SavedData` has no `save()` method; mutating calls `setDirty()` and vanilla
flushes on the world save.

## Commands

Editing requires **OP level 2**, because a `COMMAND` reward is a
privilege-escalation surface.

```
/questbook new <quest>                            create a quest
/questbook add <quest> <player> <item> <count>    add a task, assigned
/questbook assign <quest> <task> <player>         reassign a task
/questbook delete <quest>                         remove a quest
/questbook require <quest> <prerequisite>         lock behind another quest
/questbook unrequire <quest> <prerequisite>       unlock
/questbook reward item <quest> <item> <count>     quest item reward
/questbook reward xp <quest> <levels>             quest xp reward
/questbook reward command <quest> <template>      quest command reward
/questbook reward clear <quest>                   remove the reward
/questbook taskreward item <quest> <task> <item> <count>    task item reward
/questbook taskreward xp <quest> <task> <levels>            task xp reward
/questbook taskreward command <quest> <task> <template>     task command reward
/questbook taskreward clear <quest> <task>                  remove the task reward
/questbook list                                    all quests and tasks
/questbook tree                                    dependency graph
/questbook top [limit]                             leaderboard, 10 by default
/questbook stats [player]                          points, tasks, quests
/questbook mine                                    your assigned tasks
/questbook discord url <webhook>                   set the Discord webhook
/questbook discord off                              stop posting
/questbook discord test                             send a probe message
/questbook discord status                           show the current config
```

A quest may be named or given by UUID, so duplicate names stay addressable.
A task takes a **UUID prefix** (the id printed beside it in `/questbook list`),
not an item name.

## Rewards

Three kinds, which between them cover everything without new code per idea:

| Kind | Grants |
|---|---|
| `ITEM` | N of an item, dropped if the inventory is full |
| `XP` | N experience levels |
| `COMMAND` | runs a server command, `%player%` substituted |

`COMMAND` is the extensible one — any reward expressible as a command works with
no mod change. `%player%` is substituted from the player object, never from user
input, so it cannot inject command syntax.

Rewards exist at **two levels**, and the distinction matters:

| Level | Fires when | Points |
|---|---|---|
| Task reward | that one task completes | 1 |
| Quest reward | every task in the quest completes | 10 |

Both levels are optional, and a quest with a task reward set still grants its
quest reward on top — they stack deliberately.

Paid rewards are recorded **in the saved data**, not in memory, so a restart
cannot re-grant them.

## Design decisions

**Why `COMMAND` rewards are gated.** Reward editing is OP 2+. A lower-privileged
editor could otherwise name `/op` or similar.

**Why rewards fire on the server tick.** A command reward must run on the server
thread; detecting completion on the tick keeps that guaranteed rather than
depending on where the tracker was called from.

**Why offline recipients are skipped, not queued.** Queuing needs a persistent
per-player pending list. Out of scope for this phase; the skip is logged.

## The book

Press **J** to open. A parchment page listing the quests this player has tasks in,
each with its tasks and progress.

```
+----------------------------------------+
| Quest Book                        [x]  |
| My Tasks                    1/3 done   |
|----------------------------------------|
| Build a Cottage                        |
|   ( ) Oak log  ####------  10/64       |
|   ( ) Cobblestone  ###---  12/32       |
| Reward Test                            |
|   (v) Stone  ##########  5/5           |
|----------------------------------------|
| scroll for more                        |
+----------------------------------------+
```

**Verification keybind:** `J` — free in vanilla (`L` is Advancements).

### Looks

The parchment is drawn with **solid fills**, not a texture, using colours sampled
from `assets/minecraft/textures/gui/book.png`. Vanilla's book sprite is a single
192×192 page with no two-page variant, so it cannot be scaled to a larger panel —
see `.research/book-reference.md` for the measured palette and the verdict.

| Element | Colour |
|---|---|
| Page | `#FDF7EA` |
| Page edge | `#D1BFA1` |
| Border bands | `#75321E` → `#652816` → `#4C1A0B` → `#1C0F00` |
| Progress bar fill | `#C3251D` |
| Completed | `#2E7D32` |

### Networking

The server sends only **this player's** tasks — a player has no reason to see
another player's assignments, and sending them would leak the whole plan.

| When | What |
|---|---|
| Player joins | full sync of their tasks |
| Admin runs any editing command | push to every online client |

Titles are resolved **server-side**, so the client never touches the item registry
to render a row.

## Verification

**Static**
- `gradlew build` — clean
- `gradlew runModelCheck` — 87 model assertions

**End-to-end, on a live server with a real client**

| Step | Observed |
|---|---|
| Quest + task created | `Added task ...: 10 x minecraft:oak_log for Player424` |
| Player picked up items | `Player424 completed Oak log` |
| Progress advanced | `0/10` → `10/10` |
| Quest completed | `Quest complete: Test Quest`, listing shows `1/1` |
| **Command reward ran** | `[Server] REWARD_FIRED_FOR_Player424` — `%player%` substituted |
| No double-grant | command executed **once**, not per tick |
| Survived restart | both quests reloaded at `10/10` and `5/5` |
| **No re-grant after restart** | zero reward events in the new run |
| **Sync on join** | client logged `Received 0 quest(s) from server` |
| **Live push** | after an admin command, `Received 1 quest(s) from server` |

**Not verified:** the book's appearance. The client is a desktop GUI window, which
cannot be driven or captured from here.

**Dev-server requirements:** `online-mode=false` in `run/server/server.properties`
(the dev client is offline and an online-mode server drops it as `Disconnected`).

## Admin UI

Press **K** to open the Admin Quest Manager (requires OP permission level 2+).

- **Browse Mode**:
  - Lists all server quests and their progress.
  - Click any quest to expand/collapse its tasks, displaying each task's item, required count, progress, assigned player name, and completion status.
  - Delete individual quests or tasks with the `[x]` button.
  - Click `+ New Quest` to open the quest creation studio.

- **Quest Creation Mode**:
  - **Left Pane - Item Picker**:
    - Vertical category tabs on the far left (Building, Colored, Natural, Functional, Redstone, Tools, Combat, Food, Ingredients, All).
    - Search box for filtering items/blocks live.
    - Scrollable grid of item icons with hover tooltips.
    - Count input box.
    - Assignee selector (cycles through online players or "Unassigned").
    - `+ Add` button to stage a task into the new quest.
  - **Right Pane - Quest Sheet**:
    - Quest name input field at top.
    - List of tasks added to the quest so far.
    - `[ Create Quest ]` to commit the quest and all its tasks to the server.
    - `[ Cancel ]` to return to Browse mode.

## Discord

Optional. A webhook URL turns the mod into a one-way feed of quest events.

```
/questbook discord url https://discord.com/api/webhooks/...
/questbook discord status      # confirm it took
/questbook discord test        # post a probe
```

Config lives in `world/questbook-discord.json`, is read fresh on every post
(no restart after editing), and is never written to the mod jar. The `off`
subcommand clears the URL; three event toggles (`announceQuests`,
`announceTasks`, `announceRewards`) control what is posted.

Posts are **fire-and-forget on a daemon thread** — a dead or slow webhook can
never stall the server tick, which is what keeps a chat-integration bug from
becoming a TPS bug.

## Not in this phase

- GUI editing of quest prerequisites (commands cover it)

## Research carried over

`.research/` holds the verified findings from the previous attempt, so the
mistakes are not repeated:

| File | Contains |
|---|---|
| `book-reference.md` | measured vanilla book palette, **single-page** verdict |
| `persistence-and-events.md` | 26.2 `SavedData`, lifecycle event signatures |
| `keybinds.md` | free keys (**L is taken by Advancements**) |
| `amcdb.md`, `amcdb-integration.md` | AMCDB mechanism |
