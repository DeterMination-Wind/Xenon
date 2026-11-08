# Xenon 功能列表

本文档是 Xenon 的完整能力清单，包含 README 中未展开的小功能。用户操作步骤见 [用户手册](docs/USAGE.md)，构建与发布见 [开发文档](docs/DEVELOPMENT.md)，竞品对比见 [README](README.md#与竞品的比较)。

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
- 文件级差量（manifest 快照）：只上传云端缺失的 blob（并发 ≤3，按服务端建议的退避时间重试），恢复时只下载缺失或哈希不符的文件；超出服务端文件数/大小限制、格式不被接受或配额不足时自动回退整包 zip。
- 快照格式错误可读化：409 `SAVE_SNAPSHOT_FORMAT_MISMATCH`、`SAVE_BLOB_MISSING`、`SAVE_MANIFEST_INVALID` 等不再显示裸 HTTP 码。
- 自动同步（按实例 opt-in）：启动前拉取、退出后上传；冲突永不自动裁决；共享数据目录禁用整实例模式。
- 本地 `cloud-sync.json` 基线：用文件指纹而不是时间戳判断 pull / push / conflict。

## 社区与联机

- MDTBBS 社区页：版块、帖子、搜索、正文/回复、发帖、回复、点赞、收藏、通知。
- MindAuth 系统浏览器登录并自动返回；作者名可打开用户主页。
- 好友在线状态、正在玩动态、联机邀请接受/拒绝。
- 官方公共服务器列表，复制地址或启动游戏。
- MDTBBS 1.8.0 联机：可见范围（private/friends/public）与加入权限（invite_only/friends/public）正交，`require_approval` 独立；每账号只允许一个活跃房间，建房撞上遗留会话时先结束僵尸房间并重试一次。
- 公开房间大厅：显示人数、版本与可见性；按钮按服务端的 `joinable` / `can_request_join`（以及批准开关）决定直接加入、申请加入或不可加入，不可加入时显示 `blocked_reason` 的本地化原因。
- 房主审批：以 `GET /multiplayer/sessions/{id}/join-requests` 为权威快照，实时事件只触发刷新，丢事件不再永久丢申请；客人等待状态与拒绝提示。
- 实时事件：一次性 ticket + `/realtime/v1` WebSocket，30 秒心跳、事件去重、断线 resume；`resume_failed` 重新拉取快照。
- `capabilities.limits` 收敛建房人数、大厅分页与邀请上限；邀请/在线状态连接超限时给出可操作提示。
- 邀请对话框支持按用户名搜索非好友并直接邀请，是否成功由服务端的隐私/好友策略与邀请上限决定。
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

竞品对比已移至 [README](README.md#与竞品的比较)（英文：[README_EN.md](README_EN.md#comparison-with-other-launchers)）。

## English summary

Xenon is a Mindustry launcher and instance manager. Detailed English feature descriptions live in [README_EN.md](README_EN.md); this file keeps the complete capability list, and the competitor matrix lives in [README_EN.md](README_EN.md#comparison-with-other-launchers). Build and release instructions are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
