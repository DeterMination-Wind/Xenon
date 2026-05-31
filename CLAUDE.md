# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Xenon is a **Mindustry launcher carved out of an HMCL fork**. The HMCL JavaFX UI shell (decorator, sidebar, wizard, animations, themes) is preserved verbatim; the Minecraft-specific download / launch / mod / account backends have been replaced with Mindustry equivalents under the package root `determination.xenon.mindustry.*`. Per-instance isolation is delivered through `-Dmindustry.data.dir=<.data>`, which is the same property Mindustry's official launcher honours.

Group is `determination`, root version is `0.1.0`. Source/target is Java 17. JavaFX 17+ is required at runtime.

## Build, run, package

The project is a multi-module Gradle build (`Xenon`, `XenonCore`, `XenonBoot`, plus two Minecraft loader libraries under `minecraft/libraries/`).

```bash
./gradlew :Xenon:compileJava            # fast type-check after edits
./gradlew :Xenon:run                    # dev launch (depends on :Xenon:jar => shadowJar)
./gradlew :Xenon:shadowJar              # fat jar => Xenon/build/libs/Xenon-<ver>.jar
./gradlew :Xenon:packagePortable        # JRE-less zip => Xenon/build/dist/Xenon-portable-<ver>.zip
./gradlew :Xenon:packageAll             # portable zip + host-OS jpackage (.msi/.dmg/.deb/.AppImage)
./gradlew :Xenon:checkTranslations      # validates I18N_*.properties bundles parity
./gradlew :Xenon:checkstyle             # license header + style; runs main + test
./gradlew :XenonCore:test               # all XenonCore unit tests (JUnit 5)
./gradlew :XenonCore:test --tests 'determination.xenon.util.io.NetworkUtilsTest'   # single class
```

The default tasks are `clean build`. `:Xenon:run` honours `XENON_JAVA_HOME` and `XENON_JAVA_OPTS` (defaults to `-Xmx1g`).

Per `AGENTS.md`: when invoking gradle, set `GRADLE_USER_HOME` to a workspace-local `.gradle-user-home` directory, and use a 10-minute timeout for `test` tasks. Example: `./gradlew -g .gradle-user-home :XenonCore:test`.

## Architecture

### Module layout (top-level Gradle projects)

- **`XenonCore`** — backend: data model, repositories, downloaders, file-format parsers, process spawn, server lifecycle. No JavaFX. The Mindustry replacements live under `determination.xenon.mindustry.*`:
  - `XenonGameRepository` / `MindustryVersion` / `VersionVariant` / `DataDirectoryPolicy` — version registry on disk under `<config>/versions/<id>/`
  - `XenonLauncher` / `LaunchOptions` — `ProcessBuilder` driver, injects `-Dmindustry.data.dir`, `-Dmindustry.player.uuid` / `.name`
  - `mod/` — local mod manager + remote `mindustry-mods` index (community browser)
  - `save/`, `schematic/` — `.msav` header parser, `.msch` import/export with base64 share-codes
  - `crash/` — scans `crashes/*.txt` + `last_log.txt`, highlights mod stack frames, maps variant → GitHub issue template
  - `server/` — full dedicated-server stack: `ServerInstance`/`ServerInstanceManager`, `ServerProcess`, `ServerConsoleSession` (sealed `ConsoleEvent`: `Started`/`StdoutLine`/`StderrLine`/`Exited`/`Restarted`), `ServerSessionRunner` (auto-restart supervisor), `ServerAutoRestartPolicy`, `ServerConfig`/`ServerConfigManager`, `ServerMapPool`, `ServerModsBridge`, `PortChecker`, `{Vanilla,Be,MindustryX}ServerVersionList`, `ServerJarInstaller`
  - `scriptagent/` — ScriptAgent4Mindustry hook
  - `uuid/` — replaces HMCL's account system; one nickname per UUID
  - `download/MirrorDownloader` — races GitHub-release mirrors (`ghproxy / hub.gitmirror / kgithub / gh.api.99866`) with fallback to upstream
- **`Xenon`** — JavaFX UI. Hosts the HMCL fork shell. Mindustry-specific UI is segregated under `determination.xenon.mindustry.ui.*`:
  - `MindustryRoutes` — central routing helper. `Versions.*`/`GameItem` patches detour to it when an id matches the Mindustry repo
  - `MindustryVersionPage` — per-instance management (Mod / Save / Schematic / Crash tabs); pattern reused everywhere
  - `MindustryVariantPickerPane`, `install/XenonInstallWizardProvider` + wizard pages — install flow
  - `MindustryModBrowserPane` — community mod index browser
  - `ui/server/` — server-management UI: `MindustryServerListPane`, `MindustryServerDetailPage` (Console / Config / Maps / Mods / Settings tabs), `MindustryServer{Console,Config,Maps,Mods,Settings}Pane`
