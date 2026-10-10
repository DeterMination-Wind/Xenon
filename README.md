# Xenon - A Mindustry Launcher
<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/Xenon/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/Xenon?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/Xenon/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/Xenon/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/Xenon?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/Xenon"><img src="https://img.shields.io/github/stars/DeterMination-Wind/Xenon?style=flat&label=Star%20this%20project&color=yellow"></a>
</h1>

[中文](#中文) | [English](#english)

> 把 Mindustry 的版本、内容与服务器，放进一个清晰的工作台。

---

## 中文

### Xenon 是什么？

Xenon 是一款面向 Mindustry 玩家和服主的跨平台桌面启动器与实例管理器。它把游戏安装、版本切换、Mod、存档、蓝图、云存档、MDTBBS 社区和服务器维护集中到一个入口中，让不同游戏环境彼此独立，却不需要手动整理 Java、启动参数和数据目录。

**面向中国大陆用户：** Xenon 通过整合由启动器作者提供的镜像服务器、Wayzer 的 GitHub 镜像、MDT BBS 原版端文件站以及通用 GitHub 公益镜像等，为中国大陆用户带来 1 MB/s-30 MB/s 的超高速 Mindustry 资源下载体验；原版安装包会优先从 MDT 文件站获取，失败时自动回退到其他镜像。

Xenon 源自 [HMCL](https://github.com/HMCL-dev/HMCL)，并围绕 Mindustry 的实际使用方式重新组织。无论你是在多个客户端之间切换、维护不同的 Mod 组合，还是管理一台服务器，都可以从同一个地方开始。

### 为什么使用 Xenon？

#### 让版本和 Mod 环境互不打扰

官方版、测试版和社区客户端可以并存，每个实例拥有自己的配置、存档和 Mod。你可以为不同玩法保留独立环境，切换版本时也不必担心文件互相覆盖。

#### 把安装和下载交给启动器

Xenon 可以直接安装 Mindustry，也支持接入 Steam 中已有的安装或导入本地版本。启动器会协助准备 Java 和数据目录，并针对不同网络环境使用可用的下载来源，在来源不可用时自动回退。下载支持断点续传与暂停/继续，中断后重新安装会从已下载的字节继续；在设置中填入 GitHub Token 还可以把 GitHub API 额度从每小时 60 次提升到 5000 次。

#### 让游戏内容跟着实例一起管理

Mod、地图、蓝图和存档都可以围绕具体实例整理、备份和导出。完整的游戏环境还可以打包为 `.xenon` 文件，方便迁移或分享；地图则可以直接从 [mindustry.top](https://mindustry.top) 浏览和安装。

#### 在启动器里参与 MDTBBS 社区

内置 [MDTBBS](https://mdtbbs.cn/) 社区页，浏览版块和帖子、搜索、阅读正文与回复，发帖、回复、点赞、收藏和查看通知都无需打开网页。通过 MindAuth 在系统浏览器完成登录后自动返回启动器，帖子和回复的作者名可以直接打开用户主页。

#### 蓝图、地图、存档都在实例旁边

下载页的「资源」可以浏览和搜索 MDTBBS 上的蓝图与地图，预览后一键安装到指定实例，也可以把本地蓝图或地图提交审核。每个实例还提供「云存档」：云端槽位与历史快照、上传、一键恢复（恢复前自动备份本地文件）、固定重要版本并显示额度。

#### 好友与联机邀请

登录 MDTBBS 后，可以看到好友的在线状态与「正在玩」动态，并在好友面板里接受或拒绝联机邀请；`xenon://join?intent=` 链接会直接唤起启动器。当前版本完成联机信令，进入服务器仍在 Mindustry 内完成。

#### 玩家和服主使用同一个工作台

除了启动本地游戏，Xenon 也能管理多个 Mindustry 服务器实例。服主可以在同一处处理运行状态、控制台、配置和自动重启，并使用 [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) 管理脚本环境。

#### 联机与实例小工具

「联机」页内置官方公共服务器列表，支持 MDTBBS 联机会话（创建 / 加入房间、好友邀请与 Join Intent，数据经官方中继），也可以用六位房号通过 EasyTier 快速组建 P2P 房间；实例页的「迁移数据」可以从其他实例复制存档、mod、蓝图、地图与游玩统计；启动内存会结合已启用 mod 的体积自动推荐；便携版还能用启动器目录下的 `Xenon.portable` 标记把所有数据保存在程序目录中。

#### 出现问题时更容易找到原因

每个实例都有对应的日志和崩溃记录。游戏退出或 Mod 冲突时，可以直接打开相关日志并查看可疑的 Mod 信息，减少在多个版本和文件夹之间排查的时间。

### 与竞品的比较

> 官方发行版与 Steam 版不提供启动器，Java、数据目录和 Mod/存档整理都需要手动完成。下表按各项目公开文档整理（截至 2026-10-10），竞品能力可能随版本变化，请以各自项目为准。

| 能力 | Xenon | [BookMdtLauncher](https://github.com/ch-BookBanana/BookMdtLauncher) | [Copper Launcher](https://github.com/MDTCopper/launcher) | [MDL](https://github.com/colorgarden/mindustry_launcherMDL) | [MindustryLauncher](https://github.com/BalaM314/MindustryLauncher) |
| --- | --- | --- | --- | --- | --- |
| 平台 / 形态 | Windows / Linux / macOS · 图形界面 | Windows · 图形界面（PySide6，单文件便携） | Windows / Linux / macOS / Android · 图形界面（Flutter，开发阶段） | Windows 7+ · 图形界面（WPF，需 .NET 10） | Windows / Linux / macOS · 命令行（需 Node.js，npm 分发） |
| 移动端运行（Android） | ❌ 桌面启动器；同维护者另有独立项目 [Xenon-Mobile](https://github.com/DeterMination-Wind/Xenon-Mobile) | ❌ | ✅ 自带 Java 运行环境与 Copper 桥（模组支持开发中） | ❌ | ❌ |
| 游戏来源 | 原版 / Bleeding-Edge / MindustryX / CN-ARC / Foo Client，本地 jar 与 Steam 导入 | 原版 / MindustryX / MindustryARC | 原版 / Bleeding-Edge | 原版（GitHub Release） | 原版 / Bleeding-Edge / Foo Client，自定义 jar 与源码目录 |
| 实例隔离 | ✅ 独立数据目录 | ✅ | ✅ | ✅ | ⚠️ 仅版本目录，数据目录未说明 |
| Mod 管理 | ✅ 社区索引、GitHub 直装、启停、依赖补齐 | ❌ | ✅ 浏览、启停、搜索与分类筛选 | ✅ 浏览、搜索、安装卸载 | ⚠️ 面向 Mod 开发 |
| Mod 批量操作 / 拖拽导入 | ❌ 逐项操作，经文件对话框安装 | ❌ | ✅ 多选与拖动连选，拖入文件导入 | ❌ | ❌ |
| 地图 / 蓝图 | ✅ mindustry.top 浏览安装，.msch 与分享码导入导出 | ❌ | ⚠️ 资源导入导出；蓝图浏览页因缺少数据源尚未开放 | ✅ 社区蓝图下载 | ❌ |
| 存档 | ✅ 备份 / 导出，云存档 | ❌ | ✅ 导入导出、跨版本迁移 | ✅ 扫描 / 解析 / 删除 | ❌ |
| 游戏内设置编辑（settings.bin） | ⚠️ 仅写入昵称 / UUID 与 Mod 启停 | ❌ | ⚠️ 启动时覆盖游戏内设置与多人用户名 | ✅ 表格化编辑、搜索、自动备份 | ❌ |
| 社区与联机 | ✅ MDTBBS 社区、好友与联机邀请 | ❌ | ❌ | ✅ EasyTier P2P 联机大厅 | ❌ |
| Mod 开发工作流（源码编译 / 改动自动重启） | ❌ | ❌ | ❌ | ❌ | ✅ 编译源码、构建 Mod，文件变动自动重启 |
| 国内下载 | ✅ 多镜像自动回退 | ⚠️ JDK 走清华镜像，游戏本体依赖 GitHub 网络 | ✅ github / raw / api 分类测速切换，支持自定义节点 | ✅ 内置 6 种 GitHub 代理 | ❌ |
| 下载限速 | ❌ | ❌ | ✅ | ❌ | ❌ |
| 服务器管理 | ✅ 控制台、自动重启、ScriptAgent | ❌ | ❌ | ❌ | ❌ |
| 最后更新 | ![last commit](https://img.shields.io/github/last-commit/DeterMination-Wind/Xenon) | ![last commit](https://img.shields.io/github/last-commit/ch-BookBanana/BookMdtLauncher) | ![last commit](https://img.shields.io/github/last-commit/MDTCopper/launcher) | ![last commit](https://img.shields.io/github/last-commit/colorgarden/mindustry_launcherMDL) | ![last commit](https://img.shields.io/github/last-commit/BalaM314/MindustryLauncher) |
| 许可 | GPLv3 | GPL-3.0 | MIT | AGPL-3.0 | 未标注 |

✅ 支持 ｜ ❌ 未提供或未在项目中说明 ｜ ⚠️ 部分支持或形态不同

其他同样支持 Windows 的 Mindustry 启动器：

- [GIML](https://github.com/SquareCM2/GIML)（WinUI 3）![last commit](https://img.shields.io/github/last-commit/SquareCM2/GIML)
- [zenonet/MindustryLauncher](https://github.com/zenonet/MindustryLauncher)（Avalonia，Windows/Linux 实例管理）![last commit](https://img.shields.io/github/last-commit/zenonet/MindustryLauncher)
- [GXFQE/mindustry-launcher](https://github.com/GXFQE/mindustry-launcher)（Python 标准库 + tkinter，CAS 去重、隔离存档与自动备份）![last commit](https://img.shields.io/github/last-commit/GXFQE/mindustry-launcher)
- [Walker196/Mindustry-Launcher](https://github.com/Walker196/Mindustry-Launcher)（Tauri + React + Rust）![last commit](https://img.shields.io/github/last-commit/Walker196/Mindustry-Launcher)
- [NixaVulpi/MindustryLauncher](https://github.com/NixaVulpi/MindustryLauncher)（便携包装，需自备 Mindustry.jar 与 JRE）![last commit](https://img.shields.io/github/last-commit/NixaVulpi/MindustryLauncher)

Xenon 的差异在于把整条链路放进同一个界面：镜像下载与回退、实例隔离、Mod/地图/蓝图/存档、云存档、MDTBBS 社区与好友联机、服务器管理，以及 `.xenon` 打包和 `xenon://` 链接。

### 快速开始

1. 下载 [最新版 Xenon-portable](https://github.com/DeterMination-Wind/Xenon/releases) 并解压。
2. Windows 双击 `Xenon.bat`；Linux/macOS 执行 `chmod +x Xenon.sh && ./Xenon.sh`。
3. 选择 `Install Mindustry`，挑选一个版本并完成安装。
4. 打开 `Mindustry Versions`，启动刚刚安装的实例。

如果系统没有可用的 Java 17+，Xenon 会引导下载所需运行环境。

### 用户手册

更多安装、Mod、存档、蓝图、服务器和 ScriptAgent 操作说明，请参阅 [Xenon 用户手册](docs/USAGE.md)。

### 构建

```bash
./gradlew :Xenon:shadowJar          # fat jar
./gradlew :Xenon:packagePortable    # 便携版 zip
./gradlew :Xenon:packageAll         # 平台原生安装包
```

需要 JDK 17+。

### 联系方式

- [B 站](https://space.bilibili.com/1433776051)
- QQ 群：`188709300`
- [GitHub Issues](https://github.com/DeterMination-Wind/Xenon/issues)

### 致谢

特别感谢 [Wayzer / TinyLake](https://github.com/way-zer)（ScriptAgent、MindustryX 与 GitHub 镜像）、[DeterMination](https://github.com/DeterMination-Wind)（镜像与维护服务）、[MDTbbs](https://mdtbbs.cn/)（Mindustry 原版端国内下载镜像）、[休闲 / Xiuxian](https://alist.mindustry.ltd/Github/MindustryX)（MindustryX 镜像）、[爱看番的年兽sama](https://space.bilibili.com/433674920)（默认背景图）、[HMCL 团队](https://github.com/HMCL-dev/HMCL)（项目基础）、[Anuken](https://github.com/Anuken)（Mindustry）以及所有 [GitHub 贡献者](https://github.com/DeterMination-Wind/Xenon/graphs/contributors)。

### 负责任地使用 AI

本项目除 README 和部分 Doc 外，几乎全部由 AI 生成。秉持负责任地使用 AI 的原则，我简要说明一下项目的历史：
- 最初，从事 Mindustry Mod 开发时，我困扰与在测试 Mod 时，每次都要先禁用我日常使用的所有 Mod 再加上我的 Dev Mod，这非常麻烦，于是我着手开发一款启动器。Minecraft和Mindustry同为Java开发，一看就是很适合迁移，所以我 Fork HMCL 进行开发。
- 最初工作由 `GPT-5.4` 完成，在 `DeepSeek Harness` 发布后，我学习了其出色的 Doc 架构，并改造了 Xenon，使 `DeepSeek V4(.1) Flas`h 的能力即可满足项目开发需求。如果你也想用 AI 参与开发，那当然很好。但请始终记住：负责任地使用 AI，你需要对自己的代码负责。因此，我建议维护者在 Push 代码前，先用单独的 Subagent Review 一遍，这可能会发现不少低级问题。

### License

GPLv3（沿用 HMCL）。详见 [LICENSE](LICENSE)。

---

## English

### What is Xenon?

Xenon is a cross-platform desktop launcher and instance manager for Mindustry players and server owners. It brings installation, version switching, mod management, saves, schematics, cloud saves, the MDTBBS community, and server maintenance into one place, so different game environments stay separate without requiring you to manage Java, launch arguments, or data directories by hand.

**For users in mainland China:** Xenon combines mirror servers provided by the launcher maintainer, Wayzer's GitHub mirror, the MDT BBS file station for original Mindustry builds, and general-purpose public GitHub mirrors to deliver 1 MB/s-30 MB/s download speeds for Mindustry resources. Original builds are fetched from the MDT file station first and fall back to the other mirrors when it is unavailable.

Xenon is based on [HMCL](https://github.com/HMCL-dev/HMCL), but its workflow is organized around the way Mindustry is actually played and hosted. Whether you switch between several clients, maintain different mod setups, or run a server, Xenon gives you one place to manage them.

### Why Xenon?

#### Keep versions and mod setups separate

Official, testing, and community clients can live side by side. Each instance keeps its own configuration, saves, and mods, so you can maintain different setups without overwriting one another.

#### Let the launcher handle installation

Xenon can install Mindustry directly, discover an existing Steam installation, or import a local build. It helps prepare Java and data directories and uses available download sources for different network conditions, with automatic fallback when a source is unavailable. Downloads are resumable and can be paused and continued, so a retried installation picks up from the last byte; adding a GitHub token in the settings raises the API rate limit from 60 to 5000 requests per hour.

#### Keep game content with its instance

Mods, maps, schematics, and saves can be organized, backed up, and exported alongside the instance they belong to. An entire setup can also be packed as a `.xenon` file for migration or sharing, while maps can be browsed and installed from [mindustry.top](https://mindustry.top).

#### Join the MDTBBS community inside the launcher

Xenon ships a native [MDTBBS](https://mdtbbs.cn/) community page: browse categories and threads, search, read formatted posts and replies, and post, reply, like, bookmark or check notifications without a browser. MindAuth sign-in opens the system browser and returns to the launcher automatically, and author names link to their forum profiles.

#### Resources, maps and saves live next to the instance

The Resources tab on the download page browses and searches MDTBBS blueprints and maps, previews them, and installs them into the selected instance with one click; local blueprints or maps can be submitted for moderation too. Every instance also has Cloud saves: cloud slots and history, upload, one-click restore with an automatic local backup, pinning and quota display.

#### Friends and multiplayer invites

After signing in to MDTBBS, Xenon shows friends' online status and "playing now" activity, and the friends dialog lets you accept or decline multiplayer invites; `xenon://join?intent=` links bring the launcher to the foreground. Signalling is implemented in this release; joining the server still happens inside Mindustry.

#### One workspace for players and server owners

Xenon also manages multiple Mindustry server instances. Server owners can handle runtime status, console access, configuration, and automatic restarts in the same workspace, with [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) support for script management.

#### Netplay and instance tools

The Netplay page ships the official public server list, supports MDTBBS multiplayer sessions (create/join rooms, friend invites and Join Intents over the official relay) and creates EasyTier P2P rooms from a six-digit code. Each instance gains a Migrate data dialog that copies saves, mods, schematics, maps and playtime from another instance; launch memory is suggested from the enabled mod set; and the portable build can keep all data next to the launcher through the `Xenon.portable` marker.

#### Make troubleshooting less painful

Every instance has its own logs and crash records. When a game exits or a mod causes trouble, Xenon can open the relevant log and surface suspicious mod information, making it easier to find the cause.

### Comparison with alternatives

> Official builds and Steam do not ship a launcher: Java, data directories, and mod/save organization are all manual there. The table below is compiled from each project's public documentation (as of 2026-10-10); competitor features can change between releases, so check the respective projects for the current state.

| Capability | Xenon | [BookMdtLauncher](https://github.com/ch-BookBanana/BookMdtLauncher) | [Copper Launcher](https://github.com/MDTCopper/launcher) | [MDL](https://github.com/colorgarden/mindustry_launcherMDL) | [MindustryLauncher](https://github.com/BalaM314/MindustryLauncher) |
| --- | --- | --- | --- | --- | --- |
| Platforms / form | Windows / Linux / macOS · GUI | Windows · GUI (PySide6, single-file portable) | Windows / Linux / macOS / Android · GUI (Flutter, in development) | Windows 7+ · GUI (WPF, requires .NET 10) | Windows / Linux / macOS · CLI (requires Node.js; distributed via npm) |
| Mobile (Android) | ❌ Desktop launcher; the same maintainer ships the separate [Xenon-Mobile](https://github.com/DeterMination-Wind/Xenon-Mobile) | ❌ | ✅ Bundled Java runtime and Copper bridge (mod support in progress) | ❌ | ❌ |
| Game sources | Vanilla / Bleeding-Edge / MindustryX / CN-ARC / Foo Client, local jar and Steam import | Vanilla / MindustryX / MindustryARC | Vanilla / Bleeding-Edge | Vanilla (GitHub releases) | Vanilla / Bleeding-Edge / Foo Client, custom jars and source directories |
| Instance isolation | ✅ Separate data directories | ✅ | ✅ | ✅ | ⚠️ Version folders only; data isolation not documented |
| Mod management | ✅ Community index, GitHub install, enable/disable, dependency resolution | ❌ | ✅ Browse, toggle, search and category filters | ✅ Browse, search, install/uninstall | ⚠️ Mod development focused |
| Batch mod actions / drag-and-drop import | ❌ Per-item actions, install via a file dialog | ❌ | ✅ Multi-select and drag-select, drag files in to import | ❌ | ❌ |
| Maps / schematics | ✅ Browse and install from mindustry.top, .msch and share-code import/export | ❌ | ⚠️ Resource import/export; schematic browser paused for lack of a data source | ✅ Community schematic downloads | ❌ |
| Saves | ✅ Backup/export plus cloud saves | ❌ | ✅ Import/export, cross-version migration | ✅ Scan / parse / delete | ❌ |
| In-game settings editing (settings.bin) | ⚠️ Writes nickname/UUID and mod toggles only | ❌ | ⚠️ Overrides in-game settings and the multiplayer username at launch | ✅ Table editor, search, automatic backup | ❌ |
| Community & multiplayer | ✅ MDTBBS community, friends and invites | ❌ | ❌ | ✅ EasyTier P2P lobby | ❌ |
| Mod development workflow (source compile / restart on change) | ❌ | ❌ | ❌ | ❌ | ✅ Compiles sources, builds mods, restarts on file changes |
| China-friendly downloads | ✅ Automatic mirror fallback | ⚠️ Tsinghua mirror for the JDK; game downloads depend on GitHub | ✅ Speed-tested mirrors per github/raw/api category, custom nodes supported | ✅ Six built-in GitHub proxies | ❌ |
| Download rate limiting | ❌ | ❌ | ✅ | ❌ | ❌ |
| Server management | ✅ Console, auto-restart, ScriptAgent | ❌ | ❌ | ❌ | ❌ |
| Last update | ![last commit](https://img.shields.io/github/last-commit/DeterMination-Wind/Xenon) | ![last commit](https://img.shields.io/github/last-commit/ch-BookBanana/BookMdtLauncher) | ![last commit](https://img.shields.io/github/last-commit/MDTCopper/launcher) | ![last commit](https://img.shields.io/github/last-commit/colorgarden/mindustry_launcherMDL) | ![last commit](https://img.shields.io/github/last-commit/BalaM314/MindustryLauncher) |
| License | GPLv3 | GPL-3.0 | MIT | AGPL-3.0 | Not declared |

✅ supported ｜ ❌ not offered or not documented ｜ ⚠️ partial or different shape

Other Windows-capable Mindustry launchers:

- [GIML](https://github.com/SquareCM2/GIML) (WinUI 3) ![last commit](https://img.shields.io/github/last-commit/SquareCM2/GIML)
- [zenonet/MindustryLauncher](https://github.com/zenonet/MindustryLauncher) (Avalonia, Windows/Linux instance management) ![last commit](https://img.shields.io/github/last-commit/zenonet/MindustryLauncher)
- [GXFQE/mindustry-launcher](https://github.com/GXFQE/mindustry-launcher) (Python standard library + tkinter; CAS deduplication, isolated save profiles and automatic backups) ![last commit](https://img.shields.io/github/last-commit/GXFQE/mindustry-launcher)
- [Walker196/Mindustry-Launcher](https://github.com/Walker196/Mindustry-Launcher) (Tauri + React + Rust) ![last commit](https://img.shields.io/github/last-commit/Walker196/Mindustry-Launcher)
- [NixaVulpi/MindustryLauncher](https://github.com/NixaVulpi/MindustryLauncher) (portable wrapper, needs your own Mindustry.jar and JRE) ![last commit](https://img.shields.io/github/last-commit/NixaVulpi/MindustryLauncher)

What sets Xenon apart is keeping the whole pipeline in one window: mirror downloads and fallback, instance isolation, mods/maps/schematics/saves, cloud saves, the MDTBBS community with friends and invites, server management, plus `.xenon` packaging and `xenon://` links.

### Quick Start

1. Download the [latest Xenon-portable release](https://github.com/DeterMination-Wind/Xenon/releases) and unzip it.
2. Run `Xenon.bat` on Windows, or `chmod +x Xenon.sh && ./Xenon.sh` on Linux/macOS.
3. Choose `Install Mindustry`, select a version, and finish the installation.
4. Open `Mindustry Versions` and launch the new instance.

If Java 17+ is not available, Xenon will guide you through downloading a suitable runtime.

### User Guide

See the [Xenon User Guide](docs/USAGE.md) for installation, mod, save, schematic, server, and ScriptAgent workflows.

### Build

```bash
./gradlew :Xenon:shadowJar
./gradlew :Xenon:packagePortable
./gradlew :Xenon:packageAll
```

Requires JDK 17+.

### Contact

- [Bilibili](https://space.bilibili.com/1433776051)
- QQ Group: `188709300`
- [GitHub Issues](https://github.com/DeterMination-Wind/Xenon/issues)

### Credits

Special thanks to [Wayzer / TinyLake](https://github.com/way-zer) (ScriptAgent, MindustryX, and GitHub mirrors), [DeterMination](https://github.com/DeterMination-Wind) (mirrors and maintenance), [MDTbbs](https://mdtbbs.cn/) (domestic mirror for original Mindustry builds), [Xiuxian / 休闲](https://alist.mindustry.ltd/Github/MindustryX) (MindustryX mirror), [爱看番的年兽sama](https://space.bilibili.com/433674920) (default background), the [HMCL team](https://github.com/HMCL-dev/HMCL) (project foundation), [Anuken](https://github.com/Anuken) (Mindustry), and all [GitHub contributors](https://github.com/DeterMination-Wind/Xenon/graphs/contributors).

### Using AI Responsibly

Except for the README and some docs, almost the entire project was generated by AI. In keeping with the principle of using AI responsibly, I will briefly explain the project's history:
- Initially, while doing Mindustry mod development, I was troubled by having to disable all the mods I use daily plus my dev mod every time I tested a mod. This was very troublesome, so I set out to develop a launcher. Minecraft and Mindustry are both developed in Java, so it was clearly a good fit for porting, so I forked HMCL for development.
- The initial work was done by `GPT-5.4`. After `DeepSeek Harness` was released, I studied its excellent documentation architecture and reworked Xenon so that the capabilities of `DeepSeek V4(.1) Flash` alone could meet the project's development needs. If you also want to use AI to participate in development, that's of course great. But always remember: use AI responsibly—you need to be responsible for your own code. Therefore, I suggest maintainers first run a separate Subagent Review before pushing code; this may uncover quite a few low-level issues.

### License

GPLv3, following HMCL. See [LICENSE](LICENSE).

---

Mindustry, MindustryX, and ScriptAgent are properties of their respective authors.
