# XenonBoot AGENTS.md

Java-8-compatible bootstrap stub. It exists only to check the runtime and hand
off before JavaFX loads; the packaged jar overwrites its `EntryPoint` with
`Xenon/src/main/java/determination/xenon/EntryPoint.java`.

## Startup path

- Manifest `Main-Class` is `determination.xenon.Main`.
- `Main` rejects `!` in the working directory/jar path, checks
  `java.version >= 17`, then calls `EntryPoint.main`; on failure it shows a Swing
  error and exits 1.
- `compileJava` uses `--release 8`; `compileTestJava` uses 17. Keep `main` free
  of Java 9+ APIs (`var`, records, `List.of`, `Path.of`, ...).
- `EntryPoint` here is a dummy; don't put startup logic here that the packaged
  jar expects.
- Six main classes: `BootProperties`, `DesktopUtils`, `EntryPoint`, `Main`,
  `util/SwingUtils`, `util/UTF8Control`; keep them Java-8-safe.

## What lives elsewhere

- Self-update is in `Xenon/src/main/java/determination/xenon/upgrade/`
  (`UpdateChecker`, `UpdateHandler`, `IntegrityChecker`).
- JavaFX self-patching is in `Xenon`'s `util/SelfDependencyPatcher`.
- The HMCL shell and Mindustry UI are in `Xenon`; see `Xenon/AGENTS.md`.

## Stale HMCL leftovers

- `DOWNLOAD_PAGE` points at the HMCL site and `EntryPoint` says "verify that the
  HMCL is complete". These are upstream strings, not Xenon behavior; don't treat
  them as a contract.

## Tests

- `XenonBoot/src/test/java/determination/xenon/MainTest.java`; run
  `:XenonBoot:test` (root has the Gradle-home and 10-minute rule).
