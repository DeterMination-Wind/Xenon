# Xenon — A Mindustry Launcher built on the HMCL UI

[中文](#中文) | [English](#english)

> 在 HMCL 启动器的成熟 UI 上替换后端为 Mindustry，统一管理 5 个客户端变体、Mod、存档、服务端与 ScriptAgent。
>
> A Mindustry-shaped launcher carved out of HMCL's UI, supporting 5 client variants, mods, saves, dedicated servers and ScriptAgent in one place.

---

## 中文

### 是什么

Xenon 是基于 [HMCL](https://github.com/HMCL-dev/HMCL) Fork 的 Mindustry 启动器。我们保留 HMCL 全部 JavaFX UI 框架（侧栏、向导、装饰器、动画、主题），把 Minecraft 专属的下载/启动/Mod/账户后端替换为 Mindustry 的等价物。所有数据通过 `-Dmindustry.data.dir=<.data>` 实现版本隔离，与官方 Mindustry 启动方式完全兼容。

### 核心特性

- **5 个客户端变体**：Vanilla / Bleeding-Edge / MindustryX / CN-ARC / Foo Client，一键安装、并存、各自独立数据目录。
- **隔离环境 = ModLoader 选择**：安装向导第 4 步可选社区 mod 预装到该实例的 `.data/mods/`。
- **导入本地 jar**：拖一个 `MindustryX-Desktop.jar` 进来即可识别 build / variant / Java 需求。
- **存档 / 蓝图管理**：解析 `.msav` 头读 mapName/build/wave；`.msch` 蓝图导入导出 + base64 分享代码复制。
- **崩溃日志分析**：扫 `crashes/*.txt` + `last_log.txt`，识别 mod 堆栈帧并高亮，按 variant 跳到对应 GitHub issue 模板。失败时也可联系 QQ 群 `188709300`。
- **服务端管理**：多实例并发 / 实时控制台 / 自动重启 / 端口占用检测 / 配置文件表单 / 地图池 / Mod 同步管理。
- **ScriptAgent 集成**：一键安装 [way-zer/ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry)，kts 模块启用/禁用/热重载。
- **UUID 管理器**：取代 HMCL 账户系统，每个 UUID 配一个昵称，启动时通过 `-Dmindustry.player.uuid` / `-Dmindustry.player.name` 注入。
- **JavaFX 主题**：保留 HMCL 自定义主题色 / 字号 / 中英切换；默认色已改为 Mindustry 橙 `#ffa44a`。
- **GitHub Releases 镜像**：`ghproxy / hub.gitmirror / kgithub / gh.api.99866` 自动探活，国内拉取无忧。
- **三平台打包**：`./gradlew :Xenon:packageAll` 自动判断 host OS 出 `.msi` / `.dmg` / `.deb` / `.AppImage`；`packagePortable` 出 `Xenon-portable.zip`（不含 JRE，复用 `<config>/java/`）。

### v1.0.0 发布说明

- 修复运行时 classpath 不一致导致的 Mindustry 启动失败与 UUID 管理页面 `NoClassDefFoundError`，并在构建中校验关键类会被打进 shadow jar。
- Mindustry 实例选择、启动 JavaFX 模块打开参数与多实例启动流程更稳定。
- 整合包导出改为流式写入，任务窗口显示真实进度/速度，取消会中断并清理半成品。
- 服务端配置页按 Mindustry server `config` 命令支持的真实字段重做，移除无效配置项，并可对运行中的服务端应用配置。
- MindustryX 版本统一显示 `X...` 正式版或 `B...` 测试版，安装列表与实例列表保持一致。
- 保留蓝图搜索和崩溃搜索，移除“询问 AI”入口。
- 修正 QQ 群跳转链接，新增 DeterMination 鸣谢。

### 数据目录布局

```
<config>/                       Windows: %APPDATA%\Xenon
                                Linux:   ~/.xenon
                                macOS:   ~/Library/Application Support/Xenon
├── xenon.json                  全局设置（HMCL 兼容）
├── caches/github/              GitHub Release 元数据缓存（带 ETag）
├── java/                       Adoptium 自动下载的 JDK
├── uuid-profiles.json          UUID + 昵称
├── versions/<id>/              一个 Mindustry 客户端版本
│   ├── version.json            {variant, build, javaReq, jarPath, dataDirPolicy, ...}
│   ├── <id>.jar
│   └── .data/                  ← -Dmindustry.data.dir 指向这里
│       ├── saves/  mods/  maps/  schematics/  crashes/  config/
│       └── settings.bin
└── servers/<sid>/              一个 Mindustry 服务端实例
    ├── server.json             {jar, javaReq, scriptAgent, autoRestart, port, ...}
    ├── server-release.jar
    └── .data/
```

### 快速开始

1. 下载 `Xenon-portable-1.0.0.zip` 解压；
2. Windows 双击 `Xenon.bat` / Linux & macOS 跑 `chmod +x Xenon.sh && ./Xenon.sh`；
3. 侧栏 → "Install Mindustry" 进入向导：选变体 → 选版本 → 命名/隔离 → （可选）预装 mod → 完成；
4. 侧栏 → "Mindustry Versions" 一键启动。

如果系统没有 Java 17+，启动器会引导你下载 Adoptium Temurin。

### 架构概览

```
┌─────────────────────────────────────────────────────┐
│ Xenon UI (HMCL fork — JavaFX, decorator/wizard/...) │
│   RootPage 侧栏新增 8 个 Mindustry 入口             │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────┐
│ determination.xenon.mindustry.* (XenonCore 新增)     │
│ ┌──────────────┐  ┌──────────────┐  ┌─────────────┐  │
│ │ XenonGame    │  │ XenonLauncher│  │ download/   │  │
│ │ Repository   │  │              │  │ MirrorSelect│  │
│ └──────────────┘  └──────────────┘  └─────────────┘  │
│ ┌──────────────┐  ┌──────────────┐  ┌─────────────┐  │
│ │ mod/         │  │ save/        │  │ schematic/  │  │
│ └──────────────┘  └──────────────┘  └─────────────┘  │
│ ┌──────────────┐  ┌──────────────┐  ┌─────────────┐  │
│ │ server/      │  │ scriptagent/ │  │ crash/uuid/ │  │
│ └──────────────┘  └──────────────┘  └─────────────┘  │
└────────────────────────┬─────────────────────────────┘
                         │ ProcessBuilder
                         ▼
              ┌──────────────────────┐
              │  Mindustry.jar       │
              │  -Dmindustry.data.dir│
              └──────────────────────┘
```

### 构建

```bash
./gradlew :Xenon:shadowJar           # 单个 fat jar (build/libs/Xenon-*.jar)
./gradlew :Xenon:packagePortable     # build/dist/Xenon-portable-*.zip
./gradlew :Xenon:packageAll          # +所属平台的 msi/dmg/deb/AppImage
```

要求 JDK 17+ 和 JavaFX 17+ runtime。

### 联系方式

- B 站：<https://space.bilibili.com/1433776051>
- QQ 群：`188709300`
- GitHub Issues：<https://github.com/DeterMination-Wind/Xenon/issues>

### 致谢

- [HMCL 团队](https://github.com/HMCL-dev/HMCL) — UI 框架基石。
- [Anuken](https://github.com/Anuken) — Mindustry / MindustryBuilds。
- [TinyLake](https://github.com/TinyLake) — MindustryX。
- [BlueWolf3434](https://github.com/BlueWolf3434) — Mindustry-CN-ARC。
- [mindustry-antigrief](https://github.com/mindustry-antigrief) — Foo Client。
- [way-zer](https://github.com/way-zer) — ScriptAgent4Mindustry。
- 社区 mod 作者们。

### License

GPLv3（沿用 HMCL）。详见 [LICENSE](LICENSE)。

---

## English

### What

Xenon is a Mindustry launcher forked from [HMCL](https://github.com/HMCL-dev/HMCL). We keep the entire HMCL JavaFX UI (sidebar, wizard, decorator, animations, themes) and swap the Minecraft-specific download/launch/mod/account backends for their Mindustry equivalents. Per-version isolation is achieved through `-Dmindustry.data.dir=<.data>`, which is exactly the property Mindustry's official launcher honours.

### Highlights

- **5 client variants**: Vanilla / Bleeding-Edge / MindustryX / CN-ARC / Foo Client — installed side by side, each with its own data directory.
- **Isolated environment as ModLoader picker**: the install wizard's step 4 lets you pre-install community mods straight into `.data/mods/`.
- **Local jar import**: drag any `MindustryX-Desktop.jar` and Xenon sniffs build / variant / Java requirement.
- **Save & schematic management**: `.msav` header parser (mapName / build / wave / playtime) + `.msch` import / export with base64 share-code clipboard.
- **Crash analyzer**: scans `crashes/*.txt` and `last_log.txt`, highlights mod stack frames, jumps straight to the variant's GitHub issue template. QQ group `188709300` is also exposed for community help.
- **Dedicated servers**: multi-instance, live console, auto-restart, port-conflict detection, config form editor, map pool, mod sync.
- **ScriptAgent integration**: one-click install for [way-zer/ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry), enable / disable / hot-reload kts modules.
- **UUID manager**: replaces HMCL's account system; one nickname per UUID, injected via `-Dmindustry.player.uuid` / `-Dmindustry.player.name`.
- **GitHub mirror selector**: races `ghproxy / hub.gitmirror / kgithub / gh.api.99866`, falls back to upstream when needed.
- **Cross-platform packaging**: `./gradlew :Xenon:packageAll` produces `.msi` / `.dmg` / `.deb` / `.AppImage` depending on host; `packagePortable` builds a JRE-less zip that reuses `<config>/java/`.

### v1.0.0 Release Notes

- Fixed runtime classpath mismatches that caused Mindustry startup failures and UUID manager `NoClassDefFoundError`; the build now verifies critical classes are present in the shadow jar.
- Stabilized Mindustry instance selection, JavaFX module opens, and multi-instance launch handling.
- Reworked modpack export to stream files with real progress/speed reporting; cancellation now stops the export and removes partial output.
- Rebuilt the dedicated-server config page around real Mindustry server `config` command keys, removed invalid fields, and applies saved values to a running server.
- Standardized MindustryX version labels as `X...` release builds or `B...` preview builds across installers and instance lists.
- Kept schematic search and crash search, and removed the "Ask AI" entry.
- Fixed the QQ group link and added DeterMination to the credits.

### Quick Start

1. Grab `Xenon-portable-1.0.0.zip`, unzip;
2. Windows: double-click `Xenon.bat`. Linux / macOS: `chmod +x Xenon.sh && ./Xenon.sh`;
3. Sidebar → "Install Mindustry" → variant → release → name/isolation → optional pre-install mods → done;
4. Sidebar → "Mindustry Versions" → Launch.

### Build

```bash
./gradlew :Xenon:shadowJar
./gradlew :Xenon:packagePortable
./gradlew :Xenon:packageAll
```

Requires JDK 17+ with JavaFX.

### Contact

- Bilibili: <https://space.bilibili.com/1433776051>
- QQ Group: `188709300`
- GitHub Issues: <https://github.com/DeterMination-Wind/Xenon/issues>

### License

GPLv3 — see [LICENSE](LICENSE).

---

Built on top of HMCL. Mindustry, MindustryX, CN-ARC, Foo Client, ScriptAgent are properties of their respective authors.
