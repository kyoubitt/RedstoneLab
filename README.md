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

**M** on a block = meter. **M** again or **Backspace** removes it. **R + M** removes all.

```
Order: A → 2t → B D
A ▮▮ 4 ▮▮ 4   clock · Observer · on 2t · off 4t
B ▮▮ 4 ▮▮ 4   Repeater · on 2t · off 4t
```
- **bar** = on, number = ticks · **thin line** = powered, not on yet · **white line** = 0-tick
- **Order**: who turns on first, from A. `A → 2t → B D` = B and D 2t after A.
- `6t` exact · `3t/5t` alternates · `2t/3t/3t` pattern · `2t or 3t` no pattern
- Dropper: bar = fires · Hopper: bar = locked · Chest: bar = signal > 0, `(3 at 83)` = signal 3 at 83 items
- Repeater `delay 4t · locked` · Torch `burnt out` · Block `strong 15` (lights dust next to it) / `weak 13`
- Meters follow blocks moved by pistons.

**Up** freezes the game (`/tick`, needs op); Up again steps 1 tick (hold to repeat). **Down** runs it.
**R + Backspace / Enter**: 1 tick back / forward (freezes; at now it runs again). Frozen = list of
what happened that tick, in sub-tick order. Block / shape updates: **Show event marks**.

`/rlab meter expect A 2 4` (wrong pulses turn red) · `/rlab meter name A clock` ·
`/rlab group <name>` (**R + G** lists)

## Pistons

**Right-click a piston with an empty hand** to show what it does next. Again hides it. **P** hides all.
- `✔ push 3`: numbers = move order · `breaks` = destroyed
- `✖ blocked` (`stuck`) · `✖ too many` (`13th`)
- `⚡ QC` = powered from above · `⚡ BUD` = moves on next update
- Several shown: `#1`, `#2 · +3t`, `#3 · same tick` = firing order, from the first one shown.
  Exact ticks with the mod on the server; otherwise lag can shift them by 1.

## Keys

| Key | Does |
|---|---|
| M | meter on / off |
| Backspace | remove a meter |
| R + M | remove all |
| Up | freeze the game; frozen: step 1 tick (`/tick`, op) |
| Down | unfreeze the game |
| R + Space | freeze / unfreeze the timeline |
| R + Backspace / Enter | timeline 1 tick back / forward |
| P | hide piston previews |
| R + G | meter groups |
| R + C | settings (also Mod Menu) |

Change keys in settings: click one, hold the keys, let go. **Esc** unbinds.

## Commands

`/rlab` help · `tool` · `meter …` · `group …` · `piston …` · `clear` · `config` (ops).
Needs op; `/rlab config set permissionLevel 0` lets everyone use it.

## Build

`./gradlew build` (JDK 25; the mod runs on Java 21). Jar in `build/libs/`.
- `./gradlew runGameTest`: 28 tests that check the mod against the real game (clocks, pistons,
  QC, comparators, hoppers, sub-tick order, random circuits and piston builds).
- `xvfb-run ./gradlew runClientGameTest`: real client, real keys and clicks.
