# Changelog

All notable changes to Xenon will be documented in this file.

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
