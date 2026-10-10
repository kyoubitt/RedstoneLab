# RedstoneLab

Redstone meters and piston previews.
**Guide with pictures:** [RedstoneLab-guide.pdf](docs/RedstoneLab-guide.pdf)
Minecraft 1.21.11 · Fabric · by kyoubitt · [LGPL-3.0](LICENSE)

`t` = game tick (1/20 s). A repeater on delay 1 = 2t.

## Quick start

1. Press **M** while looking at a block (or `/rlab tool` and right-click it): it gets a meter.
2. Watch the timeline in the top left.
3. **Right-click a piston with an empty hand**: you see what it will push (like Pistorder).

## Meters

```
Order: A → 2t → B D
A ▮▮ 4 ▮▮ 4   clock · Observer · on 2t · off 4t
B ▮▮ 4 ▮▮ 4   Repeater · on 2t · off 4t
```
- **bar** = on (lamp lit, piston out, repeater output on); the number = how many ticks
- **thin line** = powered but not on yet (a repeater's delay, a piston waiting)
- **white line** = 0-tick pulse
- **Order** = which meters turn on first, counted from A. `A → 2t → B D`: B and D turn on 2 ticks after A.
- Numbers are exact: `6t`; `3t/5t` = it alternates 3t, 5t, 3t, 5t…; `2t/3t/3t` = that pattern
  repeats; `2t or 3t` = no pattern, those are the lengths seen.
- Droppers / dispensers / crafters: bar = fires (triggered). `Dropper · 27 Stone · fires every 4t`
- Hoppers: bar = locked. Chests, barrels…: bar = comparator signal above 0.
  `Chest · 27 Stone · signal 2 (3 at 83)` (`3 at 83` = the comparator gives 3 at 83 items)
- Comparators: `Comparator · subtract · back 15 · side 14 · out 1`
- Meters move with blocks pushed by pistons.

**Remove:** M again, or **Backspace** (the one you look at, else the newest). **R + M**: remove all.

**Look back:** **R + Left / R + Right** = 1 tick back / forward (the timeline freezes; back at
"now" it runs again). While frozen you get a list of everything that happened at your meters in
that tick, in order (repeater ticks, turns on, piston moves…), grouped by the part of the tick
(sub-tick order, like RSMM). Block and shape updates: turn on **Show event marks** in settings.

**Extras (commands):**
- `/rlab meter expect A 2 4`: A should be on 2t, off 4t. Wrong pulses turn red.
- `/rlab meter name A clock`: a name in front of A's text.
- `/rlab group <name>`: separate saved meter sets (**R + G** lists them).

No client mod? You get boss bars and `/rlab meter` in chat.

## Pistons

Like Pistorder, and it works **on any server** (only your client needs the mod):
**right-click a piston with an empty hand** to show what it will do, click it
again to hide it. Several can be shown at once. Sneaking does nothing. **P** hides them all
(changing dimension too).
- `✔ push 3`: numbers on the blocks = move order, `breaks` = destroyed
- `✖ blocked` (`stuck` marks the block) or `✖ too many` (`13th` marks the extra block)
- `⚡ QC` = powered only from above · `⚡ BUD` = moves on the next block update

It updates live while the piston works. "Where blocks land" boxes: Pistorder settings tab.

**Firing order:** show several pistons and each gets its place in the order they extend,
counted from the first one you showed:
- `#1`, then `#2 · +3t` = 3 ticks after #1, `#3 · same tick` = same tick as #2, but the game
  moved it after #2
- `own timing` = it runs at a different speed than #1

With RedstoneLab on the server (or singleplayer) the ticks are exact. On a server without it the
order is still exact, but the ticks are measured on your side, so lag can make them off by one.

## Settings

**R + C** or Mod Menu. Tabs: Meters, GUI, Pistorder, Hotkeys. Hotkeys can be combos: click one,
hold the keys (e.g. R, then Left), let go. Saved in `config/redstonelab.json`.

| Key | Does |
|---|---|
| M | meter on / off on the block you look at |
| Backspace | remove a meter |
| R + M | remove all meters |
| R + Space | freeze / unfreeze the timeline |
| R + Left / Right | 1 tick back / forward |
| P | hide piston previews |
| R + G | meter groups |
| R + C | settings |

## Commands

`/rlab` help · `tool` · `meter [x y z]` · `meter expect|name|remove …` · `group …` ·
`piston clear` · `piston landing <true|false>` · `clear` · `config` (ops).
Needs cheats/op; `/rlab config set permissionLevel 0` lets everyone use it.

## Install

**Download:** [latest release](https://github.com/kyoubitt/RedstoneLab/releases/latest) (`redstonelab-x.y.z.jar`, not `-sources`).
Fabric API + this jar. Nothing else (Mod Menu optional).
- Client only: piston previews work everywhere. Meters need the mod on the server (or singleplayer).
- Server only: players without the mod still get meters as boss bars / chat, and piston previews.
- Both: same version.

## Build and test

```
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build
```
Jar: `build/libs/`. Building needs JDK 25; the mod runs on Java 21.

- `./gradlew runGameTest`: 26 in-game tests that build redstone on a test server and check the
  mod against what the game really does (clocks, 0-tick pulses, pistons, QC, comparators,
  hoppers, tick order, 300 random piston setups vs the game's own push calculation, 8 random
  circuits where every meter must match the real block every tick, and 60 random observer-clock
  piston builds where every real piston move must match the preview shown right before it,
  and the firing order of 4 pistons on a clock).
- `xvfb-run ./gradlew runClientGameTest`: opens a real client, presses the real keys, right-clicks
  a piston, checks the timeline bars match the game, and checks for 240 ticks that the
  client-side piston previews on clock builds are the same as the server's.
