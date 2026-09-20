# Quest Book — Agent Notes

Mod for MC 26.2 / Fabric. Read `README.md` first, then `.research/` before
touching persistence or UI.

## Commands

```powershell
.\gradlew.bat build            # compile
.\gradlew.bat runServer        # dev server (needs run/server/eula.txt)
.\gradlew.bat runClient        # dev client, auto-joins localhost:25565
.\gradlew.bat runModelCheck    # 24 model assertions
.\gradlew.bat runUiCheck       # 23 book-layout assertions
```

Windows: always `.\gradlew.bat`. The JDK is pinned in `gradle.properties` via
`org.gradle.java.home`; it must be JDK 25.

## Layout

```
src/main/java/com/questbook/
  QuestBook.java            entrypoint; registers commands + reward tick
  data/                     Task, Goal, QuestStore, Reward + QuestModelCheck
  storage/QuestSavedData    SavedData + Codec persistence
  tracking/                 ProgressTracker, RewardGranter, Quests
  command/QuestCommands     /questbook tree
  mixin/                    pickup + craft hooks (both required)
src/client/java/com/questbook/client/
  QuestBookClient.java      entrypoint; receives syncs
  QuestKeybinds.java        J opens player book, K opens admin manager
  ClientQuestState.java     this player's tasks, as sent
  ClientAdminState.java     all server goals + online players cache
  gui/QuestBookScreen.java  player parchment book
  gui/AdminQuestScreen.java admin goal manager and item picker
  gui/ItemCatalog.java      creative category tab and item search loader
```

## Traps already hit — do not rediscover

1. **MC 26.2 renamed `PersistentState` → `SavedData`.** There is no Fabric API
   wrapper. `SavedData` has no `save()`; mutate then `setDirty()`.
2. **`ServerPlayer.getServer()` does not exist in 26.2.** Use
   `player.level().getServer()`.
3. **`Commands.LEVEL_GAMEMASTERS` is a `PermissionCheck`, not an int.** For a
   full-permission source use `.withMaximumPermission(PermissionSet.ALL_PERMISSIONS)`.
4. **No Fabric API event exists for item pickup or craft output.** The two mixins
   are mandatory.
5. **Vanilla book is single-page** (`book.png` blits 192×192). A two-page spread
   does not exist and must be custom-drawn. See `.research/book-reference.md`.
6. **Key `L` is taken** by Advancements. Free letters: `J K M R U Y Z`.
7. **Saved data only hits disk on world save.** After a command, run
   `save-all flush` before inspecting `goals.dat`, or the file is stale.
8. **The dev client cannot log into an `online-mode=true` server.** The dev
   account is offline, so the server drops it as `Disconnected` and the client
   just says the login failed. Set `online-mode=false` in
   `run/server/server.properties` (this repo already does).
9. **`latest.log` rotates on server restart.** After a restart, grep the new
   file — old reward/progress lines are in the archived log, not `latest.log`.
10. **`Minecraft#screen` is not public in 26.2.** Screens must track their own
    open state (`isOpen()`) for a keybind to avoid stacking copies.
11. **The dev client gets a RANDOM username each launch** unless
    `programArgs "--username", "DevTester"` is set in `build.gradle` (it is).
    Without it, every restart is a different player, so tasks assigned before the
    restart are gone and the book looks empty for no visible reason. This cost
    most of a debugging session.
12. **Log both counts when receiving a sync.** Logging only the goal count hid a
    zero task count and made a data bug look like a rendering bug.
13. **`text()` colour is ARGB, not RGB.** A 6-digit literal like `0x000000` has
    alpha `0x00` and draws **completely invisibly** — the row is laid out and
    "drawn" correctly, nothing appears. Always write `0xFF000000`. Vanilla's own
    book text is `-16777216` = `0xFF000000`, for exactly this reason. This cost a
    long debugging session: logs said rows were drawing, screenshots said empty.
14. **`CustomPacketPayload` needs a `type()` override and an *instance* write
    method** — `codec(...)` takes `StreamMemberEncoder`, not a static writer.
    Also give the byte-decoder constructor a private static helper to read into,
    or `this(readCollection(...))` is ambiguous against the record's canonical
    constructor.

## Conventions

- Records for data; immutable, mutations return new values.
- No `System.out` — use `QuestBook.LOGGER`.
- Server is authoritative; the client never computes progress.
- Command editing is OP 2+ because `COMMAND` rewards can run server commands.
