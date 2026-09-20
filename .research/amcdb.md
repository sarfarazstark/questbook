# AMCDB Research — Verified

**Verified against the published artifact and upstream source, not the project page.**

## What AMCDB is

| Fact | Value | Source |
|---|---|---|
| Mod id | **`amcdb`** | `fabric.mod.json` inside `amcdb-1.4.0.jar` |
| Version | 1.4.0 | same |
| Licence | **MIT** | same |
| Loaders | fabric, quilt | Modrinth API |
| **MC 26.2** | **supported** | Modrinth API `game_versions` includes `26.2` and `26.3` |
| Updated | 2026-04-02 | Modrinth API |
| Source | github.com/0x4e49434f4c45/amcdb | Modrinth API |
| Bundles | **JDA 5.6.1**, okhttp, jackson | jar contents |

Note: the Modrinth project *page* only showed up to 1.21.11 (stale HTML).
The **API** is authoritative and lists 26.2. Always trust the API/artifact.

### Packaging quirk

The jar is a **multi-version bundle**: it contains nested per-MC jar
(`META-INF/jars/amcdb-26.1-1.4.0.jar`, etc.) plus shaded dependencies
(JDA, okhttp, jackson, kotlin-stdlib). It is not a normal library jar.

---

## THE KEY FINDING: AMCDB has NO public API

Verified by decompiling the shipped class:

```
public class network.parthenon.amcdb.AMCDB implements ModInitializer {
  public static final String MOD_ID;
  public static final Logger LOGGER;
  public AMCDB();
  public void onInitialize();
}
```

That is the **entire** public surface. Everything else is private:

- `broker`, `minecraftService`, `discordService`, `config` — all **private instance
  fields** created inside `onInitialize()`
- No static accessor, no service registry, no `api` package
- The `MessageBroker` interface and `BroadcastMessage` class are public *types*,
  but there is **no way to obtain an instance** of the broker

**Consequence: a mod cannot call AMCDB directly.** No `AmcdbApi.send(...)` exists.

---

## The integration path that DOES work (verified)

AMCDB subscribes to Fabric's server message events
(`src/main/java/network/parthenon/amcdb/minecraft/MinecraftService.java`):

```java
ServerMessageEvents.CHAT_MESSAGE.register(handler::handleChatMessage);
ServerMessageEvents.COMMAND_MESSAGE.register(handler::handleCommandMessage);
ServerMessageEvents.GAME_MESSAGE.register(handler::handleGameMessage);
```

And `handleGameMessage` republishes **any** broadcast as a Discord message:

```java
public void handleGameMessage(MinecraftServer server, Component message, boolean overlay) {
    if (minecraftService.checkAndConsumeRecentlyPublished(message.getString())) return; // skip own
    broker.publish(new BroadcastMessage(MINECRAFT_SOURCE_ID, formatter.toComponents(message)));
}
```

### So: broadcast in Minecraft → it appears in Discord

A mod posts a normal server broadcast:

```java
server.getPlayerList().broadcastSystemMessage(Component.literal("Goal complete: ..."), false);
```

AMCDB sees it via `GAME_MESSAGE` and relays it to the configured Discord channel.

### ⚠️ The constraint you must accept

That same broadcast **also appears in players' in-game chat**. There is no
"Discord-only" path without AMCDB's private broker. If you want Discord-only
notifications, you'd need your own webhook (team-quest already has one).

Also: AMCDB's `checkAndConsumeRecentlyPublished` guard prevents *AMCDB's own*
messages from looping, and only applies to messages AMCDB published — not ours.

---

## Detection — for the optional toggle you asked for

```java
FabricLoader.getInstance().isModLoaded("amcdb")
```

The id `amcdb` is confirmed from the shipped `fabric.mod.json`.
Gate the Discord toggle on this, as you proposed — no hard dependency.

Note: the *source* `fabric.mod.json` on GitHub shows `id: amcdb-26_1` (a
template substitution artifact). The **published jar** says `amcdb`. Trust the jar.

---

## Recommendation

**Use the broadcast bridge, gated on `isModLoaded("amcdb")`.**

| | Broadcast bridge (AMCDB) | Own webhook |
|---|---|---|
| Extra deps | none | none (JDK HttpClient) |
| Config burden | none — AMCDB already set up | user must paste a URL |
| Appears in MC chat | **yes** | no |
| Rate limits | AMCDB's problem | yours |
| Works if AMCDB absent | n/a | yes |

Sensible design: **prefer AMCDB when present** (zero config for the user), and
keep the existing `DiscordWebhook` as the standalone fallback. That is exactly
the toggle you described, and both paths already exist.

Caveat: I have **not** verified at runtime that AMCDB relays arbitrary
`broadcastSystemMessage` calls — this is read from its source and event
subscriptions. Runtime confirmation needs AMCDB installed on the server.
