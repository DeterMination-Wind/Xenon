# Xenon 功能列表

本文档是 Xenon 的完整能力清单，包含 README 中未展开的小功能与竞品对比。用户操作步骤见 [用户手册](docs/USAGE.md)，构建与发布见 [开发文档](docs/DEVELOPMENT.md)。

## 实例与版本

- 支持原版、Bleeding-Edge、MindustryX、CN-ARC、Foo Client 等来源，也支持导入本地 jar、Steam 安装和外部安装目录。
- 每个实例独立保存 Java 选择、JVM/游戏参数、工作目录、数据目录和 `version.json` 配置。
- 实例复制：连同 `.data`、玩家 UUID、设置和游玩统计一起复制。
- 实例迁移：从其他实例复制存档、Mod、蓝图、地图、`settings.bin` 和游玩统计，可选择是否覆盖同名文件。
- 单层实例分组：在实例页设置分组名，游戏列表可用 `group:<名称>` 筛选。
- 内存建议：结合已启用 Mod 的体积、系统内存与可用内存推荐 `-Xmx`，显式参数优先。
- 便携模式：在程序目录放置 `Xenon.portable`，所有数据随程序保存。

## 安装与下载

- 一键安装 Mindustry，首次启动自动准备 Java 17+ 与数据目录。
- 多镜像与自动回退：启动器作者镜像、Wayzer GitHub 镜像、MDT BBS 文件站、公共 GitHub 镜像。
- 四档下载来源：优先国内 / 优先官方 / 仅国内 / 仅官方；与版本列表和变体入口联动。
- 断点续传、暂停/继续、并发下载与镜像竞速。
- 全局限速：不限速 / 1 / 2 / 5 MB/s / 自定义，下限 32 KB/s。
- GitHub Token 与 GitHub CLI 认证，API 额度从 60 次/小时提升到 5000 次/小时。
- 下载文件按源清单校验 SHA-256，失败自动重试或切换来源。

## Mod 与游戏内容

