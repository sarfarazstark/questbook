# Quest Book

A Fabric mod for Minecraft 26.2. Admins create **quests**, each a list of **tasks**
assigned to individual players ("collect 64 oak logs"). Progress tracks
automatically as players pick items up or craft them. Players view their tasks in
an in-game book; a leaderboard ranks contributors.

- **Mod id:** `questbook`
- **MC:** 26.2 · **Loader:** >= 0.19.5 · **Fabric API:** required (not bundled) · **Java:** 25

## Installation

1. Install Fabric Loader and the Fabric API for MC 26.2.
2. Drop the Quest Book jar into `mods/` on the **server and on every client**
   (the book GUI is client-side, so players need the jar too).

## For players

Press **J** to open your book: the quests you have tasks in, each with progress.
Quests you have no tasks in never appear.

Useful commands (no permission needed):

```
/questbook mine             your assigned tasks
/questbook top [limit]      leaderboard, 10 by default (1-100)
/questbook stats [player]   points, tasks and quests completed
/questbook list             all quests and tasks
/questbook tree             every quest and what it asks for
```

**Points:** 1 per completed task, 10 per completed quest. Counted from
completions, so editing or deleting a task never takes points away.

## For admins

Press **K** to open the Admin Quest Manager (OP level 2, or an editor grant —
the server decides, so editors without OP get in too):

- **Browse:** all quests with progress; expand a quest to see its tasks,
  reassign them, or delete quests/tasks with `[x]`.
- **Creation studio:** pick an item from the tabbed picker (search, count,
  assignee cycling through online players), stage tasks, name the quest, commit.

Or use commands. Everything that edits quests needs **OP level 2** or an editor
grant:

```
/questbook new <quest>                         create a quest
/questbook add <quest> <player> <item> <count> add a task, assigned
/questbook assign <quest> <task> <player>      reassign a task
/questbook delete <quest>                      remove a quest
```

A quest may be named or given by UUID, so duplicate names stay addressable.
A task takes an id prefix (the id printed beside it in `/questbook list`), an
item name, or the `"name <idprefix>"` form for duplicates.

A quest is complete when **every** task in it is complete. One task has exactly
one assignee; only the assignee's pickups and crafting advance it.

### Editor access

`/questbook editor add <player>` grants quest editing without the OP tag.
Editor management itself stays OP-only and can never be granted by an editor.

- `editor add` refuses players who already hold OP level 2 (the grant would be
  redundant, and it would survive a later `/deop`).
- `editor remove <player>` needs them online; `editor remove id <uuid>`
  works offline and accepts the short form `editor list` prints.
- `editor list` shows operators and grantees separately.

## Discord (optional)

A webhook URL turns the mod into a one-way feed of quest events:

```
/questbook discord url https://discord.com/api/webhooks/...
/questbook discord status      # confirm it took
/questbook discord test        # post a probe
/questbook discord off         # stop posting
```

Config lives in `config/questbook-discord.json`. It is read at startup and
cached — the `url`/`off` commands update it live, but the two event toggles
(`announceQuests`, on by default; `announceTasks`, off by default) need a
hand-edit plus a restart. Posts are asynchronous with short timeouts; a dead
webhook is logged and dropped, never retried, so it can never stall the tick.

## Data file

Quests, points and editor grants live in vanilla saved data at
`world/data/questbook/goals.dat` (key `questbook:goals`). It flushes on the
world save, so run `save-all flush` before inspecting it by hand.

## Building

```
.\gradlew.bat build            # compile
.\gradlew.bat runModelCheck    # 24 model assertions
```

Requires JDK 25 (pinned in `gradle.properties`).
