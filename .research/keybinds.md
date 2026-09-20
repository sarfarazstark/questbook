# Keybind Availability — MC 26.2 (verified from bytecode)

Extracted from `Options.<init>` in `minecraft-clientonly-deobf-26.2.jar`.
Each `new KeyMapping(name, keycode, category)` was decoded to its GLFW code.

## ❌ L IS TAKEN — do not use it for the leaderboard

Two vanilla bindings use GLFW code **76 (L)**:

```
key.advancements    -> 76 (L)   [real gameplay binding, always active]
key.debug.profiling -> 76 (L)   [only while F3 held]
```

Evidence: `Options.<init>` line ~2903: `new KeyMapping("key.advancements", 76, Category.MISC)`

`key.advancements` opens the Advancements screen. Binding a mod key to L
**collides with a vanilla screen**. This is exactly the case you asked me to
check for.

## Full letter map (verified)

### Real gameplay bindings (always active — COLLIDE)

| Key | Binding |
|---|---|
| W | forward |
| A/S/D | left / back / right |
| E | inventory |
| Q | drop |
| F | swap offhand |
| **O** | **friends** ← our settings key already collides |
| **L** | **advancements** |
| X | load toolbar activator |

### Debug-only (fire ONLY while F3 is held — effectively free)

`A B C D G H I N P S T V` and `L`(profiling)

### ✅ Genuinely free letters

**J, K, M, R, U, Y, Z**

Of these, **J** (list) and **K** (editor) are already used by this mod.

---

## Recommendation for the leaderboard key

| Candidate | Verdict |
|---|---|
| `L` | ❌ taken by Advancements |
| `J` | ❌ we use it for the task book |
| `K` | ❌ we use it for the editor |
| **`R`** | ✅ free. Natural mnemonic ("Ranking") |
| **`M`** | ✅ free |
| **`U`** | ✅ free |
| **`Y`** | ✅ free |
| **`Z`** | ✅ free |

**Recommend `R`** for the leaderboard — free, memorable, and adjacent to the
existing J/K cluster so the mod's keys sit together.

---

## Separate issue found: our own keybind collides

`QuestKeybinds` binds the settings screen to `GLFW_KEY_O`, but **`O` is
`key.friends`** in 26.2. Two actions on one key. It still "works" because
Minecraft fires both, but it is a real conflict and should move.

Suggested: settings → `M`, keeping J/K/R for list/editor/leaderboard.

---

## Caveat

This is read from `Options.<init>` bytecode, which is authoritative for *default*
bindings. A user's own `options.txt` can rebind anything, and other mods may
claim more letters. Fabric's `KeyMappingHelper.registerKeyMapping` is the correct
registration path (already used by this mod), and it surfaces conflicts in the
in-game controls screen rather than crashing.
