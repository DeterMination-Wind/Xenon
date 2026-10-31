# XenonCore AGENTS.md

Backend module. `Xenon` depends on it; `XenonCore` must not depend on `Xenon` or
`XenonBoot`, and must not construct scenes/stages. `determination.xenon.mindustry.*`
has zero JavaFX imports; legacy HMCL packages (`auth/`, `task/`, `util/javafx/`,
`game/World`) do import JavaFX and must not be copied into new Mindustry code.

## Package map (Mindustry path)

- `mindustry/` — `MindustryVersion`, `VersionVariant`, `XenonGameRepository`,
  `DataDirectoryPolicy`, `LaunchOptions`, `XenonLauncher`.
- `mindustry/download/` — `MirrorDownloader` (races mirrors, falls back to
  upstream), `MirrorSelector` (candidate list/cache), `MindustryDownloadPolicy`
  (manifest verification/retry/source switch), resumable/segmented downloads,
  GitHub release client/cache, `VanillaVersionList`, `BeVersionList`,
  `MindustryXVersionList`, `CnArcVersionList`, `FooVersionList`, MDTBBS
  mirror/list.
- `mindustry/server/` — `ServerInstance(Manager)`, `ServerProcess`,
  `ServerConsoleSession` (`ConsoleEvent`: Started/StdoutLine/StderrLine/Exited/
  Restarted), `ServerSessionRunner` (auto-restart supervisor),
  `ServerAutoRestartPolicy`, `ServerConfig(Manager)`, `ServerMapPool`,
  `ServerModsBridge`, `PortChecker`, `{Vanilla,Be,MindustryX}ServerVersionList`,
  `ServerJarInstaller`.
- `mindustry/mod/` — local mod manager plus the remote `mindustry-mods` index;
  `save/` parses `.msav` headers and launcher save archives; `schematic/`
  imports/exports `.msch` with base64 share-codes; `crash/` scans
  `crashes/*.txt` + `last_log.txt` and builds issue-ready reports
  (`IssueTemplateBuilder` maps a variant to its GitHub issue template).
- `mindustry/uuid/` — UUID/nickname profiles in `uuid-profiles.json`; one
  nickname per UUID. `scriptagent/` installs ScriptAgent4Mindustry into a server
  data dir. `community/`, `map/`, `modpack/`, `migrate/`, `playtime/`, `steam/`
  cover their named domains.
- `java/` — JDK discovery and runtime metadata (Adoptium/Temurin-aware); the UI
  picker lives in `Xenon`.
- The Minecraft backend (`auth/` non-offline providers, `download/`, `game/`,
  `launch/`, `mod/`, `resourcepack/`, `schematic/`) has been removed. `netplay/`
  is Mindustry-only and stays; generic helpers live in `task/`, `util/`,
  `event/`, and `java/`.

## Data-directory invariants

- `MindustryVersion#resolveDataDir(versionRoot)` and
  `ServerInstance#resolveDataDir(serverRoot)` are the only path resolvers. Never
  rebuild `<XENON_GLOBAL_DIRECTORY>/versions/<id>/.data` or
  `<XENON_GLOBAL_DIRECTORY>/servers/<sid>/.data` by hand.
- `DataDirectoryPolicy`: `ISOLATED` (default), `GLOBAL`, `CUSTOM`. The property is
  `-Dmindustry.data.dir`; Mindustry's official launcher honours the same property.
- Client layout: `<XENON_GLOBAL_DIRECTORY>/versions/<id>/version.json`, the jar, `.data/`.
  Server layout: `<XENON_GLOBAL_DIRECTORY>/servers/<sid>/server.json`, the jar, `.data/`.
  `.data/` holds `saves/`, `mods/`, `maps/`, `schematics/`, `crashes/`, `config/`,
  and `settings.bin`.
- The launcher (`Xenon`) resolves local config `./.xenon/xenon.json`
  (HMCL-derived schema; jar-dir/CWD copies also accepted) and global settings
  `<XENON_GLOBAL_DIRECTORY>/config.json` (Windows `%APPDATA%/Xenon`, Linux
  `$XDG_DATA_HOME/Xenon` or `~/.xenon`, macOS
  `~/Library/Application Support/Xenon`; portable mode, `xenon.home`, or
  `XENON_USER_HOME` override the global root). `ConfigHolder.locateConfig` resolves
  the local file; Core receives roots as parameters.
- `LaunchOptions.buildCommandLine()` adds `-Dmindustry.data.dir`; `XenonLauncher`
  also sets `MINDUSTRY_DATA_DIR` for mods that read the env var.
  `MindustryPlayerLaunchHook` appends `-Dmindustry.player.uuid`/`.name`; the
  server side injects the data-dir property via `ServerInstanceManager`.

## Server invariants

- `ServerSessionRunner.stop()` writes `stop\n`, waits ~3 s, then force-kills.
  Exit code 0 never auto-restarts; `ServerAutoRestartPolicy` owns the decision.
- `ServerModsBridge` and `ScriptAgentInstaller` write under the resolved data
  directory (`mods/`, `config/scripts/`), never the launcher root.
- `PortChecker.isPortFree` is advisory UI validation, not a reservation.

## Download invariants

- Add or change mirrors only in `MirrorSelector`/`MindustryDownloadPolicy`; do not
  scatter URL lists. `MindustryDownloadPolicy` verifies manifest size/hash,
  retries the same source once, then switches source family; pause/cancel never
  switches.
- This policy is JavaFX-free on purpose so it can run on headless CI; keep it
  that way.
- GitHub release metadata is ETag-cached under the launcher cache root; the
  launcher passes `Metadata.getCachesDirectory()` (Metadata lives in `Xenon`).
  Respect token/rate-limit paths instead of adding unauthenticated calls.

## Tests and style

- JUnit 5 under `src/test/java`; run `:XenonCore:test` (root has the Gradle-home
  and 10-minute rule). One class: append `--tests <FQCN>`, e.g.
  `--tests 'determination.xenon.mindustry.LaunchOptionsTest'`.
- `:XenonCore:checkstyle` is not a Javadoc or annotation gate (see root style
  caveat).
