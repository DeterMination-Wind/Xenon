# Xenon 用户手册

本手册面向 Xenon 1.14，按功能给出操作路径与预期结果，方便上手和排错。

## 1. 安装 Xenon

### 1.1 Portable(便携版)

1. 解压 `Xenon-portable-<版本>.zip` 到任意目录；
2. Windows 双击 `Xenon.bat`；Linux/macOS 执行 `chmod +x Xenon.sh && ./Xenon.sh`；
3. 第一次启动会创建数据目录：
   - Windows: `%APPDATA%\Xenon`
   - Linux: `~/.xenon`(或 `$XDG_DATA_HOME/Xenon`)
   - macOS: `~/Library/Application Support/Xenon`

### 1.2 系统包(msi / dmg / deb / AppImage)

`./gradlew :Xenon:packageAll` 在对应系统上产出安装包；安装后行为与便携版一致，所有用户数据仍写入上述配置目录。

### 1.3 便携数据模式(数据随程序)

适合放在 U 盘或移动硬盘：

1. 设置 → 下载 → 「缓存目录」选择 **便携模式(数据保存在 Xenon.jar 同目录)**；
2. 设置页会在启动器目录写入 `Xenon.portable` 标记，并在其旁边创建 `XenonData/`；
3. **重启 Xenon** 后生效：实例、存档、Java、缓存等全部改存 `XenonData/`；
4. 想改回默认目录：删除 `Xenon.portable` 标记并重启，数据会回到 `%APPDATA%\Xenon`。

> 安装包版本不提供该选项(程序目录由安装器管理)。

校验：重启后设置页显示的目录应为 `<程序目录>\XenonData`。

## 2. 网络与下载设置

设置 → 下载：

- **GitHub 账号(可选)**:粘贴个人访问令牌后点「验证」，可把 GitHub API 限额从每小时 60 次提升到 5000 次，避免版本列表、mod 索引因限流加载失败。令牌只用于直连 `api.github.com` 的请求，不会发给任何镜像站。
- **缓存目录 / 下载线程数 / 代理**:与原版 Xenon 一致，按需调整。

下载过程中的行为：

- **断点续传**:中断(网络失败、关闭启动器)后重新下载会从已下载的字节继续；
- **暂停 / 继续**:下载进度对话框里有「暂停」按钮，暂停时不会占用带宽，继续后从原位置接续；
- **取消即清理**:点「取消」会停止下载并删除临时文件；
- **镜像自动回退**:优先使用 MDT 文件站与国内镜像，不可用时自动切换，全程无需手动干预。

校验：暂停后进度条不再增长；继续或重启后重新下载时进度从上次位置开始。

## 3. 安装一个 Mindustry 变体

1. 侧栏点 "Install Mindustry"；
2. **第 1 步 — 变体**:Vanilla / Bleeding-Edge / MindustryX / CN-ARC / Foo Client；
3. **第 2 步 — 版本**:版本列表按时代(V4/V5/V6/V7/V8)与 Java 需求标注；旧版本选中时会提示"不支持现代 mod 与多人协议"。有「更新说明」按钮可查看 release notes。断网且无缓存时会显示内置离线快照；
4. **第 3 步 — 命名/隔离**:
   - 版本 id(默认 `<变体>-<build>`)；只接受字母/数字/`._-`；
   - 数据目录策略:
     - **Isolated(推荐)**:保存在 `<config>/versions/<id>/.data/`；
     - **Global**:与官方 Mindustry 数据目录共享；
     - **Custom**:填写绝对路径；
   - 勾选「从现有实例复制数据」可把已有实例的存档、mod、蓝图、地图和游玩统计复制进新实例；
5. **第 4 步 — 预装 mod**(可选):选择若干社区 mod 一同安装；点 "Skip" 或 "Install selected"；
6. 完成后侧栏 "Mindustry Versions" 出现新条目，点 "Launch" 启动。

校验：新实例能在多实例间独立启停，互不覆盖存档与 mod。

## 4. 导入本地 jar

1. 侧栏点 "Import Mindustry jar"；
2. FileChooser 选 `.jar`；
3. 输入 id；
4. Xenon 自动从文件名识别 build / 变体 / Java 需求；保存到 `<config>/versions/<id>/<id>.jar` 并启动。

## 5. Mod 管理

侧栏 → "Mindustry Versions" 选实例 → "Mod 管理" 页：

- 已装 mod:启用/禁用(重命名 `.disabled` 后缀，同时写入游戏设置)、删除；
- 社区索引:从 `Anuken/mindustry-mods` 拉取，按 ★ 排序，"Install" 走 GitHub Release；
- GitHub 直装:输入 `owner/repo`；
- 缺失依赖时弹「自动补齐」对话框；`minGameVersion > 当前 build` 会高亮警告。

删除说明:mod、存档、蓝图、地图以及整个实例都优先移入系统回收站，误删可从回收站找回。

## 6. 存档与云存档

侧栏 → "Mindustry Versions" 选实例 → "存档" 页：

- 列表显示 mapName / build / wave / 修改时间；
- **Backup** → 在 `saves/backups/<base>-<时间戳>.msav` 生成备份；
- **Export ZIP** → 导出为单文件 zip；
- **Delete** → 移入系统回收站；
- 选择某个存档归档启动时，日志与崩溃报告也会跟随该数据目录。

登录 MDTBBS 后还提供**云存档**:云端槽位与历史快照、上传、一键恢复(恢复前自动备份本地文件)、固定重要版本并显示额度。

## 7. 蓝图

