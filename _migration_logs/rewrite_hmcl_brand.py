#!/usr/bin/env python3
"""T11: rewrite HMCL/Hello-Minecraft brand strings -> Xenon in i18n properties.

- Skips lines starting with '#': preserves headers, comments, license blocks.
- Substitutes only outside-URL HMCL token usages so URLs stay intact.
- Appends 4 xenon.about.* keys at the end of each file.
- Idempotent: skips appending keys that already exist; URL/text replacements
  do nothing on already-rewritten text.
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANG = ROOT / "Xenon/src/main/resources/assets/lang"

FILES = [
    "I18N.properties",
    "I18N_zh_CN.properties",
    "I18N_zh.properties",
    "I18N_lzh.properties",
    "I18N_ja.properties",
    "I18N_es.properties",
    "I18N_ru.properties",
    "I18N_uk.properties",
    "I18N_ar.properties",
]

NEW_KEYS_EN = [
    "xenon.about.bilibili=Bilibili: https://space.bilibili.com/1433776051",
    "xenon.about.qq_group=QQ Group: 188709300",
    "xenon.about.github=GitHub: https://github.com/TinyLake/Xenon",
    "xenon.about.upstream=Built on top of HMCL (https://github.com/HMCL-dev/HMCL).",
]

NEW_KEYS_ZH_CN = [
    "xenon.about.bilibili=B 站：https://space.bilibili.com/1433776051",
    "xenon.about.qq_group=QQ 群：188709300",
    "xenon.about.github=GitHub：https://github.com/TinyLake/Xenon",
    "xenon.about.upstream=基于 HMCL (https://github.com/HMCL-dev/HMCL) 构建。",
]

NEW_KEYS_ZH = [
    "xenon.about.bilibili=B 站：https://space.bilibili.com/1433776051",
    "xenon.about.qq_group=QQ 群：188709300",
    "xenon.about.github=GitHub：https://github.com/TinyLake/Xenon",
    "xenon.about.upstream=承 HMCL (https://github.com/HMCL-dev/HMCL) 而建。",
]

NEW_KEYS_LZH = [
    "xenon.about.bilibili=嗶站：https://space.bilibili.com/1433776051",
    "xenon.about.qq_group=QQ 羣：188709300",
    "xenon.about.github=GitHub：https://github.com/TinyLake/Xenon",
    "xenon.about.upstream=承 HMCL (https://github.com/HMCL-dev/HMCL) 而建。",
]

NEW_KEYS_BY_FILE = {
    "I18N.properties": NEW_KEYS_EN,
    "I18N_zh_CN.properties": NEW_KEYS_ZH_CN,
    "I18N_zh.properties": NEW_KEYS_ZH,
    "I18N_lzh.properties": NEW_KEYS_LZH,
    "I18N_ja.properties": NEW_KEYS_EN,
    "I18N_es.properties": NEW_KEYS_EN,
    "I18N_ru.properties": NEW_KEYS_EN,
    "I18N_uk.properties": NEW_KEYS_EN,
    "I18N_ar.properties": NEW_KEYS_EN,
}

URL_SPLIT = re.compile(r'(https?://[^\s"<>]+)')
HMCL_TOKEN = re.compile(r'\bHMCL\b')


def replace_hmcl_token_outside_urls(text: str) -> str:
    parts = URL_SPLIT.split(text)
    for i in range(len(parts)):
        if i % 2 == 0:  # not a URL
            parts[i] = HMCL_TOKEN.sub("Xenon", parts[i])
    return "".join(parts)


def fix_value(text: str) -> str:
    # Specific URL replacements (do these BEFORE generic HMCL token replacement)
    text = text.replace("https://hmcl.huangyuhui.net/download", "https://github.com/TinyLake/Xenon/releases")
    text = text.replace("http://hmcl.huangyuhui.net/download", "https://github.com/TinyLake/Xenon/releases")
    text = text.replace("https://hmcl.huangyuhui.net", "https://github.com/TinyLake/Xenon")
    text = text.replace("http://hmcl.huangyuhui.net", "https://github.com/TinyLake/Xenon")
    text = text.replace("https://docs.hmcl.net/help.html", "https://github.com/TinyLake/Xenon/blob/main/docs")
    text = text.replace("https://docs.hmcl.net/groups.html", "https://github.com/TinyLake/Xenon")
    text = text.replace("https://docs.hmcl.net", "https://github.com/TinyLake/Xenon/blob/main/docs")
    text = text.replace("https://space.bilibili.com/1445341", "https://space.bilibili.com/1433776051")
    text = text.replace("https://space.bilibili.com/20314891", "https://space.bilibili.com/1433776051")
    text = text.replace("https://github.com/HMCL-dev/HMCL/graphs/contributors", "https://github.com/TinyLake/Xenon/graphs/contributors")
    text = text.replace("https://github.com/HMCL-dev/HMCL/issues/new/choose", "https://github.com/TinyLake/Xenon/issues/new")
    text = text.replace("GPL v3 (https://github.com/HMCL-dev/HMCL)", "GPL v3 (https://github.com/TinyLake/Xenon)")

    # Brand strings: launcher name in various locales
    text = text.replace("Hello Minecraft! Crash Report", "Xenon Crash Report")
    text = text.replace("Hello Minecraft! Launcher", "Xenon Launcher")
    text = text.replace("Hello Minecraft! Лаунчером", "Xenon Launcher")
    text = text.replace("Hello Minecraft! Лаунчера", "Xenon Launcher")
    text = text.replace("Hello Minecraft! Лаунчер", "Xenon Launcher")
    text = text.replace("Hello Minecraft！ランチャーがクラッシュしました", "Xenon Launcher がクラッシュしました")
    text = text.replace("Hello Minecraftをダウンロードすることで", "Xenon Launcher をダウンロードすることで")
    text = text.replace("Hello Minecraftを再開してください！ランチャー", "Xenon Launcher を再開してください")
    text = text.replace("Hello Minecraftでインポートできます！ランチャー", "Xenon Launcher により導入可能")
    text = text.replace("Hello Minecraft！ランチャー", "Xenon Launcher")
    text = text.replace("Hello Minecraft！", "Xenon Launcher")
    text = text.replace("Hello Minecraft!", "Xenon Launcher")

    # huangyuhui copyright lines (about.copyright.statement values)
    text = text.replace(
        "Copyright © 2013-2026 huangyuhui and contributors.",
        "Copyright © 2013-2026 Xenon contributors.",
    )
    text = text.replace(
        "版权所有 © 2013-2026 huangyuhui 及贡献者",
        "版权所有 © 2013-2026 Xenon 贡献者",
    )
    text = text.replace(
        "著作權所有 © 2013-2026 huangyuhui 與貢獻者",
        "著作權所有 © 2013-2026 Xenon 貢獻者",
    )
    text = text.replace(
        "حقوق النشر © 2013-2026 huangyuhui والمساهمون.",
        "حقوق النشر © 2013-2026 Xenon contributors.",
    )

    # bilibili author tag in about.author.statement
    text = text.replace("bilibili @huanghongxun", "Xenon Launcher")

    # Inline @author in HTML link text
    text = text.replace("@huanghongxun", "Xenon Launcher")
    text = text.replace("@Glavo", "Xenon Launcher")

    # HMCL token (only outside URLs)
    text = replace_hmcl_token_outside_urls(text)

    return text


def process_file(path: Path, new_keys: list[str]) -> None:
    raw = path.read_bytes().decode("utf-8")
    # Preserve LF line endings
    lines = raw.split("\n")
    out_lines: list[str] = []
    for line in lines:
        if line.startswith("#"):
            out_lines.append(line)
            continue
        out_lines.append(fix_value(line))
    text = "\n".join(out_lines)

    if not text.endswith("\n"):
        text += "\n"

    existing_keys: set[str] = set()
    for ln in text.split("\n"):
        if "=" in ln and not ln.lstrip().startswith("#"):
            existing_keys.add(ln.split("=", 1)[0].strip())

    appended = []
    for kv in new_keys:
        key = kv.split("=", 1)[0].strip()
        if key not in existing_keys:
            appended.append(kv)
            existing_keys.add(key)

    if appended:
        if not text.endswith("\n\n") and text.endswith("\n"):
            # Ensure exactly one trailing newline; add a separator blank line.
            pass
        text = text + "\n" + "\n".join(appended) + "\n"

    path.write_bytes(text.encode("utf-8"))
    print(f"  rewrote {path.relative_to(ROOT)} appended_keys={len(appended)}")


def main() -> int:
    print(f"# Lang dir: {LANG}")
    for name in FILES:
        process_file(LANG / name, NEW_KEYS_BY_FILE[name])
    return 0


if __name__ == "__main__":
    sys.exit(main())
