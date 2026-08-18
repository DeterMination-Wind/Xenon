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

Xenon 是一款面向 Mindustry 玩家和服主的跨平台桌面启动器与实例管理器。它把游戏安装、版本切换、Mod、存档、蓝图和服务器维护集中到一个入口中，让不同游戏环境彼此独立，却不需要手动整理 Java、启动参数和数据目录。

**面向中国大陆用户：** Xenon 通过整合由启动器作者提供的镜像服务器、Wayzer 的 GitHub 镜像、MDT BBS 原版端镜像以及通用 GitHub 公益镜像等，为中国大陆用户带来 1 MB/s-30 MB/s 的超高速 Mindustry 资源下载体验。

Xenon 源自 [HMCL](https://github.com/HMCL-dev/HMCL)，并围绕 Mindustry 的实际使用方式重新组织。无论你是在多个客户端之间切换、维护不同的 Mod 组合，还是管理一台服务器，都可以从同一个地方开始。

### 为什么使用 Xenon？

#### 让版本和 Mod 环境互不打扰

官方版、测试版和社区客户端可以并存，每个实例拥有自己的配置、存档和 Mod。你可以为不同玩法保留独立环境，切换版本时也不必担心文件互相覆盖。

#### 把安装和下载交给启动器

Xenon 可以直接安装 Mindustry，也支持接入 Steam 中已有的安装或导入本地版本。启动器会协助准备 Java 和数据目录，并针对不同网络环境使用可用的下载来源，在来源不可用时自动回退。

#### 让游戏内容跟着实例一起管理

Mod、地图、蓝图和存档都可以围绕具体实例整理、备份和导出。完整的游戏环境还可以打包为 `.xenon` 文件，方便迁移或分享；地图则可以直接从 [mindustry.top](https://mindustry.top) 浏览和安装。

#### 玩家和服主使用同一个工作台

除了启动本地游戏，Xenon 也能管理多个 Mindustry 服务器实例。服主可以在同一处处理运行状态、控制台、配置和自动重启，并使用 [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) 管理脚本环境。

#### 出现问题时更容易找到原因

每个实例都有对应的日志和崩溃记录。游戏退出或 Mod 冲突时，可以直接打开相关日志并查看可疑的 Mod 信息，减少在多个版本和文件夹之间排查的时间。

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

### License

GPLv3（沿用 HMCL）。详见 [LICENSE](LICENSE)。

---

## English

### What is Xenon?

Xenon is a cross-platform desktop launcher and instance manager for Mindustry players and server owners. It brings installation, version switching, mod management, saves, schematics, and server maintenance into one place, so different game environments stay separate without requiring you to manage Java, launch arguments, or data directories by hand.

**For users in mainland China:** Xenon combines mirror servers provided by the launcher maintainer, Wayzer's GitHub mirror, the MDT BBS mirror for original Mindustry builds, and general-purpose public GitHub mirrors to deliver 1 MB/s-30 MB/s download speeds for Mindustry resources.

Xenon is based on [HMCL](https://github.com/HMCL-dev/HMCL), but its workflow is organized around the way Mindustry is actually played and hosted. Whether you switch between several clients, maintain different mod setups, or run a server, Xenon gives you one place to manage them.

### Why Xenon?

#### Keep versions and mod setups separate

Official, testing, and community clients can live side by side. Each instance keeps its own configuration, saves, and mods, so you can maintain different setups without overwriting one another.

#### Let the launcher handle installation

Xenon can install Mindustry directly, discover an existing Steam installation, or import a local build. It helps prepare Java and data directories and uses available download sources for different network conditions, with automatic fallback when a source is unavailable.

#### Keep game content with its instance

Mods, maps, schematics, and saves can be organized, backed up, and exported alongside the instance they belong to. An entire setup can also be packed as a `.xenon` file for migration or sharing, while maps can be browsed and installed from [mindustry.top](https://mindustry.top).

#### One workspace for players and server owners

Xenon also manages multiple Mindustry server instances. Server owners can handle runtime status, console access, configuration, and automatic restarts in the same workspace, with [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) support for script management.

#### Make troubleshooting less painful

Every instance has its own logs and crash records. When a game exits or a mod causes trouble, Xenon can open the relevant log and surface suspicious mod information, making it easier to find the cause.

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

### License

GPLv3, following HMCL. See [LICENSE](LICENSE).

---

Mindustry, MindustryX, and ScriptAgent are properties of their respective authors.
