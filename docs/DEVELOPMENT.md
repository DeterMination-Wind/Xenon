# Xenon 开发与构建

本文档面向构建、调试和发布 Xenon 的开发者。用户操作说明见 [用户手册](USAGE.md)，完整功能列表见 [FEATURE.md](../FEATURE.md)。

## 环境要求

- JDK 17+（构建 Launcher 与运行测试）。
- Windows 单文件 `Xenon.exe` 额外需要 .NET Framework 的 `csc.exe`（`packageWindowsSingleFileExe` 会调用）。
- 仓库自带 Gradle Wrapper，不需要单独安装 Gradle。

所有命令在仓库根目录执行；本仓库统一使用 `-g .gradle-user-home`，避免污染全局 Gradle 缓存。

## 本地构建

```bash
./gradlew -g .gradle-user-home :Xenon:shadowJar
./gradlew -g .gradle-user-home :Xenon:packagePortable
./gradlew -g .gradle-user-home :Xenon:packageWindowsSingleFileExe
./gradlew -g .gradle-user-home :Xenon:packageAll
```

产物：

| 任务 | 产物 |
| --- | --- |
| `:Xenon:shadowJar` | `Xenon/build/libs/Xenon-<version>.jar` |
| `:Xenon:packagePortable` | `Xenon/build/dist/Xenon-portable-<version>.zip` |
| `:Xenon:packageWindowsSingleFileExe` | `Xenon/build/dist/Xenon.exe` |
| `:Xenon:packageAll` | 当前平台安装包 + 便携 zip + Windows 单文件 exe |

本地正式版本构建需要显式指定版本环境变量，否则产物名会带 `SNAPSHOT` / `unofficial`：

```bash
VERSION_ROOT=2.0 BUILD_NUMBER=0 VERSION_TYPE=stable \
  ./gradlew -g .gradle-user-home :Xenon:shadowJar :Xenon:packageWindowsSingleFileExe
```

产物为 `Xenon-2.0.0.jar` 与 `Xenon.exe`。

## 测试与检查

```bash
./gradlew -g .gradle-user-home :XenonCore:test
./gradlew -g .gradle-user-home :Xenon:test
./gradlew -g .gradle-user-home :Xenon:compileJava :Xenon:checkTranslations
./gradlew -g .gradle-user-home :Xenon:checkstyle :XenonCore:checkstyle
```

提交前至少运行 `:XenonCore:test`、`:Xenon:test`、`checkTranslations` 与两个 `checkstyle`。发布前再跑 `shadowJar` / `packagePortable` / 单文件 exe。

## 项目结构

- `Xenon/`：JavaFX UI、HMCL 外壳、打包与发布任务。
- `XenonCore/`：版本/下载/Mod/存档/云存档/联机/Mindustry 运行时后端。
- `XenonBoot/`：Java 8 兼容的启动引导。
- `XenonBootstrap/`：Windows 单文件 exe 的 C# 引导桩。
- `buildSrc/`、`gradle/`、`config/`：构建逻辑、依赖版本、Checkstyle 与翻译检查。
- `docs/`：用户手册、平台与本地化文档。
- `FEATURE.md`：完整功能与竞品对比。

## 发布流程

1. 更新 `config/project.properties` 的 `versionRoot`。
2. 更新 `CHANGELOG.md` 与 `release-notes/v<version>.md`。
3. 本地构建并验证 jar/exe；需要留档时复制到 `构建/Xenon/`。
4. 提交并 push `main`。
5. 创建并 push `v<version>` tag；`.github/workflows/release.yml` 会在 Windows runner 上重新构建 jar/exe、生成 SHA-256 表并创建 GitHub Release。
6. Release 必须只有一个 `.jar` 资产（安装器会取第一个 jar），可以与 `.exe` 并列；不要上传 Android/desktop 分类 jar、D8 输入包或其他中间产物。
7. 发布后检查 Release 资产、`Implementation-Version`、`Main-Class` 与工作流结果。

## 文档与本地化

- UI 文案位于 `Xenon/src/main/resources/assets/lang/I18N*.properties`。
- `I18N.properties`、`I18N_zh_CN.properties`、`I18N_zh.properties`、`I18N_lzh.properties` 需要保持同步；`checkTranslations` 会检查 zh_CN 键是否存在于英文/繁体，以及常见错别字。
- 用户文档使用中文为主、英文术语保留原文的风格。

## 关于 AI 辅助开发

本项目除 README 和部分文档外，几乎全部由 AI 生成。使用 AI 参与开发时，请始终记住：你需要对自己的代码负责。建议在 push 前用独立的 subagent 或 reviewer 审查一遍，这通常能发现不少低级问题。

## License

GPLv3（沿用 HMCL）。详见 [LICENSE](../LICENSE)。
