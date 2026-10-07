# Builder's Ledger

[![CI](https://github.com/Dante3750/builders-ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/Dante3750/builders-ledger/actions/workflows/ci.yml)

A native Android app (Kotlin, Jetpack Compose, Room) that tracks what every builder, laboratory and helper in your Clash of Clans village is doing, warns you the moment one is free, and plans the next upgrades so no worker sits idle.

It is a fan-made tool. It does not touch the game: you paste the game's own **Data Export** text (or type values in). Your village data stays on your device. The one network feature is an optional, off-by-default progress sync from the official developer API (see [Progress from the official API](#progress-from-the-official-api)); without it the app never goes online.

## What it does

| Screen | What you get |
|---|---|
| **Board** | Live countdowns for every running upgrade, grouped by worker type (builders, lab, pet house...). Idle workers are called out and show the next planned upgrade with a one-tap Start. Collect finished upgrades, cancel, edit. |
| **Planner** | A wishlist of upgrades (time, cost, priority, optional finish-by deadline). A scheduler orders them across your workers and shows a timeline, when each starts, where a worker would sit idle waiting for resources, and a plain-language "why now / why this order" for every entry. Impossible or missed deadlines and storage-cap warnings are called out. |
| **Resources** | Balance, income per hour and an optional storage cap for each resource. Balances keep growing with income between visits (up to the cap), and the planner never starts something you cannot pay for. |
| **Insights** | Toggle between *Activity* (weekly summary, how busy each worker type was, resources spent, upgrades finished, full history) and *Progress* (optional levels-to-max from the official API). |
| **Sync from export** | Paste or open the game's export. Running timers are read and merged into the board. Sync again any time: it updates instead of duplicating, and moves vanished timers to history. |
| **Settings** | Optional official-API tag, key and base URL, notifications (exact alarms, optional "finishing soon" reminder), planner patience, worker counts, village management, JSON backup and restore. |

Notifications survive reboot and app updates.

## Progress from the official API

An optional extra: the app can read your troop, spell, hero, hero-equipment and siege levels from the game's official developer API and show how close each is to its maximum (overall and per category, home village or builder base, plus a "closest to max" list). It also updates the current village's Town Hall from the response. It works without this; the export-based Board, Planner and Resources never depend on it.

### Setup

1. Create an account at [developer.clashofclans.com](https://developer.clashofclans.com) and create a key. **A key is bound to the IP addresses you list when creating it.**
2. Decide where the request comes from:
   - **Official host (default, `https://api.clashofclans.com/v1`)** only works if the phone's public IP is the one you registered. On mobile data or changing Wi-Fi it usually is not, and the server answers 403.
   - **A proxy.** Many people point the base URL at a community proxy (for example `https://cocproxy.royaleapi.dev/v1`; you must register the key for that proxy's IP address, see its own instructions) or at a small proxy they run themselves. **Trade-off:** the proxy receives your key with every request, so only use one you trust.
3. In the app: Settings > *Progress from official API*, enter your player tag (the app fixes case, a missing `#` and the letter O for zero), the key, and the base URL (empty = official host). Save.
4. Open Insights > *Progress* and tap *Sync now* or pull down.

Only `https://` base URLs are accepted, so the key is never sent in clear text. Redirects are not followed.

### What it can and cannot show

- Can: troop, spell, hero, hero-equipment and siege levels with their maximum for your Town Hall / Builder Hall, Town Hall and Builder Hall level, last-synced time.
- Cannot: buildings, upgrade timers, resources or builder counts. The API does not provide them, so keep using the export for those. Super-troop boost entries mirror base troops and are not counted in the percentages.
- The last response is cached on the device, so the screen keeps working offline and when the API is down.

### Privacy

This is the **only** network feature. The tag, the key and the cached response stay on your device; the app sends the request only to the base URL you configured, only when you sync, and has no analytics or other servers. The key is stored in the app's private DataStore **without encryption** (the app has `allowBackup` off, but a rooted device could read it). Use a key you can revoke, and revoke it if you lose the phone.

### Terms

Follow Supercell's [API Terms of Use](https://developer.clashofclans.com/#/documentation) and [Fan Content Policy](https://supercell.com/en/fan-content-policy/): respect the rate limits (the app only fetches when you ask, never in the background; a 429 is reported, not retried), do not scrape or bulk-collect other players, and keep the notice that this material is unofficial and not endorsed by Supercell.

### Not verified against the live API

The response parser follows the documented shape from memory and is deliberately tolerant (every field optional, unknown fields ignored, odd types read as absent). It is tested only against hand-written fixtures. **No real API call has been made while building this**, so check one real sync early and report any mismatch (for example a category that stays empty).

## How the pieces fit

```
domain/   pure Kotlin, no Android: models, planner, export parser, sync, analytics, official-API parser (unit tested)
app/
  data/     Room database, repository, DataStore settings, JSON backup
  notify/   AlarmManager scheduling, notification receivers, reschedule on boot
  ui/       Compose screens, navigation, ViewModel
```

### The planner

Within a worker type, higher priority goes first, then longer upgrades first (which keeps the finish time short). Upgrades pay their full cost when they start; resources grow linearly with your entered income. If the top choice would make a worker wait longer than your **patience** setting for resources, a cheaper lower-priority upgrade fills the gap. Commitments are made in chronological order, so spending by one worker type is always seen by the others. Anything that can never be afforded is reported with the reason instead of silently dropped.

**Storage caps.** If you enter a cap for a resource, income stops accruing while the balance sits at the cap. The planner and the Board then warn "cap reached at <time>: spend or lose income", with an estimate of what would be lost before your next planned spend. An upgrade that costs more than the cap is reported as impossible.

**Deadlines.** Give a wishlist item a "finish within" time. At equal priority the earlier deadline goes first, and a deadline that is close but reachable moves the item to the front. If the deadline cannot be met even starting right now, or the plan finishes the item late, the Planner says by how much.

### Why there is no built-in cost table

Upgrade costs and times are not in any official API, and taking them from the game's files is not allowed. So the app asks you for them, once per upgrade, from the numbers the game shows you. Names are not in the export either (it only has numeric ids), so you name each item once and the app remembers it.

## Get the app

**Download the debug APK from the latest Actions run:** open the [CI workflow runs](https://github.com/Dante3750/builders-ledger/actions/workflows/ci.yml), pick the newest green run and download the `builders-ledger-debug-apk` artifact (GitHub asks you to sign in to download artifacts). Unzip it, copy the APK to your phone and install it (you will need to allow installs from your file manager). It is a debug build, signed with a debug key, and meant for trying the app, not for the Play Store.

### Build it yourself

1. Android Studio Ladybug (2024.2) or newer, JDK 17+.
2. Open this folder. The Gradle wrapper is included (`./gradlew`).
3. Run the `app` configuration on a device or emulator (API 26+), or `./gradlew :app:assembleDebug`.

Change `applicationId` / `namespace` in `app/build.gradle.kts` before publishing.

### Tests

```bash
./gradlew :domain:test
```

## Status

- **CI-verified build.** Every push runs `.github/workflows/ci.yml` on GitHub Actions: the 98 domain unit tests, `:app:assembleDebug` (which compiles the Room, notification and Compose code for real) and `:app:lintDebug` (reported, not blocking). The badge above shows the state of `main`. If a build fails, error lines are written into the job annotations and an artifact with the full log is uploaded.
- **Not yet verified on a device.** The code compiles and the domain logic is unit tested, but nobody has run the UI on a phone for this version. Expect rough edges in layout and flows, and please report them.
- The export parser assumes the structure of the game's data export: top-level arrays of objects with a numeric `data` id, optional `lvl` and `cnt`, and `timer` in seconds, plus `tag` and `timestamp`. It tolerates missing or unknown fields, extra top-level keys, numbers written as strings, a wrapper object, code fences around the text, and timestamps in seconds, milliseconds or microseconds. Check one real export early; fixture tests in `domain/src/test` are easy to extend.
- Upgrades created by a sync have no known start time, so their progress bars count from the sync.
- Storage caps are per resource and manual: the app does not know your storages' real capacity until you enter it.
- Database schema is version 2 (caps and deadlines); an existing v1 database migrates automatically, and old backup files still restore.

## Rules this app is built to respect

- Fan content only: no cheats, bots, automation, private servers, mods or reverse-engineering, and no game assets or Supercell trademarks.
- No blockchain or crypto features.
- No paid features. If you want to fund it, the policy allows ads and voluntary donations.
- Do not put "Clash" or Supercell names or logos in the app name, domain or store listing.
- The app shows the required notice: "This material is unofficial and is not endorsed by Supercell." Re-read Supercell's [Fan Content Policy](https://supercell.com/en/fan-content-policy/) before publishing, as it can change.

## Ideas for v2

- A tiny self-hostable proxy recipe so the official API works from a phone without trusting a third party.
- Home-screen widget (Glance) showing the next free worker.
- Optional AI advisor: "what should I upgrade next?" from your village and goals.
- Share and import label packs so friends do not name the same ids twice.
