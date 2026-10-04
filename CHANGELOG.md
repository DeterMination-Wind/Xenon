# Changelog

All notable changes to Xenon will be documented in this file.

## [1.14.0] — 2026-10-04

### Added
- MDTBBS multiplayer: create or join rooms, join codes, friend invites, `xenon://join?intent=` links and an official relay data plane, with a one-click copy for the join code.
- Netplay page public server directory (mirror-backed) and EasyTier six-digit P2P rooms.
- GitHub account settings: paste a token to raise the API limit from 60 to 5000 requests per hour and see the remaining quota.
- Resumable downloads with pause/resume and cancel cleanup.
- Version lists tag eras and Java requirements and fall back to bundled offline snapshots; release notes render tables and images.
- Cross-instance data migration, both as a dialog and as an install-wizard seeding option.
- Automatic heap sizing from the enabled mod set, shown in the instance health check.
- Per-instance playtime statistics and a health check report.
- Trash-first deletion for saves, mods, schematics, maps and whole instances.
- Portable data mode through a `Xenon.portable` marker next to the launcher.

### Fixed
- EasyTier archives whose executables live in a nested folder no longer fail with "did not contain easytier-core".
- MDTBBS sessions are serialised: starting a new room leaves the previous one first, cancelled attempts clean up the session they created, stale relay failures report stable error codes, and a join code copy button was added.

### Changed
- Rewrote the user guide (`docs/USAGE.md`) for the current workflows and updated the README sections about netplay and instance tools.

See the [bilingual release notes](release-notes/v1.14.0.md) for details.

## [1.13.1] — 2026-10-02

### Fixed
- The "Last log" button on the Logs tab and "Open log file" in the abnormal-exit dialog now open the best available log instead of reporting a missing `last_log.txt`: the game's own log, then the launcher-captured process output, then the newest crash report.
- Every launch keeps its process output under `<instance folder>/logs/` (latest 10 files), so early crashes before Mindustry writes its own log stay diagnosable.
- The Logs tab follows the effective launch data directory, including save-archive runtimes.
- New game folders and profiles default to the official Mindustry data directory (`%APPDATA%\Mindustry` on Windows), and the remaining `.minecraft` example paths were removed.

See the [bilingual release notes](release-notes/v1.13.1.md) for details.

## [1.13.0] — 2026-10-02

### Added
- Native MDTBBS community page: browse categories and threads, search, read formatted posts and replies, post new threads, reply, like, bookmark and view notifications without opening a browser.
- MindAuth sign-in from the launcher with the system browser and a local callback; author names open their forum profile.
- Resource center tab for MDTBBS blueprints and maps: search, previews, one-click install into the selected instance, and uploads that enter forum moderation.
- Cloud saves for every instance: slots, history, upload, restore with a local backup, pinning and quota display.
- Friends and multiplayer helpers: presence and "playing now" activity, a friends and invites dialog, and `xenon://join?intent=` link handling.

### Fixed
- The shadow jar build now verifies the Kotlin/Okio classes OkHttp needs, so dependency minimization cannot silently drop them.
- Hiding a JFoenix popup twice no longer crashes on a JavaFX listener.
- Deleted or corrupted instances no longer throw while the game list renders or the game launches; they show a clear message instead.
- GitHub release responses that are not a release array now produce a readable error instead of a Gson crash.

### Changed
- Resource previews are limited to three concurrent downloads.

See the [bilingual release notes](release-notes/v1.13.0.md) for details.

## [1.12.1] — 2026-09-26

### Changed
- Mindustry downloads check the MDT File manifest first and use `file.mdtbbs.cn` when that asset is listed.
- Download clients use HTTP/1.1. A handshake reset retries once, rewriting a legacy `d.file.mdtbbs.cn` host to `file.mdtbbs.cn`, then falls back to the GitHub release asset through the existing mirror race.
- The mainland vanilla version list reads `https://file.mdtbbs.cn/api/v1/mindustry/manifest.json` instead of scraping the file-station HTML.

See the [bilingual release notes](release-notes/v1.12.1.md) for details.

## [1.12.0] — 2026-09-18

### Fixed
- Mindustry instances are now stored in the selected game folder (Profile) instead of falling back to the shared `%APPDATA%/Xenon` repository, so a custom folder actually owns the instances imported into it.
- Imported external installations only reuse their data directory when it lives inside the installation folder (Steam/portable); otherwise the instance is isolated instead of silently binding to `%APPDATA%/Mindustry`.
- Renaming an instance moves the directory, rewrites `version.json` and renames the jar; the manifest no longer keeps the old id/jar path, and instances broken that way are repaired automatically.
- HMCL's Minecraft version scan no longer renames Mindustry `version.json` files to `<id>.json`.

See the [bilingual release notes](release-notes/v1.12.0.md) for details.

## [1.11.0] — 2026-08-18

### Added
- Mainland China users now receive the original Mindustry builds from the MDTbbs mirror by default, with Windows, Linux, and macOS archive support.
- Mindustry archives can use bounded HTTP range-based segmented downloads, with an automatic single-stream fallback when the mirror does not support ranges.
- MDTbbs downloads include a `?reques=Xenon+<version>` attribution parameter.

See the [bilingual release notes](release-notes/v1.11.0.md) for details.

## [1.10.0] — 2026-08-17

### Fixed
- Launching a game could crash with a `NullPointerException` when no usable Java runtime could be resolved. The launcher now falls back to its own Java environment, or shows a clear error message instead of crashing.

See the [bilingual release notes](release-notes/v1.10.0.md) for details.

## [1.9.0] — 2026-08-02

See the [bilingual release notes](release-notes/v1.9.0.md) for the complete list of changes.

