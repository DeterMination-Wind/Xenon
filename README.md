# Xenon - A Mindustry Launcher
<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/Xenon/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/Xenon?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/Xenon/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/Xenon/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/Xenon?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/Xenon"><img src="https://img.shields.io/github/stars/DeterMination-Wind/Xenon?style=flat&label=Star%20this%20project&color=yellow"></a>
</h1>

[中文](#中文) | [English](#english)

> 把 Mindustry 的版本、内容与服务器，放进一个清晰的工作台。

## 中文

### 概览

Xenon 是面向 Mindustry 玩家和服主的跨平台桌面启动器与实例管理器。它把游戏安装、版本切换、Mod、地图、蓝图、存档、云存档、MDTBBS 社区、联机与服务器维护集中到一个入口，并让每个实例拥有独立的 Java、启动参数和数据目录。

Xenon 源自 [HMCL](https://github.com/HMCL-dev/HMCL)，但工作流围绕 Mindustry 重新组织。中国大陆用户可通过内置镜像、Wayzer 的 GitHub 镜像、MDT BBS 文件站与公共 GitHub 镜像获得 1–30 MB/s 的下载体验；原版安装包优先走 MDT 文件站，失败时自动回退。

### 核心能力

- **实例隔离与版本管理**：原版、BE、MindustryX、CN-ARC、Foo Client、本地 jar 与 Steam 安装可以并存并可选各自隔离。
- **安装与下载**：自动准备 Java 与数据目录，支持断点续传、暂停/继续、下载来源档位、和镜像加速。
- **内容管理**：Mod 国内镜像索引和资源、Mod 批量启停、拖拽导入；WZ/MDTBBS地图、蓝图启动器内浏览安装与导入导出；游戏外 `settings.bin` 表格化编辑与备份。
- **存档与云存档**：本地备份/导出、删除进回收站、长会话退出后自动备份；云存档支持单存档快照、冲突保留两份，以及整实例归档和按实例自动同步。
- **MDTBBS 联机**：公开房间大厅、按可见范围与加入权限建房、房主审批、实时事件、好友邀请/加入动作，以及伪局域网自动发现，客人可直接从游戏内 LAN 列表进入。所有操作在游戏内完成。
- **服务器管理**：多服务器实例、控制台、自动重启，以及自动配置 [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) 脚本环境。
- **排障与维护**：游戏外导出实例日志、崩溃报告；游戏外提供兼容检查、内存建议、实例迁移、回收站功能。

### 快速开始

1. 下载 [最新 Release](https://github.com/DeterMination-Wind/Xenon/releases)：Windows 用单文件 `Xenon.exe`，macOS/Linux 用 `.jar`（需要 Java 17+）。
2. 启动 Xenon，选择 `Install Mindustry` 并安装一个版本。
3. 打开 `Mindustry Versions`，启动实例；首次启动会自动准备数据目录。
4. 需要登录 MDTBBS 时，按提示在系统浏览器完成 MindAuth 授权登录你的 MDTBBS账号。

### 文档

- [用户手册](docs/USAGE.md)：安装、Mod、存档、联机、服务器与 ScriptAgent 的详细操作。
- [完整功能列表与竞品对比](FEATURE.md)：所有能力、版本差异和逐项对照。
- [开发与构建](docs/DEVELOPMENT.md)：构建、测试、打包与发布流程。
- [更新日志](CHANGELOG.md)：版本变更记录。

### 联系方式

- [B 站](https://space.bilibili.com/1433776051)
- QQ 群：`188709300`
- [GitHub Issues](https://github.com/DeterMination-Wind/Xenon/issues)

### 致谢

感谢 [Wayzer / TinyLake](https://github.com/way-zer)（ScriptAgent、MindustryX 与 GitHub 镜像）、[DeterMination](https://github.com/DeterMination-Wind)（镜像与维护服务）、[MDTbbs](https://mdtbbs.cn/)（原版端国内下载镜像）、[休闲 / Xiuxian](https://alist.mindustry.ltd/Github/MindustryX)（MindustryX 镜像）、[爱看番的年兽sama](https://space.bilibili.com/433674920)（默认背景图）、[HMCL 团队](https://github.com/HMCL-dev/HMCL)（项目基础）、[Anuken](https://github.com/Anuken)（Mindustry）以及所有 [GitHub 贡献者](https://github.com/DeterMination-Wind/Xenon/graphs/contributors)。

### License

GPLv3（沿用 HMCL）。详见 [LICENSE](LICENSE)。

---

## English

### Overview

Xenon is a cross-platform desktop launcher and instance manager for Mindustry players and server owners. It brings installation, version switching, mods, maps, schematics, saves, cloud saves, the MDTBBS community, multiplayer, and server maintenance into one place, with isolated Java, launch arguments, and data directories for every instance.

Xenon is based on [HMCL](https://github.com/HMCL-dev/HMCL) but reorganizes the workflow around Mindustry. In mainland China, built-in mirrors, Wayzer's GitHub mirror, the MDT BBS file station, and public GitHub mirrors deliver 1–30 MB/s downloads; original builds use the MDT file station first and fall back automatically.

### Highlights

- **Instance isolation and versions**: Vanilla, BE, MindustryX, CN-ARC, Foo Client, local jars, and Steam installs can coexist with optional per-instance isolation.
- **Install and download**: automatic Java and data-directory setup, resumable and pausable downloads, download source tiers, and mirror acceleration.
- **Content**: domestic mod mirror index and resources, batch enable/disable, drag-and-drop import; WZ/MDTBBS maps and schematics can be browsed, installed, imported, and exported inside the launcher; table-style editing and backup of `settings.bin` outside the game.
- **Saves and cloud**: local backup/export, recycle bin, automatic backup after exiting long sessions; cloud saves support single-save snapshots with keep-both conflict copies, whole-instance archives, and per-instance automatic sync.
- **MDTBBS multiplayer**: public room lobby, room creation by visibility scope and join permission, host approval, realtime events, friend invite/join actions, and pseudo-LAN automatic discovery so guests can join directly from Mindustry's in-game LAN list. All operations are completed in-game.
- **Server management**: multiple server instances, console, auto-restart, and automatic configuration of the [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) script environment.
- **Diagnostics and maintenance**: export instance logs and crash reports outside the game; outside the game, provide compatibility checks, memory suggestions, instance migration, and recycle bin.

### Quick Start

1. Download the [latest release](https://github.com/DeterMination-Wind/Xenon/releases): `Xenon.exe` single file for Windows, or the `.jar` for macOS/Linux (Java 17+).
2. Start Xenon, choose `Install Mindustry`, and install a version.
3. Open `Mindustry Versions` and launch the instance; its data directory is prepared automatically on first launch.
4. When MDTBBS login is required, follow the prompt to complete MindAuth authorization in the system browser and sign in to your MDTBBS account.

### Documentation

- [User guide](docs/USAGE.md): installation, mods, saves, multiplayer, servers, and ScriptAgent.
- [Full feature list and comparison](FEATURE.md): every capability, version differences, and the competitor matrix.
- [Development and build](docs/DEVELOPMENT.md): build, test, package, and release workflow.
- [Changelog](CHANGELOG.md): release history.

### Contact

- [Bilibili](https://space.bilibili.com/1433776051)
- QQ group: `188709300`
- [GitHub Issues](https://github.com/DeterMination-Wind/Xenon/issues)

### Credits

Thanks to [Wayzer / TinyLake](https://github.com/way-zer) (ScriptAgent, MindustryX, and GitHub mirrors), [DeterMination](https://github.com/DeterMination-Wind) (mirrors and hosting), [MDTbbs](https://mdtbbs.cn/) (original Mindustry download mirror), [Xiuxian / 休闲](https://alist.mindustry.ltd/Github/MindustryX) (MindustryX mirror), [爱看番的年兽sama](https://space.bilibili.com/433674920) (default background), the [HMCL team](https://github.com/HMCL-dev/HMCL) (project foundation), [Anuken](https://github.com/Anuken) (Mindustry), and all [GitHub contributors](https://github.com/DeterMination-Wind/Xenon/graphs/contributors).

### License

GPLv3 (inherited from HMCL). See [LICENSE](LICENSE).