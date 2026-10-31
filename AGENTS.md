# AGENTS.md — Xenon

Xenon is a Mindustry launcher carved out of an HMCL fork. The HMCL JavaFX shell
(decorator, sidebar, wizard, animations, themes) is preserved; the Minecraft
backend has been removed and all launcher behavior lives under
`determination.xenon.mindustry.*`. Per-instance isolation uses
`-Dmindustry.data.dir`.

Java 17 bytecode for the launcher; `XenonBoot` main stays Java 8-compatible for
bootstrap. Group is `determination`; version root comes from
`config/project.properties` (per-build version in `Xenon/build.gradle.kts`).
JavaFX classic/modern pins live in `JavaFXPlatform` under `buildSrc`; don't pin
them by hand.

## Build, run, test

Run from the repo root with the wrapper (`./gradlew`; Windows `.\gradlew.bat`).
Always pass `-g .gradle-user-home` (`GRADLE_USER_HOME`) and use a 10-minute
timeout for `test` tasks.

```bash
./gradlew -g .gradle-user-home :Xenon:compileJava
./gradlew -g .gradle-user-home :Xenon:run
./gradlew -g .gradle-user-home :Xenon:packagePortable
./gradlew -g .gradle-user-home :XenonCore:test
./gradlew -g .gradle-user-home :Xenon:checkstyle :XenonCore:checkstyle :Xenon:checkTranslations
```

`:Xenon:run` builds `shadowJar` and honors `XENON_JAVA_HOME` / `XENON_JAVA_OPTS`.
`packageAll` adds host-OS jpackage installers; `shadowJar` is the fat jar; task
definitions live in `Xenon/build.gradle.kts`, root default tasks are `clean build`.

## Java code style

- Every class: `@NotNullByDefault`; every nullable type/field/parameter/return/local/type argument: `@Nullable`. Nullability is never implicit.
- Immutable arrays/collections: `@Unmodifiable`/`@UnmodifiableView`; arrays use type-use syntax, e.g. `String @Unmodifiable []`.
- Every class, field, method: `///` Markdown Javadoc that is accurate about behavior, constraints, and side effects; inline comments only for non-obvious behavior.
- License header `config/checkstyle/license-header.txt`; checks live in `config/checkstyle/` (license/mechanical only, no Javadoc or annotation rules). `./gradlew checkstyle` does analyze the real sources — it is the `Check Codes` CI gate — so run it before pushing; it catches unused/redundant imports, missing trailing newlines, whitespace and the locale rules for `toLowerCase()`/`toUpperCase()`.

## Modules

- `XenonCore/` — backend: versions, downloads, mods, saves, schematics, crashes, servers, UUID; `determination.xenon.mindustry.*`. Read `XenonCore/AGENTS.md`.
- `Xenon/` — JavaFX UI, HMCL shell, and packaging; the Windows single-file stub is `XenonBootstrap/`. Read `Xenon/AGENTS.md`.
- `XenonBoot/` — Java-8 bootstrap stub; the packaged jar replaces its `EntryPoint` with `Xenon`'s. Read `XenonBoot/AGENTS.md`.
- `buildSrc/` and `gradle/libs.versions.toml` own build logic and dependency pins. The Minecraft backend and its two `minecraft/libraries` modules have been removed; the launcher is Mindustry-only.
- `docs/` holds Xenon user docs (README, USAGE, DEVELOPMENT); it is not the agent entry point. Don't edit it in agent-doc refactors.

## i18n

- UI strings: `Xenon/src/main/resources/assets/lang/I18N*.properties`.
- Keep these four in lock-step: `I18N.properties`, `I18N_zh_CN.properties`, `I18N_zh.properties`, `I18N_lzh.properties`. Other locales are best-effort.
- Add each new key to all four; `:Xenon:checkTranslations` checks zh_CN keys against en/zh and lzh spelling. Generated `I18N_en_Qabs` / `LocaleNames` are build output.

## Must-keep traps

- **JavaFX internals:** the `addOpens` list in `Xenon/build.gradle.kts` is authoritative; add a package there before reflecting into it (details: `Xenon/AGENTS.md`).
- **Win11 shell:** after `show()`, the undecorated stage needs `ensureTaskbarVisible` + `applyDwmRoundedCorners`; AppUserModelID stays unset in dev (details: `Xenon/AGENTS.md`).
- **HMCL shell:** keep the decorator/sidebar/tab layout and route through `MindustryRoutes`; don't fork the shell (details: `Xenon/AGENTS.md`).
- **Release/updater:** `Metadata.PUBLISH_URL` / `XENON_UPDATE_URL` are the single source; the updater uses a mirror and `-Dxenon.update_source.override` (details: `Xenon/AGENTS.md`).
- **JNA:** hand-rolled `Native.load` bindings; `jna-platform` is test-only.

## Diff routing

| If the diff touches... | Read first |
| --- | --- |
| `XenonCore/**` | `XenonCore/AGENTS.md` |
| `Xenon/**/mindustry/**` or `Xenon/**/ui/**` (UI, install, shell, stage, tray) | `Xenon/AGENTS.md` |
| `Xenon/src/main/java/determination/xenon/{Launcher,Metadata,EntryPoint,upgrade,setting,java}/**` | `Xenon/AGENTS.md` |
| `*.gradle.kts`, `buildSrc/**`, `gradle/**`, `config/checkstyle/**`, packaging, `XenonBootstrap/**` | root build/style sections + `Xenon/AGENTS.md` |
| `XenonBoot/**` | `XenonBoot/AGENTS.md` |
| `**/I18N*.properties` | root i18n + `:Xenon:checkTranslations` |
| `.github/workflows/**` or release/updater | root traps; inspect CI logs before editing |

## Commit discipline

- This checkout (`Xenon-fork`) is the source of truth. One logical change per commit; stage only intended files. Don't push, tag, or publish.
- `config/mods/` contains local test jars and is ignored; never commit them. Build outputs and generated bundles stay ignored.
- Don't edit the workspace-root `AGENTS.md` outside this repo (`../AGENTS.md`) or sibling repositories.
