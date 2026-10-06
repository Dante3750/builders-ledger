# Builder's Ledger

A native Android app (Kotlin, Jetpack Compose, Room) that tracks what every builder, laboratory and helper in your Clash of Clans village is doing, warns you the moment one is free, and plans the next upgrades so no worker sits idle.

It is a fan-made tool. It does not touch the game: you paste the game's own **Data Export** text (or type values in), and everything stays on your device.

## What it does

| Screen | What you get |
|---|---|
| **Board** | Live countdowns for every running upgrade, grouped by worker type (builders, lab, pet house...). Idle workers are called out and show the next planned upgrade with a one-tap Start. Collect finished upgrades, cancel, edit. |
| **Planner** | A wishlist of upgrades (time, cost, priority). A scheduler orders them across your workers and shows a timeline, when each starts, and where a worker would sit idle waiting for resources. |
| **Resources** | Balance plus income per hour for each resource. Balances keep growing with income between visits, and the planner never starts something you cannot pay for. |
| **Insights** | How busy each worker type was, resources spent, upgrades finished, full history. |
| **Sync from export** | Paste or open the game's export. Running timers are read and merged into the board. Sync again any time: it updates instead of duplicating, and moves vanished timers to history. |
| **Settings** | Notifications (exact alarms, optional "finishing soon" reminder), planner patience, worker counts, village management, JSON backup and restore. |

Notifications survive reboot and app updates.

## How the pieces fit

```
domain/   pure Kotlin, no Android: models, planner, export parser, sync, analytics (unit tested)
app/
  data/     Room database, repository, DataStore settings, JSON backup
  notify/   AlarmManager scheduling, notification receivers, reschedule on boot
  ui/       Compose screens, navigation, ViewModel
```

### The planner

Within a worker type, higher priority goes first, then longer upgrades first (which keeps the finish time short). Upgrades pay their full cost when they start; resources grow linearly with your entered income. If the top choice would make a worker wait longer than your **patience** setting for resources, a cheaper lower-priority upgrade fills the gap. Commitments are made in chronological order, so spending by one worker type is always seen by the others. Anything that can never be afforded is reported with the reason instead of silently dropped.

### Why there is no built-in cost table

Upgrade costs and times are not in any official API, and taking them from the game's files is not allowed. So the app asks you for them, once per upgrade, from the numbers the game shows you. Names are not in the export either (it only has numeric ids), so you name each item once and the app remembers it.

## Open it

1. Android Studio Ladybug (2024.2) or newer, JDK 17+.
2. Open this folder. The Gradle wrapper jar is not included; Studio will use the properties file, or run `gradle wrapper --gradle-version 8.10.2` once.
3. Run the `app` configuration on a device or emulator (API 26+).

Change `applicationId` / `namespace` in `app/build.gradle.kts` before publishing.

### Tests

```bash
./gradlew :domain:test
```

## Status: read this first

- The **domain module** (planner, export parser, sync, analytics, time parsing) was compiled with the Kotlin 2.0.21 compiler and its **26 unit tests pass**.
- The **Android layers** (Room, notifications, Compose UI) could not be compiled where this was written (no Android SDK or Maven access). They were desk-checked and statically linted for bracket balance and missing imports, but **the first Gradle sync and build may surface small compile errors** (an import, a changed API signature). Expect minutes, not hours, to fix them.
- The export parser assumes the structure of the game's data export: top-level arrays of objects with a numeric `data` id, optional `lvl` and `cnt`, and `timer` in seconds, plus `tag` and `timestamp`. If your export differs, adjust `VillageImport.kt`; there are fixture-style tests in `ImportAndTimeTest.kt` to extend. Check one real export early.
- Upgrades created by a sync have no known start time, so their progress bars count from the sync.
- Resource storage caps are not modelled.

## Rules this app is built to respect

- Fan content only: no cheats, bots, automation, private servers, mods or reverse-engineering, and no game assets or Supercell trademarks.
- No blockchain or crypto features.
- No paid features. If you want to fund it, the policy allows ads and voluntary donations.
- Do not put "Clash" or Supercell names or logos in the app name, domain or store listing.
- The app shows the required notice: "This material is unofficial and is not endorsed by Supercell." Re-read Supercell's [Fan Content Policy](https://supercell.com/en/fan-content-policy/) before publishing, as it can change.

## Ideas for v2

- Cross-check levels against the official API through a small backend proxy (API keys are tied to an IP address).
- Home-screen widget (Glance) showing the next free worker.
- Optional AI advisor: "what should I upgrade next?" from your village and goals.
- Share and import label packs so friends do not name the same ids twice.
