# Changelog

All notable changes to Xenon will be documented in this file.

## [2.0.0] — 2026-10-23

### Added
- MDTBBS multiplayer moved to API 1.7.0: orthogonal visibility/join-policy axes, host approval, a public lobby, realtime WebSocket events, the default join-intent launcher preference and a pseudo-LAN advertiser so guests pick the room from Mindustry's LAN list.
- Cloud saves gained the full conflict contract (`create_conflict_copy` / `force_replace_head` / head confirmation), `no_upload_required`, upload metadata with game/save/Mod/device fields, ETag and Last-Modified capture, slot renaming and snapshot deletion.
- Whole-instance cloud archives pack saves, maps, schematics, settings and restorable Mods, validate every zip entry, back up before restore, fill indexed Mods and run an opt-in pre-launch pull / post-exit push with a local sync baseline.
- A Mod development tab builds a project, deploys the newest artifact, watches sources with debounced single-flight rebuilds and restarts the game without triggering cloud sync or abnormal-exit dialogs.
- A headless CLI (`--list`, `--launch`, `--export`, `--get`, `--set`), standalone `.bat`/`.sh` launch scripts, and `xenon://launch` / `xenon://open` deep links.
- A recycle bin for instances and save archives, automatic save backups after sessions that reach a per-instance threshold, and single-level instance groups with a `group:` list filter.

### Fixed
- The third room preset no longer sends the removed `unlisted`/`open` values and can never 400 again; deprecated values are normalised before the request.
- The first peer heartbeat is sent immediately instead of after a full interval, removing a relay `PEER_UNAVAILABLE` race.
- The owner bridge retries the local game connection for eight seconds, so creating the room before starting the game works.

### Changed
- The competitor table was re-verified as of 2026-10-23 and now compares cloud archives, Mod development, the recycle bin, groups and the CLI.

See the [bilingual release notes](release-notes/v2.0.0.md) for details.

## [1.17.0] — 2026-10-19

### Added
- Cloud-save uploads show progress: the dialog replaces its body with a percentage bar and the tray menu mirrors the same status line, both cleared once the upload settles.
- Launching an instance whose selected Java executable no longer exists opens a guidance dialog that names the missing path. Its action jumps to Settings → Java management and opens the runtime download dialog, instead of surfacing the raw `CreateProcess error=2` failure.

### Fixed
- The Java picker skips registered runtimes whose executable disappeared and silently falls back to a working runtime, so a stale Java pool entry no longer blocks a launch.
- A cloud-save upload that fails during the PUT or the commit hands its server-side upload session back with a best-effort DELETE instead of leaving it behind; the original error is preserved when the cleanup request also fails.

### Changed
- The README competitor table was re-verified as of 2026-10-18: Copper's cloud saves and Windows file drag-out, the command line added in GXFQE/mindustry-launcher v1.3, and a new mobile / other-platform launcher list.

See the [bilingual release notes](release-notes/v1.17.0.md) for details.

## [1.16.0] — 2026-10-13

### Added
- Global download rate limiting for the whole Mindustry download chain: unlimited, 1 / 2 / 5 MB/s presets or a custom KiB/s rate with a 32 KB/s floor, applied live to racing mirrors, range segments and resumable streams through one shared token bucket.
- Mindustry download source tiers: prefer domestic, prefer official, domestic only and official only. The tier picks the version feed, gates every implicit fallback and is wired to the variant entries, so a variant that has no allowed feed is offered as a disabled card with the reason instead of failing later.
- Downloads are verified against the source manifest. The file station publishes a SHA-256 for every asset, so a mismatch now deletes the artifact, retries the same source once, then switches to the other source family, and finally reports the exact size or hash discrepancy. A pause or cancel never switches sides.
- Cloud saves are compared with the cloud once after startup, read-only, and files that differ are listed with both timestamps and sizes. A conflicting file can be resolved from a small prompt: overwrite local with the cloud copy after a backup, keep local and upload it, or decide later.
- Uploading a cloud save makes room in the storage quota first by dropping the oldest unpinned snapshots, never a pinned one and never the newest one, logging each irreversible deletion before it happens.

### Changed
- The competitor table in both READMEs was re-verified against each project's own repository (descriptions, licenses, last push and per-cell evidence) as of 2026-10-12, with new Java-management and download-source-policy rows and three newly found Windows-capable launchers.

See the [bilingual release notes](release-notes/v1.16.0.md) for details.

## [1.15.0] — 2026-10-11

### Added
- Offline accounts can import the Steam player UID: Xenon reads the local Steam client data (`loginusers.vdf`, `config.vdf`, the Mindustry app manifest and `userdata/`) and reproduces the exact UUID the Steam build uses.
- Mindustry instances can be duplicated together with their game data and player settings.
- Instances can view and edit every `settings.bin` entry by its original type, with automatic backups before saves and restore of the latest backup.
- The mod list supports batch enable/disable/delete and drag-and-drop installation, including nested archive unpacking.
- A launch interrupted during mod initialization can start in safe mode: Xenon snapshots the current configuration, disables all mods for that launch, and can restore the full configuration with one click.
- Starting Xenon again focuses the already running window and forwards `xenon://` join links; launching a different version asks whether to open a second window.
- GitHub API requests can reuse the credentials of a locally signed-in GitHub CLI, and the rate-limit dialog points to the token settings.

### Changed
- Download-page image thumbnails are cached with a bounded memory budget, the MDTBBS resource and map browsers are virtualized, community threads append incrementally, mod search is debounced, and the packaged launcher caps its heap at 1g with heap dumps on OOM.

### Fixed
- Mod state and crash-recovery snapshots survive runtime rebuilds, and the health check audits the effective launch data directory.
- The Chinese language bundles are valid UTF-8 again.
- Map version chips rebuild when paging appends rows, and a stale background load no longer pauses the displayed background.

See the [bilingual release notes](release-notes/v1.15.0.md) for details.

## [1.14.1] — 2026-10-05

### Fixed
- Community mod installs keep the release asset name and replace the previous archive of the same mod instead of stacking duplicates; archives without a `mod.hjson`/`mod.json` are listed as unrecognized and refused at install time.
- MDTBBS cloud saves upload and download against the current `/api/v1` contract, including the doubled URL prefix, slot size/revision display and cursor pagination.
- MDTBBS notifications, friend presence, map detail preview/mode and Rich Activity parsing now match the current server responses, with legacy-shape fallbacks.

See the [bilingual release notes](release-notes/v1.14.1.md) for details.

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