- 社区 Mod 索引、GitHub Release 直装、搜索与依赖补齐。
- 批量启用/禁用/删除，Shift/Ctrl 连选。
- 拖入文件或文件夹导入；双层压缩包自动拆包；同 internal name 的旧版本自动替换。
- Mod 开发页：配置工程目录、构建命令与产物 glob，构建日志流式显示，产物自动部署。
- 源码监听：1.5 秒防抖，构建期间合并改动；构建成功后可选自动重启游戏。
- 地图：从 [mindustry.top](https://mindustry.top) 浏览和安装。
- 蓝图：`.msch` 导入导出、分享码导入、复制 base64。
- MDTBBS 资源页：浏览/搜索蓝图与地图，预览后安装；本地内容也可提交审核。

## 存档与云存档

- 存档列表显示地图、版本、波次和修改时间；支持备份、导出 ZIP、删除。
- 删除的实例与存档归档进入本地回收站，可恢复或永久删除。
- 长会话退出后自动备份 `saves/`，阈值与保留数量按实例配置。
- 云存档槽位、历史快照、固定/取消固定、重命名槽位、删除快照。
- 上传元数据：游戏版本、构建号、地图、波次、游玩时长、Mod 列表与 SHA-256、设备 ID。
- 冲突处理：保留两份（云端冲突副本）、用云端覆盖本地（先备份）、用本地覆盖云端（先下载云端副本）；强制覆盖需确认当前快照 ID。
- `no_upload_required` 去重响应不重复上传；下载校验 SHA-256，并保存 ETag / Last-Modified。
- 整实例归档：`saves/`、`maps/`、`schematics/`、`settings.bin` 与 Mod 清单；官方索引 Mod 不打包字节，恢复后自动补齐。
- 自动同步（按实例 opt-in）：启动前拉取、退出后上传；冲突永不自动裁决；共享数据目录禁用整实例模式。
- 本地 `cloud-sync.json` 基线：用文件指纹而不是时间戳判断 pull / push / conflict。

## 社区与联机

- MDTBBS 社区页：版块、帖子、搜索、正文/回复、发帖、回复、点赞、收藏、通知。
- MindAuth 系统浏览器登录并自动返回；作者名可打开用户主页。
- 好友在线状态、正在玩动态、联机邀请接受/拒绝。
- 官方公共服务器列表，复制地址或启动游戏。
- MDTBBS 1.7.0 联机：可见范围（private/friends/public）与加入权限（invite_only/friends/public）正交，`require_approval` 独立。
- 公开房间大厅：显示人数、版本与可见性，可直接加入或申请加入。
- 房主审批：加入申请列表、批准/拒绝；客人等待状态与拒绝提示。
- 实时事件：一次性 ticket + `/realtime/v1` WebSocket，30 秒心跳、事件去重、断线 resume；`resume_failed` 重新拉取快照。
- 房间码：`join_policy=public` 时签发 10 位十六进制码，支持粘贴、大小写归一化与格式校验。
- 伪局域网：客人中继连接后向本机 Mindustry 广播房间，游戏 LAN 列表直接出现；20151 被占用时静默降级，保留 `127.0.0.1` 复制地址。
- 房主桥接：先建房后开游戏时，本地 TCP 连接在 8 秒内重试；UDP 转发与中继凭证自动续期。
- 好友动作遵循服务端 `actions.can_join/can_request_join/can_invite`，不在客户端自行推断。
- 默认联机启动器：把已注册的 Xenon 客户端写入 MDTBBS `multiplayer/preferences`。
- EasyTier P2P：六位房号组网，虚拟 IP:6567 加入；首次使用可自动下载 EasyTier。

## 服务器与运维

- 多服务器实例管理、运行状态、控制台、配置与自动重启。
- [ScriptAgent4Mindustry](https://github.com/way-zer/ScriptAgent4Mindustry) 脚本环境。
- 实例健康检查：版本、Java、数据目录、已启用 Mod、推荐内存与兼容性问题。
- 每次启动的进程日志单独保存在实例 `logs/`（保留最近 10 份）；游戏自身日志缺失时仍可查看。
- 崩溃报告分类堆栈帧并高亮可疑 Mod；异常退出与手动退出区分处理。
- `settings.bin` 全量表格编辑、搜索、自动备份（保留 10 份）与一键恢复。

## 自动化与扩展

- 无窗口 CLI：`--help`、`--list`、`--launch <id>`、`--export <id> <file>`、`--get/--set`。
- `--set` 白名单：`download.source`、`download.speed`、`github.token`；未知键退出码 2。
- 启动脚本导出：Windows `.bat` / POSIX `.sh`，包含 Java、JVM 参数、数据目录与工作目录。
- 深链：`xenon://join?intent=`、`xenon://launch?instance=`、`xenon://open?instance=`。
- `.xenon` 整实例打包，便于迁移或分享。
- 单实例协议：重复启动会把深链转发给已运行的启动器。

## 与竞品的比较

> 按各项目公开文档整理（截至 2026-10-23）。竞品能力可能随版本变化，请以各自项目为准。

| 能力 | Xenon | [BookMdtLauncher](https://github.com/ch-BookBanana/BookMdtLauncher) | [Copper Launcher](https://github.com/MDTCopper/launcher) | [MDL](https://github.com/colorgarden/mindustry_launcherMDL) | [MindustryLauncher](https://github.com/BalaM314/MindustryLauncher) |
| --- | --- | --- | --- | --- | --- |
| 平台 / 形态 | Windows / Linux / macOS · 图形界面 | Windows · 图形界面（PySide6，单文件便携） | Windows / Linux / macOS / Android · 图形界面（Flutter，开发阶段） | Windows 7+ · 图形界面（WPF，需 .NET 10） | Windows / Linux / macOS · 命令行（需 Node.js） |
| 移动端运行（Android） | ❌ 桌面启动器；同维护者另有 [Xenon-Mobile](https://github.com/DeterMination-Wind/Xenon-Mobile) | ❌ | ✅ 自带 Java 与 Copper 桥（Mod 支持开发中） | ❌ | ❌ |
| 游戏来源 | 原版 / BE / MindustryX / CN-ARC / Foo Client，本地 jar 与 Steam 导入 | 原版 / MindustryX / MindustryARC | 原版 / BE | 原版 | 原版 / BE / Foo Client，自定义 jar 与源码目录 |
| Java 管理 | ✅ 多运行时、自动检测、Adoptium 下载、按版本匹配 | ✅ 自动嗅探与竞速下载 | ✅ 多运行时与自动下载 | ✅ 扫描与内存分配 | ⚠️ 仅配置路径 |
| 实例隔离 | ✅ 独立数据目录 | ✅ | ✅ | ✅ | ⚠️ 仅版本目录 |
| Mod 管理 | ✅ 社区索引、GitHub 直装、启停、依赖补齐 | ❌ | ✅ 浏览、启停、搜索 | ✅ 浏览、搜索、安装 | ⚠️ 面向开发 |
| Mod 批量 / 拖拽 | ✅ 批量启停删除、拖入文件/文件夹、自动拆包 | ❌ | ✅ 多选、拖入、Windows 拖出 | ❌ | ❌ |
| 地图 / 蓝图 | ✅ mindustry.top、.msch、分享码、资源页 | ❌ | ⚠️ 资源导入导出 | ✅ 社区蓝图 | ❌ |
| 存档 | ✅ 备份/导出、回收站、自动备份 | ❌ | ⚠️ 资源与云存档（开发中） | ✅ 扫描/解析/删除 | ❌ |
| 云存档 | ✅ 单存档冲突处理 + 整实例归档 + 自动同步 | ❌ | ⚠️ 整包/同步/冲突副本（开发中） | ❌ | ❌ |
| settings.bin 编辑 | ✅ 全量表格、搜索、自动备份与恢复 | ❌ | ⚠️ 启动时覆盖部分设置 | ✅ 表格编辑与备份 | ❌ |
| 社区与联机 | ✅ MDTBBS 1.7.0、公开大厅、审批、实时事件、伪局域网 | ❌ | ⚠️ 中继代理与伪局域网（开发中） | ✅ EasyTier 大厅 | ❌ |
| Mod 开发工作流 | ✅ 构建、部署、监听、自动重启 | ❌ | ❌ | ❌ | ✅ 编译与自动重启 |
| 回收站 / 分组 | ✅ 实例与存档回收站、单层分组 | ❌ | ❌ | ❌ | ❌ |
| CLI / 深链 / 启动脚本 | ✅ 无窗口 CLI、`.bat/.sh`、三种深链 | ❌ | ❌ | ❌ | ✅ CLI |
| 国内下载 | ✅ 多镜像回退、GitHub CLI | ⚠️ 仅 JDK 镜像 | ✅ 分类测速与自定义节点 | ✅ 6 种代理 | ❌ |
| 下载来源策略 | ✅ 四档 | ⚠️ 无档位开关 | ✅ 三档 | ❌ | ❌ |
| 下载限速 | ✅ 全局令牌桶 | ❌ | ✅ | ❌ | ❌ |
| 服务器管理 | ✅ 控制台、自动重启、ScriptAgent | ❌ | ❌ | ❌ | ❌ |
| 许可 | GPLv3 | GPL-3.0 | MIT | AGPL-3.0 | 未标注 |

✅ 支持 ｜ ❌ 未提供或未说明 ｜ ⚠️ 部分支持或形态不同

其他 Windows-capable 启动器：

- [GIML](https://github.com/SquareCM2/GIML)（WinUI 3，开发中）
- [zenonet/MindustryLauncher](https://github.com/zenonet/MindustryLauncher)（Avalonia）
- [GXFQE/mindustry-launcher](https://github.com/GXFQE/mindustry-launcher)（Python/tkinter，CAS、隔离存档、自动备份、CLI）
- [Walker196/Mindustry-Launcher](https://github.com/Walker196/Mindustry-Launcher)（Tauri + React + Rust）
- [NixaVulpi/MindustryLauncher](https://github.com/NixaVulpi/MindustryLauncher)（便携包装，自备 jar/JRE）
- [kabaka9527/MindustryLauncher](https://github.com/kabaka9527/MindustryLauncher)（Tauri 2 + Rust + React 19）
- [UnQuis/SingularityLauncher](https://github.com/UnQuis/SingularityLauncher)（Java + Arc）
- [Plain-Mindustry-Launcher](https://github.com/taiko525/Plain-Mindustry-Launcher)（WPF，固定 Java 17）

移动端 / 其他平台：

- [haohandc/MindustryArk](https://github.com/haohandc/MindustryArk)（HarmonyOS NEXT，C）
- [GXFQE/mindustry-launcher-android](https://github.com/GXFQE/mindustry-launcher-android)（Android，APK 插件）
- [EmmM9O/oxygen-launcher](https://github.com/EmmM9O/oxygen-launcher)（Android，Kotlin，已停更）

## English summary

Xenon is a Mindustry launcher and instance manager. Detailed English feature descriptions live in [README](README.md#english); this file keeps the complete capability list and the comparison matrix above. Build and release instructions are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
