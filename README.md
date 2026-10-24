# RedstoneLab

Redstone meters and piston previews.
Minecraft 1.21.11 · Fabric · by kyoubitt · [LGPL-3.0](LICENSE)

**Download:** [latest release](https://github.com/kyoubitt/RedstoneLab/releases/latest) (`redstonelab-x.y.z.jar`, not `-sources`) · **Guide:** [PDF with pictures](docs/RedstoneLab-guide.pdf)

`t` = game tick. Repeater delay 1 = 2t.

## Install

Fabric API + this jar. Mod Menu optional.

| On | You get |
|---|---|
| Client only | Piston previews, any server |
| Client + server | Everything, exact ticks |
| Server only | Meters as boss bars / chat, piston previews |

Client and server need the same version.

## Meters

**M** on a block = meter, **M** again removes it. **R + M** removes all.

```
Order: A → 2t → B D
A ▮▮ 4 ▮▮ 4   clock · Observer · on 2t · off 4t
B ▮▮ 4 ▮▮ 4   Repeater · on 2t · off 4t
```
- **bar** = on, number = ticks · **thin line** = powered, not on yet · **white line** = 0-tick
- **Order**: who turns on first, from A (or the first meter still running). `A → 2t → B D` = B and D 2t after A,
  in the same tick: B first (same-tick meters are listed in the order the game ran them).
- `6t` always the same · `2-3t` varies between 2t and 3t
- Dropper: bar = fires · Hopper: bar = locked, `in / out every 8t` · Chest: bar = signal > 0
- `signal 3 (4 at 741)` = what a comparator reads; signal 4 at 741 items (shown when exact). Double chests count both halves; `blocked` = chest can't open, reads 0
- Repeater `delay 4t · locked` · Torch `burnt out` · Target, daylight, sculk, weighted plate `power 7` · Composter, cauldron, cake, lectern… `signal 3`
  (dust power: texture packs show it on the dust)
- Meters follow blocks moved by pistons.

**R + Backspace / Enter**: only the timeline goes 1 tick back / forward; the game keeps running (title `[3t back]`,
at now it runs again). Stopped = list of what happened that tick, in sub-tick order. Block / shape updates:
**Show event marks**.

`/rlab meter expect A 2 4` (wrong pulses turn red) · `/rlab meter name A clock` ·
`/rlab group <name>` (**R + G** lists)

## Pistons

**Right-click a piston with an empty hand** to show what it does next. Again hides it. **P** hides all.
- `✔ push 3/12`: numbers = move order · `breaks` = destroyed
- `✖ blocked` (`stuck`) · `✖ too many` (`13th`)
- `⚡ QC` = powered from above · `⚡ BUD` = moves on next update
- Several shown: `#1`, `#2 · +3t`, `#3 · same tick` = firing order, from the first one shown.
  Only pistons firing close together (each within 1 s of the one before); pushed separately = no order.
  Exact ticks with the mod on the server; otherwise lag can shift them by 1.

## Keys

| Key | Does |
|---|---|
| M | meter on / off |
| R + M | remove all |
| R + Backspace / Enter | timeline only: 1 tick back / forward |
| P | hide piston previews |
| R + G | meter groups |
| R + C | settings (also Mod Menu) |

Settings (**R + C** or Mod Menu) by type: **Timeline** (what it shows), **GUI** (where, how big, outlines,
piston landing boxes), **Hotkeys** (click one,
hold the keys, let go; **Esc** = Unbound).

## Commands

`/rlab` help · `tool` · `meter …` · `group …` · `piston …` · `clear` · `config` (ops).
Needs op; `/rlab config set permissionLevel 0` lets everyone use it.

## Build

`./gradlew build` (JDK 25; the mod runs on Java 21). Jar in `build/libs/`.
- `./gradlew runGameTest`: 69 tests that check the mod against the real game, every command
  and the Meter Tool, groups saved / shared / reloaded, limits and permissions, and: clocks, pistons, QC,
  comparators, hoppers, sub-tick order, random circuits and piston builds, and 18 circuits built
  from the Minecraft Wiki's own schematics (hopper clocks, Etho hopper clock, subtraction clocks,
  fader pulser, observer clocks, piston clock, 3 T flip-flops, edge detectors, a flying machine,
  a hopper item sorter), a 2x2 piston door, a 4-bit binary counter and a full adder:
  each runs at the wiki's timings and every meter matches the game every tick.
- `xvfb-run ./gradlew runClientGameTest`: real client, real keys and clicks.
- Both also pass with Lithium (0.21.4) installed.