侧栏 → "Mindustry Versions" 选实例 → "蓝图" 页：

1. **Import .msch**:FileChooser 选 `.msch`；
2. **Import (paste base64)**:粘贴游戏内复制的分享代码；
3. 选中条目后可 **Copy as base64** / **Export .msch** / **Delete**。

## 8. 地图与资源

- 地图:侧栏下载页的「地图」可浏览并安装 [mindustry.top](https://mindustry.top) 上的地图；
- 资源:下载页的「资源」可搜索 MDTBBS 上的蓝图与地图，预览后一键安装到指定实例，也可以把本地蓝图或地图提交审核。

## 9. 联机

侧栏「联机」页包含三种方式，可按场景选用。

### 9.1 公共服务器

- 列表来自官方 Mindustry 服务器目录(自动走镜像、失败时读缓存)；
- 「复制地址」复制 `host:port`；「启动游戏」会先复制地址再启动选定实例，随后在游戏「加入服务器」中粘贴即可。

### 9.2 MDTBBS 联机(官方中继)

需要先登录 MDTBBS(社区页登录即可)。房主与客人操作如下：

1. **房主**:选好房间预设后点「创建房间」：
   - 好友可直接加入 / 仅邀请:好友在 MDTBBS 好友页看到「可加入」；
   - 使用加入码:创建后点「复制加入码」，把加入码发给好友；
2. 房主在游戏内正常选择「多人游戏 → 主持游戏」；
3. **客人**:在「加入房间」输入加入码(或会话 id)后加入；
4. 客人等待状态变为「中继已连接」后，点「复制地址」，在游戏「加入服务器」中粘贴 `127.0.0.1:<端口>` 一次。

说明:

- 数据经 MDTBBS 官方中继转发，双方无需公网 IP；凭证自动续期；
- 房主也可以在房间内点「邀请好友」，从好友列表直接发出邀请；
- 从 MDTBBS 网页或好友面板发起的邀请会通过 `xenon://join?intent=` 链接唤起启动器，确认后自动加入并准备地址；
- 遇到「中继拒绝了联机凭证」时，先点「离开房间」，再重新创建房间(凭证为一次性)。

### 9.3 EasyTier P2P 房间(六位房号)

无需登录 MDTBBS 的备用方案：

1. 第一次使用会提示下载 EasyTier(约 20 MB)；
2. 房主点「随机房号」或输入六位房号 →「启动联机」；
3. 双方填写相同房号并启动联机，等待成员列表出现对方；
4. 房主在游戏内「主持游戏」，客人用「复制主机地址」得到的 `虚拟IP:6567` 加入。

> Windows 下 EasyTier 需要管理员权限创建虚拟网卡；启动失败时界面会提示以管理员身份重启 Xenon。

## 10. 实例工具

"实例"页(选中某个实例后)提供：

- **健康检查**:显示实例信息、游玩时长、兼容性问题，以及**推荐内存**——自动值会结合已启用 mod 的体积计算(显式 `-Xmx` 优先)；
- **迁移数据**:从其他实例复制存档、mod、蓝图、地图、`settings.bin` 与游玩统计，可选择是否覆盖同名文件；
- **导出 .xenon**:把整个实例打包，便于迁移或分享；
- **日志**:每次启动的进程输出单独保存在实例目录 `logs/`(保留最近 10 份)，游戏自身日志缺失时也能查看报错；崩溃报告会分类堆栈帧并高亮可疑 mod。

## 11. 服务端管理

侧栏「服务器」:

- 创建实例 → 选 server jar → 自动生成 `<config>/servers/<sid>/server.json`；
- 启动 → 实时控制台(INFO / WARN / ERR 分色)+ stdin 命令通道；
- 自动重启:进程异常退出且勾选 autoRestart 时按配置重试；
- `ServerConfigManager` 编辑 `config/config.json` 后自动 `reload-config`；
- 端口占用检测(`PortChecker.isPortFree(int)`)；服务端地图池与 mod 同步。

## 12. ScriptAgent

服务端实例的 ScriptAgent 页：

1. 一键安装:从 `way-zer/ScriptAgent4Mindustry` 拉取 release，jar 进 `.data/mods/`，scripts 解压到 `.data/config/scripts/`；
2. kts 模块列表:复选启用/禁用，写盘后立即生效；
3. 快捷按钮 `sa scan` / `sa load <module>` / `sa hotReload <module>` 经 stdin 发出。

首次启动 server 后 kts 编译可能耗时 10–30 秒，界面会显示 "Compiling scripts…"。

## 13. UUID 管理

侧栏 → "UUID & Nickname"：

- 新建:输入昵称 → 自动生成 22 字符 base64 UUID；
- 改名 / 删除 / 设为当前；
- 启动 Mindustry 时通过 `-Dmindustry.player.uuid` / `-Dmindustry.player.name` 传给 JVM，并写入实例的 `settings.bin`；
- vanilla Mindustry 不读这两个 system property，但配合社区 `uuidManager` 类 mod 可生效。

## 14. 自更新

启动时 `UpdateChecker` 拉取 `Metadata.XENON_UPDATE_URL`(默认指向 GitHub Release)。返回 Release JSON 时取 `tag_name` 和首个 `.jar` 资产；完整性校验在 GitHub 数据下被禁用(GitHub 不直接提供 SHA-1)。

## 反馈

- B 站:<https://space.bilibili.com/1433776051>
- QQ 群:`188709300`
- GitHub Issues:<https://github.com/DeterMination-Wind/Xenon/issues>
