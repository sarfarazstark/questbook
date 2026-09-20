# Vanilla Book — Measured Reference

All values measured, not guessed. Source: `assets/minecraft/textures/gui/book.png`
extracted from `minecraft-clientonly-deobf-26.2.jar`, sampled with PIL.

## Texture facts

| Fact | Value |
|---|---|
| Texture file size | **256 × 256** |
| Visible book within it | **146 × 180**, at offset x=20, y=1 |
| Blit size on screen | **192 × 192** (vanilla scales it up) |
| Pages rendered | **ONE per screen** — no two-page variant exists in vanilla |

## Palette (hex, with pixel share in the texture)

| Role | Hex | Notes |
|---|---|---|
| Page highlight | `#FFFAEE` | 6.6% — brightest parchment |
| **Page base** | `#FDF7EA` | **the main parchment tone** |
| Page shade | `#F9EED0` | |
| Page inner shadow | `#F1E2B8` | |
| Page edge | `#D1BFA1` | |
| Spine ribbon | `#C3251D` / `#951A13` | red bookmark |
| Border mid | `#75321E` | |
| Border dark | `#652816` | most common border tone |
| Border darker | `#4C1A0B` | |
| Border outline | `#1C0F00` | outermost |

### Horizontal scan across the book (y = middle), left→right

```
x=20  #1C0F00   outer outline
x=21  #4C1A0B   dark border
x=22  #652816   border
x=23  #75321E   border mid
x=25  #652816   border inner
x=26  #D1BFA1   page edge
x=27  #F9EED0   page shade
x=28  #951A13   spine ribbon
x=29  #C3251D   spine ribbon bright
x=30  #F1E2B8   inner shadow
x=31  #F9EED0   page
x=34  #FCF3DD   page
x=53  #FFF9EC   page highlight
```

So a parchment panel is: **dark outline → 2px brown border → #D1BFA1 edge → parchment `#FDF7EA`**.
Total border thickness on the left is ~6px before the page starts.

## Text style

`BookViewScreen.PAGE_TEXT_STYLE` = `Style.EMPTY.withoutShadow().withColor(0xFF000000)`
— black, **no drop shadow**. Text starts at `top + 16`, wraps at width **114**,
left page x offset **36**, right page x offset **116**.

## Verdict on the two-page question — CONFIRMED SINGLE PAGE

Independent confirmation, three ways:
1. `book.png` blits at 192×192 (`blit(..., 192, 192, 256, 256)` in `BookViewScreen`).
2. No other book GUI texture exists in the jar (only block/item/entity book textures).
3. `BookViewScreen` renders `getPage(currentPage)` and text is laid out for one
   page width (114px).

**There is no two-page spread in vanilla. Any "open book" look must be custom-drawn.**

## Reproducing the look at a larger size

Since the book is only 192×192 and a 9-slice of a 146×180 sprite is awkward, the
practical options are:

1. **Solid fills in the measured palette** — draw the panel with `fill()` using the
   border→edge→parchment sequence above. Scales to any size, no texture, exact colours.
2. **Ship your own PNG** — a mod can include its own parchment texture at whatever size.
3. **Tile/extend** — repeat or stretch the existing sprite (seams on the gradient).

Option 1 is the reliable one: the palette above is enough to look like the book
without depending on a texture that was only ever designed for one page size.