- **`XenonBoot`** — bootstrap launcher (`Main`), self-update, dependency patcher
- **`minecraft/libraries/HMCLTransformerDiscoveryService`, `HMCLMultiMCBootstrap`** — Minecraft-loader libs kept compiling for backward-compat; not invoked from the Mindustry path

### How a Mindustry instance lives on disk

```
<config>/                          Windows %APPDATA%\Xenon, Linux ~/.xenon, macOS ~/Library/Application Support/Xenon
├── xenon.json                     global settings (HMCL-compatible schema)
├── caches/github/                 GitHub release metadata, ETag-aware
├── java/                          Adoptium Temurin auto-downloads
├── uuid-profiles.json
├── versions/<id>/                 one client instance
│   ├── version.json               { variant, build, javaReq, jarPath, dataDirPolicy, ... }
│   ├── <id>.jar
│   └── .data/                     ← -Dmindustry.data.dir
│       ├── saves/ mods/ maps/ schematics/ crashes/ config/
│       └── settings.bin
└── servers/<sid>/                 one dedicated-server instance
    ├── server.json                { jar, javaReq, scriptAgent, autoRestart, port, ... }
    ├── server-release.jar
    └── .data/
```

`MindustryVersion#resolveDataDir(versionRoot)` and `ServerInstance#resolveDataDir(serverRoot)` are the canonical resolvers — never reconstruct paths by hand. `DataDirectoryPolicy` covers `ISOLATED` (default), `GLOBAL`, `CUSTOM`.

### UI shell integration

`RootPage.Skin` (`Xenon/src/main/java/determination/xenon/ui/main/RootPage.java`) builds the left sidebar. Mindustry sidebar entries live alongside HMCL ones and dispatch via `Controllers.navigate(...)`. `Controllers.initialize(stage)` configures the primary stage; the Stage is `StageStyle.UNDECORATED` and gets DWM rounded corners on Win11 via `WindowsNativeUtils.applyDwmRoundedCorners` after `show()`. A `WS_EX_APPWINDOW` fix-up runs there too so the taskbar treats the window as a normal top-level. System-tray (single-click restore) is installed by `TrayIconManager` from `Launcher.start`.

The HMCL "VersionPage" pattern — `DecoratorAnimatedPage` + `AdvancedListBox` sidebar + `TabHeader` over a `TransitionPane` — is the canonical layout for any Mindustry detail page. Reuse it (`MindustryVersionPage`, `MindustryServerDetailPage` are the two existing examples).

## Code style (from AGENTS.md, enforced by checkstyle)

- Annotate every class with JetBrains `@NotNullByDefault`; mark every nullable type/field/param/return/local/type-arg `@Nullable`. Nullability is never implicit.
- Mark immutable arrays/collections `@Unmodifiable` or `@UnmodifiableView`. For arrays use type-use syntax: `String @Unmodifiable []`.
- Every class, field, method gets `///` markdown javadoc. Add inline implementation comments where they materially aid readability.
- `Xenon/build.gradle.kts` defines a long `addOpens` list. New code that touches JavaFX internals must respect it; the manifest mirrors it as `Add-Opens`.
- License header is enforced by `config/checkstyle/license-header.txt`.

## i18n

UI strings live under `Xenon/src/main/resources/assets/lang/I18N*.properties`. The canonical bundle is `I18N.properties` (English); `I18N_zh_CN.properties` (Simplified Chinese) and `I18N_zh.properties` (Traditional) must stay in lock-step. New keys must be added to all three at minimum or `:Xenon:checkTranslations` will fail. Other languages are best-effort. The build also generates an upside-down English bundle (`I18N_en_Qabs.properties`) and a `LocaleNames` resource bundle from `language-subtag-registry`.

## Native quirks worth knowing

- The Stage uses `StageStyle.UNDECORATED`; transparent windows on Win11 24H2 don't get reliable taskbar proxies, hence the explicit `WS_EX_APPWINDOW` + DWM rounded-corner pair.
- AppUserModelID is intentionally **not** set during `gradle :Xenon:run` — without a matching `.lnk` shortcut, Win11 shell hides the taskbar icon. Re-enable it from the jpackaged `Xenon.exe` build.
- JavaFX 25's `MethodHandles.privateLookupIn(Window.class, ...)` route fails (`module javafx.graphics does not open javafx.stage`), so `WindowsNativeUtils.getWindowHandle` looks up the HWND via `user32!FindWindowW` first, with the reflective path as fallback.
- JNA bindings are minimal hand-rolled `Native.load("user32"/"shell32"/"dwmapi", …)` interfaces; `jna-platform` is **not** on the classpath.

## Repo / fork

The fork lives at `https://github.com/DeterMination-Wind/Xenon` (rebrand of HMCL upstream `https://github.com/HMCL-dev/HMCL`). `Metadata.PUBLISH_URL` and `Metadata.XENON_UPDATE_URL` point at the fork; the in-app self-updater talks to `api.github.com/repos/DeterMination-Wind/Xenon/releases/latest`.
