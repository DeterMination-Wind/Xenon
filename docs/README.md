<h1 align="center">Xenon — A Mindustry Launcher</h1>

<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-repo-blue?style=flat-square&logo=github)](https://github.com/DeterMination-Wind/Xenon)
[![Bilibili](https://img.shields.io/badge/Bilibili-gray?style=flat-square&logo=bilibili)](https://space.bilibili.com/1433776051)

</div>

---

**English** | 中文

## Introduction

Xenon is an open-source, cross-platform Mindustry launcher forked from [HMCL](https://github.com/HMCL-dev/HMCL). It keeps HMCL's JavaFX UI framework and replaces the Minecraft-specific backends with Mindustry equivalents, so you never have to configure Java arguments or data directories by hand.

Xenon manages 5 Mindustry-desktop client variants — Vanilla, Bleeding-Edge, MindustryX, CN-ARC and Foo Client — plus mods, saves, servers and ScriptAgent, each with its own isolated data directory.

## Key Features

- **GitHub mirror accelerator** — auto-picks the fastest mirror for China downloads
- **5 client variants** — Vanilla / Bleeding-Edge / MindustryX / CN-ARC / Foo Client, side by side
- **Steam Mindustry integration** — auto-discovers Windows Steam installs (incl. custom library folders)
- **mindustry.top map browser** — browse and install maps from the [mindustry.top](https://mindustry.top) repository
- **PostHog telemetry** — optional anonymous process-usage and crash statistics
- **Log viewer** — "Logs" sidebar entry; crash-exit dialogs open the instance's `last_log.txt`
- **Mod management** — wizard-based community mod installation per instance
- **UUID manager** — bind nicknames to UUIDs, injected at launch
- **Save & schematic management** — `.msav` / `.msch` parsing with import, export and share code
- **Crash analyzer** — scan crash logs, highlight mod frames, link to issue templates
- **Server management** — multi-instance, live console, auto-restart, config editor, map pool
- **ScriptAgent integration** — one-click [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) setup with hot-reload

## Quick Start

1. Download the [latest release](https://github.com/DeterMination-Wind/Xenon/releases) and unzip
2. Run `Xenon.bat` (Windows) or `Xenon.sh` (Linux/macOS)
3. Sidebar → "Install Mindustry" → pick a variant → install
4. Sidebar → "Mindustry Versions" → Launch

If Java 17+ is missing, the launcher will download it automatically.

## Build

```bash
./gradlew :Xenon:shadowJar          # fat jar
./gradlew :Xenon:packagePortable    # portable zip
./gradlew :Xenon:packageAll         # platform native installers
```

Requires JDK 17+.

## Contact

- [Bilibili](https://space.bilibili.com/1433776051)
- QQ Group: `188709300`
- [GitHub Issues](https://github.com/DeterMination-Wind/Xenon/issues)

## Credits

Special thanks to [Wayzer / TinyLake](https://github.com/way-zer) (ScriptAgent & MindustryX), [DeterMination](https://github.com/DeterMination-Wind) (mirror hosting), [休闲 (Xiuxian)](https://alist.mindustry.ltd/Github/MindustryX) (China mirror for MindustryX), [爱看番的年兽sama](https://space.bilibili.com/433674920) (default background), the [HMCL team](https://github.com/HMCL-dev/HMCL) (UI framework), [Anuken](https://github.com/Anuken) (Mindustry), and all [contributors](https://github.com/DeterMination-Wind/Xenon/graphs/contributors).

## License

GPLv3 (following HMCL). See [LICENSE](../LICENSE).
