# Xenon 1.18.0 verification matrix (2026-10-23)

Automated checks run from the repository root with `-g .gradle-user-home`:

| Check | Command | Result |
| --- | --- | --- |
| Core tests | `:XenonCore:test` | pass |
| Launcher tests | `:Xenon:test` | pass |
| Translations | `:Xenon:checkTranslations` | pass |
| Checkstyle | `:Xenon:checkstyle :XenonCore:checkstyle` | pass |
| Fat jar | `:Xenon:shadowJar` | pass |
| Portable zip | `:Xenon:packagePortable` | pass |
| Headless list | `:Xenon:run --args="--list"` | pass (exits before JavaFX UI) |

Focused tests added for this release:

- MDTBBS 1.7.0 capabilities/session/approval/lobby parsing and create body.
- Manager normalisation (`unlisted/open` → `private/public`), invite-only approval
  clearing, immediate first heartbeat, approval request/consume flow.
- LAN discovery packet encoding, local-probe filtering and a real local multicast
  round trip.
- Realtime event parsing, bounded de-duplication and malformed-frame tolerance.
- Cloud upload conflict fields, `no_upload_required`, quota aliases, conflict
  details, ETag/Last-Modified and snapshot display metadata.
- Cloud upload metadata collection (game/save/Mod/device) and Mod hashing.
- Whole-instance archive round trip, indexed-Mod omission, backup-before-restore,
  zip-slip rejection and cancellation cleanup.
- Sync baseline round trip and push/pull/conflict decision matrix.
- Dev build runner, watcher debounce, launch-script quoting and recycle bin.

## Pending live-account / manual checks

These require a real MDTBBS account, two clients or a desktop session and were
not run in this headless build environment:

- Five room combinations against the live 1.7.0 server, returned 10-character
  code for `private + public`, approval round trip between two accounts.
- Host creates a room first, starts the game within 8 seconds, guest joins from
  the in-game LAN list; `20151` conflict fallback.
- Public lobby visibility (`public` appears, `private` never) and rate-limit
  backoff.
- Realtime reconnect/resume against the live `/realtime/v1`.
- Live cloud conflict (`SAVE_CONFLICT`), keep-both, forced replace and
  `no_upload_required` paths.
- Two-device archive pull/push, missing-Mod fill from the public index and
  launcher-close upload wait.
- Dev workflow with a real Gradle Mod project (build, edit, rebuild, restart).
- Windows `.bat` and WSL `.sh` script execution, and all three `xenon://` links
  in an OS with the scheme registered.
- Recycle-bin restore of a real instance and `group:` filtering in the game list.

## Competitor re-check (2026-10-23)

- Copper Launcher (`MDTCopper/launcher`): latest commit `275f7503` dated
  2026-10-22 ("联机页定时刷新玩家；房主等游戏开房最多 8 秒"). No commits after
  the previous verification and no release newer than `v0.2.0-alpha2`
  (2026-09-27). README competitor rows remain current.

## Sol review

- Route: audit, then repeated fresh reviews after each correction.
- Final verdict: `ship` after matching approvals by `join_request_id`, guarding
  stale workers by lifecycle token, releasing the approval lock before
  `joinByIntent`, and adding deterministic stale rejection/stale worker tests.
