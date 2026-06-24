# Xenon — A Mindustry Launcher

[中文](#中文) | [English](#english)

Xenon 是基于 HMCL 框架的 Mindustry 启动器。统一管理 3 个客户端变体、Mod、存档、服务端与 ScriptAgent，无需手动配置 Java 参数和数据目录。

---

## 中文

### 简介

Xenon 是一款跨平台 Mindustry 启动器，基于 [HMCL](https://github.com/HMCL-dev/HMCL) UI 框架开发。支持多实例隔离、一键安装、Mod 管理、服务端管理等功能。

### 主要特性

- **游戏下载镜像加速** — 自动测速选择最快 GitHub 镜像，国内也能满速下载
- **Mod 管理** — 向导式安装社区 Mod，自动归入对应实例的 `mods/` 目录
- **UUID 管理器** — 替代账户系统，为每个 UUID 绑定昵称，启动时自动注入
- **3 种客户端变体** — Vanilla / Bleeding-Edge / MindustryX，各自独立数据目录，并存互不干扰
- **导入本地 Jar** — 拖入 `MindustryX-Desktop.jar` 即可自动识别版本与 Java 需求
- **存档与蓝图管理** — 解析 `.msav`、`.msch` 文件，支持导入/导出/分享码
- **崩溃日志分析** — 自动扫描崩溃日志，识别 Mod 冲突并跳转对应 Issue 模板
- **服务端管理** — 多实例并发、实时控制台、自动重启、端口检测、配置编辑、地图池管理
- **ScriptAgent 集成** — 一键安装与管理 [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) 模块

### 快速开始

1. 下载 [最新版 Xenon-portable](https://github.com/DeterMination-Wind/Xenon/releases) 解压
2. Windows 双击 `Xenon.bat`；Linux/macOS 执行 `chmod +x Xenon.sh && ./Xenon.sh`
3. 侧栏 → "Install Mindustry" → 选择变体与版本 → 完成安装
4. 侧栏 → "Mindustry Versions" → 一键启动

若系统无 Java 17+，启动器会自动引导下载。

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

**特别感谢以下个人与项目对 Xenon 的贡献：**

- **[Wayzer / TinyLake](https://github.com/way-zer)** — ScriptAgent 与 MindustryX
- **[DeterMination](https://github.com/DeterMination-Wind)** — 高速镜像与维护服务
- **[休闲 (Xiuxian)](https://alist.mindustry.ltd/Github/MindustryX)** — 为 MindustryX 提供国内高速镜像
- **[爱看番的年兽sama](https://space.bilibili.com/433674920)** — 提供启动器默认背景图
- **所有 GitHub 公益加速站贡献者** — 为游戏版本下载提供加速
- **[HMCL 团队](https://github.com/HMCL-dev/HMCL)** — UI 框架基石
- **[Anuken](https://github.com/Anuken)** — Mindustry / MindustryBuilds
- **[TinyLake](https://github.com/TinyLake)** — MindustryX
- 以及所有 [GitHub 贡献者](https://github.com/DeterMination-Wind/Xenon/graphs/contributors)

### License

GPLv3（沿用 HMCL）。详见 [LICENSE](LICENSE)。

---

## English

### What is Xenon?

Xenon is a cross-platform Mindustry launcher forked from [HMCL](https://github.com/HMCL-dev/HMCL). It keeps HMCL's JavaFX UI framework and replaces the Minecraft-specific backends with Mindustry equivalents.

### Key Features

- **GitHub mirror accelerator** — Auto-picks fastest mirror for China downloads
- **Mod management** — Wizard-based community mod installation per instance
- **UUID manager** — Bind nicknames to UUIDs, injected at launch
- **3 client variants** — Vanilla, Bleeding-Edge, MindustryX, side by side with isolated data directories
- **Import local jars** — Drag & drop to auto-detect variant and Java requirements
- **Save & schematic management** — `.msav` / `.msch` parsing with import, export and share code
- **Crash analyzer** — Scan crash logs, highlight mod frames, link to issue templates
- **Server management** — Multi-instance, live console, auto-restart, config editor, map pool
- **ScriptAgent integration** — One-click [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) setup with hot-reload

### Quick Start

1. Download the [latest release](https://github.com/DeterMination-Wind/Xenon/releases) and unzip
2. Run `Xenon.bat` (Windows) or `Xenon.sh` (Linux/macOS)
3. Sidebar → "Install Mindustry" → pick variant → install
4. Sidebar → "Mindustry Versions" → Launch

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

Special thanks to:

- **[Wayzer / TinyLake](https://github.com/way-zer)** — ScriptAgent & MindustryX
- **[DeterMination](https://github.com/DeterMination-Wind)** — Mirror hosting & maintenance
- **[Xiuxian (休闲)](https://alist.mindustry.ltd/Github/MindustryX)** — High-speed China mirror for MindustryX
- **[爱看番的年兽sama](https://space.bilibili.com/433674920)** — Default launcher background
- All GitHub mirror accelerator contributors
- **[HMCL Team](https://github.com/HMCL-dev/HMCL)** — UI framework foundation
- **[Anuken](https://github.com/Anuken)** — Mindustry / MindustryBuilds
- **[TinyLake](https://github.com/TinyLake)** — MindustryX
- All [GitHub contributors](https://github.com/DeterMination-Wind/Xenon/graphs/contributors)

### License

GPLv3 — see [LICENSE](LICENSE).

---

Built on HMCL. Mindustry, MindustryX, ScriptAgent are properties of their respective authors.
