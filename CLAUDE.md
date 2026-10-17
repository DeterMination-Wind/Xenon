# CLAUDE.md

Agent instructions for this repository are in `AGENTS.md` and the scoped
`XenonCore/AGENTS.md`, `Xenon/AGENTS.md`, and `XenonBoot/AGENTS.md`.

Read the root `AGENTS.md` first for build, style, module map, diff routing, and
commit rules; then read the module file that matches your change.

Xenon is a Mindustry launcher carved out of an HMCL fork: the HMCL JavaFX shell
is preserved, Mindustry code lives under `determination.xenon.mindustry.*`, and
the build is multi-module (`Xenon`, `XenonCore`, `XenonBoot`).
