# Xenon 推荐榜数据生成

`generate.mjs` 从 [MDTCopper/mindustry-data-index](https://github.com/MDTCopper/mindustry-data-index)
读取模组索引、仓库创建时间和周提交数，按 `config.json` 的权重生成
`remote/mod-ranking.json`。启动器只消费生成结果，不自己计算排名。

## 权重

默认是「维护优先」：

| 组件 | 权重 | 含义 |
| --- | --- | --- |
| popularity | 0.35 | 下载量百分位 0.6 + 星标百分位 0.4 |
| activity | 0.35 | 发布频率百分位 0.6 + 近 13 周提交百分位 0.4 |
| freshness | 0.20 | 距最近一次发布的时间衰减，90 天半衰期 |
| compatibility | 0.10 | 覆盖当前正式版为 1，否则 0.6 |

发布频率按 `releaseCount / 仓库月龄` 计算，封顶 20 次/月，并做 `log1p`
柔化。排名是确定性的：没有随机项，同一份输入和配置永远得到同一顺序。

进入推荐榜的最低门槛（`eligibility`）：

- 星标 ≥ 10 **或** 下载 ≥ 1000；
- 365 天内有发布。

不满足门槛的条目仍会出现在输出里（`rank: 0`），供「社区热度」页签使用。

## 本地运行

CI 直接访问 `raw.githubusercontent.com`；本地网络不通时可以用
`MOD_RANKING_INPUT_DIR` 指向预先下载的四个文件：

```text
index-full.json
repo-created.json
commit-stats.json
versions.json
```

```powershell
$env:MOD_RANKING_INPUT_DIR = "C:\path\to\inputs"
node scripts/mod-ranking/generate.mjs
```

不带环境变量时，脚本会依次尝试直连和 `gh-proxy.net` / `ghfast.top` /
`gh.llkk.cc` 镜像。

## 自动化

`.github/workflows/mod-ranking.yml` 每天 04:00 UTC 运行一次，也可以手动
`workflow_dispatch`。只有 `remote/mod-ranking.json` 内容变化时才提交。

## 数据来源与许可

排名依赖第三方数据，请在分发或展示时保留来源说明：

- 模组索引与统计：`MDTCopper/mindustry-data-index`
- 原始模组列表：`Anuken/MindustryMods`
