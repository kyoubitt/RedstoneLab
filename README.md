# RedstoneLab

Redstone meters and piston previews, inspired by
[Redstone Multimeter](https://modrinth.com/mod/redstone-multimeter) and
[Pistorder](https://modrinth.com/mod/pistorder).
Minecraft 1.21.11 · Fabric · by kyoubitt · [LGPL-3.0](LICENSE)

`t` = game tick (1/20 s). A repeater on delay 1 = 2t.

## Quick start

1. Press **M** while looking at a block (or `/rlab tool` and right-click it): it gets a meter.
2. Watch the timeline in the top left.
3. **Look at a piston** (anything in your hand): you see what it will push.

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
- Numbers are exact: `6t`, `5t/7t` when it alternates, `7t (varies)` when it's irregular.
- Hoppers, droppers, chests: `Dropper · 27 Stone + 4 Redstone · signal 2 (3 at 83) · fires every 4t`
  (`3 at 83` = the comparator gives 3 at 83 items)
- Comparators: `Comparator · subtract · back 15 · side 14 · out 1`
- Meters move with blocks pushed by pistons.

**Remove:** M again, or **Backspace** (the one you look at, else the newest). **R + M**: remove all.

**Look back:** **R + Left / R + Right** = 1 tick back / forward (the timeline freezes; back at
"now" it runs again). While frozen you get a list of everything that happened at your meters in
that tick, in order (repeater ticks, turns on, piston moves…), grouped by the part of the tick.

**Extras (commands):**
- `/rlab meter expect A 2 4`: A should be on 2t, off 4t. Wrong pulses turn red.
- `/rlab meter name A clock`: a name in front of A's text.
- `/rlab group <name>`: separate saved meter sets (**R + G** lists them).

No client mod? You get boss bars and `/rlab meter` in chat.

## Pistons

Look at a piston, with anything in your hand. It shows:
- `✔ push 3`: numbers on the blocks = move order, `breaks` = destroyed
- `✖ blocked` (`stuck` marks the block) or `✖ too many` (`13th` marks the extra block)
- `⚡ QC` = powered only from above · `⚡ BUD` = moves on the next block update

It stays while you look at the piston or its blocks, and 2 seconds after.
Firing order of several pistons: put meters on them, the **Order** line shows it.
Turn it off (or turn on "where blocks land" boxes) in the **Pistorder** settings tab.

## Settings

**R + C** or Mod Menu. Tabs: Meters, GUI, Pistorder, Hotkeys.

## Commands

`/rlab` help · `tool` · `meter [x y z]` · `meter expect|name|remove …` · `group …` ·
`piston preview|landing <true|false>` · `clear` · `config` (ops).
Needs cheats/op; `/rlab config set permissionLevel 0` lets everyone use it.

## Install

Fabric API + this jar (+ MaLiLib for the timeline, Mod Menu optional). On a server,
players without the mod can still use it. Server and client need the same version.

## Build and test

```
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build
```
Jar: `build/libs/`. Building needs JDK 25; the mod runs on Java 21.

- `./gradlew runGameTest`: 24 in-game tests that build redstone on a test server and check the
  mod against what the game really does (clocks, 0-tick pulses, pistons, QC, comparators,
  hoppers, tick order, 300 random piston setups vs the game's own push calculation, and 8 random
  circuits where every meter must match the real block every tick).
- `xvfb-run ./gradlew runClientGameTest`: opens a real client, checks the timeline bars match the
  game and saves one screenshot per tick to `build/run/clientGameTest/screenshots` (demo video frames).
