# AMCDB Integration — Complete Mechanism (verified)

All from the published `amcdb-1.4.0.jar` and upstream source. Not guessed.

## The decisive code path

`DiscordPublisher.handleMessage` (outgoing MC → Discord):

```java
if (message instanceof ChatMessage && discordService.isChatChannelEnabled() && !isFiltered(message)) {
    ...sendChatMessage(...);
}
else if (message instanceof BroadcastMessage
         && !config.getDiscordIgnoreBroadcast()
         && discordService.isChatChannelEnabled()
         && !isFiltered(message)) {
    ...
}
```

**`BroadcastMessage` is relayed by default.** There is no opt-in config needed.
`getDiscordIgnoreBroadcast()` defaults to `false` (i.e. don't ignore).

## How a broadcast is produced

`InGameMessageHandler.handleGameMessage`:

```java
public void handleGameMessage(MinecraftServer server, Component message, boolean overlay) {
    if (minecraftService.checkAndConsumeRecentlyPublished(message.getString())) return; // skip AMCDB's own
    broker.publish(new BroadcastMessage(MINECRAFT_SOURCE_ID, formatter.toComponents(message)));
}
```

Registered via `ServerMessageEvents.GAME_MESSAGE`.

So: **anything that reaches `GAME_MESSAGE` becomes a Discord BroadcastMessage.**

This includes `server.getPlayerList().broadcastSystemMessage(component, false)`.

## The filter is Discord-side, not Minecraft-side

```java
private boolean isFiltered(InternalMessage message) {
    return config.getDiscordMessageFilterPattern().isPresent() &&
        config.getDiscordMessageFilterPattern().orElseThrow()
              .matcher(message.getUnformattedContents()).find()
        == config.getDiscordMessageFilterExclude();
}
```

- `getDiscordMessageFilterPattern()` — a regex applied to the message text
- `getDiscordMessageFilterExclude()` — toggles include/exclude semantics

**This is the key that solves the "don't want it in chat" problem the other way
round:** the filter controls what reaches **Discord**, not what reaches chat.

- `discordMessageFilterExclude = true` + pattern `^\[TQ\]`
  → **exclude** anything starting `[TQ]` from Discord
- `discordMessageFilterExclude = false` + pattern `^\[TQ\]`
  → **only** relay messages starting `[TQ]` to Discord

So an admin CAN configure AMCDB to relay only our tagged messages. That makes the
broadcast bridge clean for the Discord side.

## Addressing / sending from our mod

```java
if (FabricLoader.getInstance().isModLoaded("amcdb")) {
    server.getPlayerList().broadcastSystemMessage(
        Component.literal("[TQ] Goal complete: " + goalName), false);
}
```

`false` = not an overlay/actionbar.

## Alternatives to `broadcastSystemMessage` — CANNOT be used

| Method | Why not |
|---|---|
| `DiscordService.sendToChatChannel(String)` | **public**, but no way to get a `DiscordService` instance (private field) |
| `MessageBroker.publish(...)` | interface is public, instance is private |
| `sendToChatWebhook(...)` | public, same instance problem |

AMCDB's public class surface is exactly:
```java
public static final String MOD_ID;
public static final Logger LOGGER;
public void onInitialize();
```

The `DiscordService`/`MessageBroker` classes expose public *methods*, but
`AMCDB` holds its instances in **private fields with no accessor**. Unless
another mod uses reflection or a mixin into AMCDB, the broadcast path is the
only viable route.

## Reflection is possible but discouraged

A mixin/reflect could grab `AMCDB.broker`. That:

- breaks whenever AMCDB renames a field (no API contract)
- requires AMCDB present at compile time or reflection guards
- is exactly the kind of fragile coupling this project should avoid

**Do not do it.** The broadcast path is stable because it rides Fabric's own
`ServerMessageEvents.GAME_MESSAGE` contract.

## Remaining trade-off

The broadcast **still appears in in-game chat** for players. AMCDB's filter only
affects what goes to Discord.

If a message must be Discord-only:
- use our own `DiscordWebhook` (already written in team-quest), or
- accept the chat line, or
- prefix it `[TQ]` and tell admins to set `discordMessageFilterExclude = false`
  with pattern `^\[TQ\]` so *only* our messages relay.

## UNVERIFIED — needs a live server with AMCDB

1. That AMCDB actually relays our `broadcastSystemMessage` at runtime (read from
   source; not executed).
2. Whether `broadcastSystemMessage` reaches `GAME_MESSAGE` on 26.2 specifically
   (event wiring is 26.2-era Fabric; high confidence, not run).
3. Whether the `[TQ]` filter regex behaves as read.

These need one runtime test with AMCDB installed.
