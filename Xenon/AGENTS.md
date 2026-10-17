# Xenon AGENTS.md

JavaFX UI module and the preserved HMCL shell. It depends on `XenonCore` and
`XenonBoot`; keep game logic in Core and use the existing services/controllers.

## Shell and stage

- Entry: `Launcher.start` -> `Controllers.initialize(stage)` -> `RootPage.Skin`
  builds the sidebar. Mindustry entries sit alongside HMCL entries and navigate
  through `Controllers.navigate(...)`.
- The stage is `StageStyle.UNDECORATED` and transparent
  (`scene.setFill(Color.TRANSPARENT)`). After `primaryStage.show()`,
  `Launcher.start` calls `WindowsNativeUtils.ensureTaskbarVisible` (WS_EX_APPWINDOW)
  and `applyDwmRoundedCorners`, then `TrayIconManager.install`; all three are
  Windows/tray shell glue, not game logic.
- AppUserModelID (`setAppUserModelID`) is currently commented out; the id is
  reserved for the jpackaged `Xenon.exe` build and is not active under
  `:Xenon:run` (no matching `.lnk`; Win11 hides the taskbar icon). Don't force it
  in dev.
- `WindowsNativeUtils.getWindowHandle` tries `user32!FindWindowW` first and
  reflection second because the `privateLookupIn(Window.class, ...)` route fails
  (`javafx.graphics` does not open `javafx.stage`). JNA bindings are hand-rolled
  `Native.load("user32"/"shell32"/"dwmapi")`; `jna-platform` is test-only, not
  runtime.

## Mindustry UI map

- `determination.xenon.mindustry.ui.*` is the Mindustry surface. `MindustryRoutes`
  detours HMCL `(profile, id)` calls (`Versions.*`/`GameItem` patches) when the id
  is a Xenon/Mindustry version.
- Detail-page pattern: `DecoratorAnimatedPage` + `AdvancedListBox` sidebar +
  `TabHeader` over `TransitionPane`. Examples: `MindustryVersionPage`
  (Mod/Save/Schematic/Crash/Health/Settings) and `MindustryServerDetailPage`
  (Console/Config/Maps/Mods/Settings/ScriptAgent). Reuse it; don't fork the shell.
- Install flow: `install/XenonInstallWizardProvider` plus
  `MindustryVariantPickerPane`, `VariantSelectionPage`, `IsolationPage`,
  `PreloadModsPage`; browsers: `MindustryModBrowserPane`,
  `MindustryMapBrowserPane`; server list: `ui/server/MindustryServerListPane`.
- Opening a server detail page must not start the server;
  `MindustryServerConsolePane` creates the runner only on Start.
- UI talks to Core through services/controllers; don't duplicate download/server
  policy in panes. Keep UI text in the i18n bundles, not inline literals.
- Java runtime selection: `MindustryJavaPicker`/`JavaManager`; don't duplicate
  Adoptium URLs or JDK metadata.

## JavaFX internals and packaging

- `Xenon/build.gradle.kts` `addOpens` is authoritative: compile-time
  `--add-exports`, runtime `--add-opens`, and the fat-jar manifest `Add-Opens`
  are generated from it. Add a package there before reflecting into it.
- `shadowJar` verifies `runtimeCriticalClasses` and writes checksums; don't
  remove entries without replacing the verification.
- Packaging: `packagePortable` (no JRE), `packageAll` (portable + host jpackage;
  Windows also builds `packageWindowsSingleFileExe` from
  `XenonBootstrap/XenonBootstrap.cs`). `:Xenon:run` honors `XENON_JAVA_HOME` and
  `XENON_JAVA_OPTS` (default `-Xmx1g`).
- `:Xenon:checkTranslations` gates i18n. `:Xenon:checkstyle` is not a Javadoc or
  annotation gate (see root style caveat).

## Release and updater

- `Metadata.PUBLISH_URL` is the fork homepage (`DeterMination-Wind/Xenon`,
  rebrand of `HMCL-dev/HMCL`); `Metadata.XENON_UPDATE_URL` is the GitHub-release
  mirror, overridable with `-Dxenon.update_source.override`. Don't hardcode
  `api.github.com`.
- Self-update is `upgrade/UpdateChecker`, `UpdateHandler`, `IntegrityChecker`;
  it is not in `XenonBoot`.

## i18n and tests

- Follow root i18n rules (four canonical bundles). Edits go under
  `src/main/resources/assets/lang/`; run `:Xenon:checkTranslations`. Generated
  `I18N_en_Qabs.properties` and `LocaleNames` are build output — don't edit.
- JUnit 5 under `src/test/java`; run `:Xenon:test`. Some UI smoke tests may need
  `JavaFXLauncher` or a display; keep new tests headless-friendly where possible.
