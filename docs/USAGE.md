# Xenon 用户手册

本文逐步演示 Xenon v0.1 的主要操作。每节末尾给出预期的状态校验，方便排错。

## 1. 安装 Xenon

### 1.1 Portable

1. 解压 `Xenon-portable-<ver>.zip` 到任意目录；
2. Windows 双击 `Xenon.bat`；Linux/macOS `chmod +x Xenon.sh && ./Xenon.sh`；
3. 第一次启动会创建数据目录：
   - Windows: `%APPDATA%\Xenon`
   - Linux: `~/.xenon`（或 `$XDG_DATA_HOME/Xenon`）
   - macOS: `~/Library/Application Support/Xenon`

### 1.2 系统包（msi / dmg / deb / AppImage）

`./gradlew :Xenon:packageAll` 在 host OS 上产出对应包；安装后启动器与 portable 行为一致，但 jar 进入只读 `Xenon` 程序目录，所有用户数据仍写到上述配置目录。

## 2. 安装一个 Mindustry 变体

1. 侧栏点 "Install Mindustry"；
2. **第 1 步 — 变体**：5 张卡片任选一个：Vanilla / Bleeding-Edge / MindustryX / CN-ARC / Foo Client；点 "Install" 进下一步；
3. **第 2 步 — 版本**：拉取 GitHub Release，列出 build / 发布日期。点选一行 → "Next >"；
4. **第 3 步 — 命名/隔离**：
   - 版本 id（默认 `<variant>-<build>`）；只接受字母/数字/`._-`；
   - 数据目录策略：
     - **Isolated**（推荐）：保存在 `<config>/versions/<id>/.data/`；
     - **Global**：与官方 Mindustry 数据目录共享；
     - **Custom**：填写绝对路径；
5. **第 4 步 — 预装 mod**（可选）：选择若干社区 mod 一同安装；点 "Skip" 或 "Install selected"；
6. 完成后侧栏 "Mindustry Versions" 出现新条目，点 "Launch" 即可启动。

## 3. 导入本地 jar

1. 侧栏点 "Import Mindustry jar"；
2. FileChooser 选 `.jar`；
3. 输入 id；
4. Xenon 自动从文件名嗅探 build / variant / Java 需求；保存到 `<config>/versions/<id>/<id>.jar` 并启动。

## 4. Mod 管理

侧栏 → "Mindustry Versions" 选条目，或后续 "Mod 管理" 页（W5.3 UI）：

- 已装 mod：启用/禁用（重命名 `.disabled` 后缀）/ 删除；
- 社区索引：从 `Anuken/mindustry-mods` 拉取，按 ★ 排序，"Install" 走 GitHub Release；
- GitHub 直装：直接输入 `owner/repo`；
- 缺失依赖时弹"自动补齐"对话框；`minGameVersion > 当前 build` 高亮警告。

资源包：Mindustry 不区分纯贴图 mod 与功能 mod，所有类型都按 mod 处理。如要分类，可在 mod 详情里手动打 "resourcepack" 标签。

## 5. 存档备份

侧栏 → "Mindustry Saves"：

1. 顶部 dropdown 选实例；
2. 列表显示 mapName / build / wave / 修改时间；
3. 工具栏：
   - **Backup** → 在 `saves/backups/<base>-<ts>.msav` 生成时间戳备份；
   - **Delete** → 弹确认对话框；
   - **Export ZIP** → FileChooser 选目标，写单文件 zip；
4. 跨版本恢复需要把备份文件复制到目标实例的 `saves/` 后再启动。

## 6. 蓝图

侧栏 → "Mindustry Schematics"：

1. **Import .msch**：FileChooser 选 .msch；
2. **Import (paste base64)**：粘贴游戏内复制的分享代码；
3. 选中条目后：
   - **Copy as base64**：复制分享代码到剪贴板；
   - **Export .msch**：保存到任意目录；
   - **Delete**：删除文件。

## 7. 服务端管理

后端能力一览（UI 完工见 W7.3 / W7.4 worker）：

- 创建实例 → 选 server jar → 自动生成 `<config>/servers/<sid>/server.json`；
- 启动 → 实时控制台（INFO 默认 / WARN 黄 / ERR 红）+ stdin 命令通道；
- 自动重启：进程 exit code != 0 且勾选 autoRestart 时按配置重试；
- `ServerConfigManager` 编辑 `config/config.json` 后自动 `reload-config`；
- 端口占用检测（`PortChecker.isPortFree(int)`）。

## 8. ScriptAgent

服务端实例的 ScriptAgent 页：

1. 一键安装：从 `way-zer/ScriptAgent4Mindustry` 拉 release，jar 进 `.data/mods/`，scripts 解压到 `.data/config/scripts/`；
2. kts 模块列表：复选启用/禁用 → 写盘后立即生效（注释 `@Module` 行）；
3. 快捷按钮 `sa scan` / `sa load <module>` / `sa hotReload <module>` 经 stdin 发出。

首次启动 server 后 kts 编译可能耗时 10–30 s，UI 会显示 "Compiling scripts…" loading 提示。

## 9. UUID 管理

侧栏 → "UUID & Nickname"：

- 新建：输入昵称 → 自动生成 22 字符 base64 UUID；
- 改名 / 删除 / 设为当前；
- 启动 Mindustry 时通过 `-Dmindustry.player.uuid` / `-Dmindustry.player.name` 传给 JVM；
- vanilla Mindustry 不读这两个 system property，但配合社区 `uuidManager` 类 mod 可生效。

## 10. 崩溃日志

- `<.data>/crashes/*.txt` 与 `last_log.txt` 自动扫描；
- 堆栈帧分类：JVM / MOD / NATIVE / UNKNOWN；mod 帧高亮黄色；
- "Submit issue" → 按 variant 跳到 `Anuken/Mindustry` 等仓库的 issue 模板；
- variant 为 CUSTOM 时复制崩溃文本到剪贴板并显示 QQ 群 `188709300`。

## 11. 自更新

启动时 `UpdateChecker` 拉取 `Metadata.XENON_UPDATE_URL`（默认 `api.github.com/repos/TinyLake/Xenon/releases/latest`）。返回 GitHub Release JSON 时取 `tag_name` 和首个 `.jar` 资产。完整性校验在 GitHub 数据下被禁用（GitHub 不直接提供 SHA-1）。

## 反馈

- B 站：<https://space.bilibili.com/1433776051>
- QQ 群：`188709300`
- GitHub Issues：<https://github.com/TinyLake/Xenon/issues>