## [1.8.0] — 2026-07-10

### Added
- Update version root to 1.8.

## [0.1.0] — 2026-05-17

The first public release. Forked from HMCL, Mindustry support layered on top.

### Added — Foundations (W1)
- Repackage `org.jackhuang.hmcl` → `determination.xenon` (4699 occurrences in 848 files).
- Rename modules: `HMCL` → `Xenon`, `HMCLCore` → `XenonCore`, `HMCLBoot` → `XenonBoot`.
- Brand metadata: `Metadata.NAME` = "Xenon", upstream-repo constants for all 5 variants.
- Per-OS data directory: `%APPDATA%\Xenon` / `~/.xenon` / `~/Library/Application Support/Xenon`.
- License header rewritten across 449 files to GPLv3 + Xenon contributors.
- Default theme color → Mindustry orange `#ffa44a`.

### Added — Local launch (W2)
- `MindustryVersion` POJO + `XenonGameRepository` reading `versions/<id>/version.json`.
- `XenonLauncher`: `ProcessBuilder` + `-Dmindustry.data.dir=<.data>`, macOS auto-`-XstartOnFirstThread`.
- `MindustryJavaPicker`: reuses HMCL `JavaManager`, picks lowest JDK ≥ `javaReq`.
- `MindustryImportFlow`: sidebar entry "Import Mindustry jar" → FileChooser → register → launch.

### Added — Variant downloads (W3 / W4)
- Generic `GitHubReleaseClient` with ETag cache + `MirrorSelector` (4 China mirrors + upstream fallback).
- `VanillaVersionList`, `BeVersionList`, `MindustryXVersionList`, `CnArcVersionList`, `FooVersionList`.
- 4-step install wizard: variant → version → naming/isolation → pre-install mods.

### Added — Mods (W5)
- `MindustryLocalMod` + `MindustryModManager`: scan / enable (`.disabled` rename) / disable / delete / install.
- `mod.hjson` parser tolerating bare keys, comments, trailing commas.
- `MindustryModsIndexRepository` against `Anuken/mindustry-mods/master/mods.json`.
- `GitHubDirectInstaller`: input owner/repo → install latest release.

### Added — Saves / schematics / crashes (W6)
- `SaveFileReader` for `.msav` v7 header (mapName / build / wave / playtime / mode).
- `SaveBackupService`: list / backup / restore / rename / delete / export-zip / cross-version copy.
- `SchematicReader` + `SchematicManager` for `.msch` (header tags, import/export, base64).
- `MindustryCrashAnalyzer` + `IssueTemplateBuilder`: classify frames (JVM / MOD / NATIVE), build per-variant issue URL.

### Added — Servers (W7)
- `ServerInstance` / `ServerInstanceManager` / `ServerProcess` for multi-instance management.
- `ServerVersionList` + `ServerJarInstaller` for vanilla / BE / MindustryX server jars.
- `ServerConsoleSession` with `[I]/[W]/[E]` log level parsing and 200-entry command history.
- `ServerAutoRestartPolicy` + `ServerSessionRunner` (graceful stop, restart loop).
- `ServerConfigManager` (auto reload-config), `ServerMapPool`, `ServerModsBridge`.
- `PortChecker` for port-conflict detection.
- `ScriptAgentInstaller` + `ScriptAgentModuleManager` for kts module enable/disable/hot-reload.

### Added — Polish (W8)
- Update endpoint adapted to GitHub Release JSON (`tag_name` + `assets[*].browser_download_url`).
- jpackage tasks: `packageWindows` / `packageMac` / `packageLinuxDeb` / `packageLinuxAppImage` / `packagePortable` / `packageAll`.
- README, CHANGELOG, USAGE docs (this file).

### Removed
- Microsoft / Mojang / Authlib login pipelines.
- Forge / Fabric / Quilt / NeoForge / OptiFine / LiteLoader installers.
- Resourcepacks / Datapacks (Mindustry uses mods instead).
- HMCL's Terracotta multiplayer relay (replaced by Mindustry server management).
- Curseforge / Modrinth / mcbbs / multimc modpack flavours (replaced by `MindustryModsIndexRepository` + `GitHubDirectInstaller`).

### Known issues
- jpackage cross-platform CI not yet wired; `packageAll` runs only the host's target.
- Server console UI (`ServerConsolePage`), mod list UI (`MindustryModListPage`), crash list UI (`MindustryCrashListPage`), ScriptAgent page (`ScriptAgentPage`) — backends complete, JavaFX surfaces in progress (delegated to follow-up workers).
- Dependency-graph sanity check on mod install: shows warning, does not auto-resolve transitive deps.
- HMCL legacy "account" classes still present as type stubs; the visible UI now points at the UUID manager.

### Migration from HMCL
- HMCL settings file `hmcl.json` → `xenon.json`. The first launch creates a fresh empty config.
- HMCL launcher signing key (`META-INF/hmcl_signature`) → `META-INF/xenon_signature`.
- Environment variables `HMCL_*` → `XENON_*`.

[0.1.0]: https://github.com/DeterMination-Wind/Xenon/releases/tag/v0.1.0
[1.8.0]: https://github.com/DeterMination-Wind/Xenon/releases/tag/v1.8.0
[1.9.0]: https://github.com/DeterMination-Wind/Xenon/releases/tag/v1.9.0
[1.11.0]: https://github.com/DeterMination-Wind/Xenon/releases/tag/v1.11.0
[1.12.0]: https://github.com/DeterMination-Wind/Xenon/releases/tag/v1.12.0
[1.12.1]: https://github.com/DeterMination-Wind/Xenon/releases/tag/v1.12.1
