#!/usr/bin/env python3
"""双端 UI 文案逐键齐全性与占位符校验（settings-roadmap P1#16 的 CI 守门）。

「19 语逐键齐全、回落面为空」是 settings-roadmap P1#16 的文档化承诺；本脚本
把它变成可执行检查，全部只读：

  Android（android/app/src/main/res）
    1. 语言目录齐全：缺省 values/（= zh-Hans）+ 18 个卫星 values-* 与路线图
       P1#16 语言目录表一一对应；含 strings.xml 的 values-* 目录不多不少。
    2. 键齐全：每个卫星 strings.xml 与缺省逐键对齐（不缺键、不多键）。
       标记 translatable="false" 的缺省键豁免翻译，卫星不得定义它们。
    3. 占位符：每键的 printf 占位符多重集（%1$s、%2$d、%%…）与缺省一致。
    3b. <plurals> 同样逐键对齐；每个数量分支（zero/one/two/few/many/other）的
        占位符多重集都须与缺省键一致（各语言分支集合由 CLDR 决定、lint 的
        MissingQuantity/UnusedQuantity 把守，本脚本不重复）。

  Windows（windows/ClipSync.App/Localization/strings.json）
    4. 语言表齐全：languages 与同一 19 语目录一致（BCP-47 标签）。
    5. 键齐全：每种语言覆盖全部键。
    6. 占位符：每键的 {n} 占位符多重集与中立语言（zh-Hans）一致。

resx 生成物与 strings.json 的同步由 CI 里「重跑 generate-windows-strings.py +
git diff --exit-code」另行把守，本脚本不重复。语言目录一经发布只增不改
（与协议字段同规）；新增语言时先改路线图 P1#16 目录表，再同步双端目录与
本脚本的 CATALOG。

用法：python3 scripts/check-i18n-parity.py
退出码：0 = 全部通过；1 = 有差异（逐条打印）。
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ANDROID_RES = REPO_ROOT / "android" / "app" / "src" / "main" / "res"
WINDOWS_STRINGS = REPO_ROOT / "windows" / "ClipSync.App" / "Localization" / "strings.json"

NEUTRAL = "zh-Hans"

# BCP-47 标签 → Android 资源目录名（None = 缺省 values/）。
# 与 docs/settings-roadmap.md P1#16 的唯一权威语言目录表逐条对应。
CATALOG: dict[str, str | None] = {
    "zh-Hans": None,
    "zh-Hant": "values-b+zh+Hant",
    "en": "values-en",
    "ja": "values-ja",
    "ko": "values-ko",
    "es": "values-es",
    "fr": "values-fr",
    "de": "values-de",
    "pt-BR": "values-pt-rBR",
    "ru": "values-ru",
    "ar": "values-ar",
    "it": "values-it",
    "vi": "values-vi",
    "th": "values-th",
    "id": "values-in",
    "hi": "values-hi",
    "tr": "values-tr",
    "pl": "values-pl",
    "nl": "values-nl",
}

# printf 风格占位符（Android getString/format）；%% 是字面百分号，也纳入比较。
ANDROID_PLACEHOLDER = re.compile(r"%%|%(?:\d+\$)?[a-zA-Z]")
# .NET string.Format 风格 {0}、{1}…（Windows 端 strings.json）。
WINDOWS_PLACEHOLDER = re.compile(r"\{\d+\}")

errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def load_android_strings(path: Path) -> tuple[dict[str, str], set[str]]:
    """返回 (可翻译键 → 文本, translatable=false 的键集合)。"""
    root = ET.parse(path).getroot()
    translatable: dict[str, str] = {}
    fixed: set[str] = set()
    for element in root.findall("string"):
        name = element.get("name")
        if not name:
            fail(f"{path}: 存在缺 name 属性的 <string>")
            continue
        text = "".join(element.itertext())
        if element.get("translatable") == "false":
            fixed.add(name)
        else:
            translatable[name] = text
    return translatable, fixed


def load_android_plurals(path: Path) -> dict[str, dict[str, str]]:
    """返回 键 → (quantity → 文本)。"""
    root = ET.parse(path).getroot()
    plurals: dict[str, dict[str, str]] = {}
    for element in root.findall("plurals"):
        name = element.get("name")
        if not name:
            fail(f"{path}: 存在缺 name 属性的 <plurals>")
            continue
        items: dict[str, str] = {}
        for item in element.findall("item"):
            quantity = item.get("quantity")
            if not quantity:
                fail(f"{path}: <plurals name={name}> 存在缺 quantity 的 <item>")
                continue
            items[quantity] = "".join(item.itertext())
        plurals[name] = items
    return plurals


def check_android() -> str:
    default_path = ANDROID_RES / "values" / "strings.xml"
    default_strings, default_fixed = load_android_strings(default_path)
    default_placeholders = {
        key: Counter(ANDROID_PLACEHOLDER.findall(text))
        for key, text in default_strings.items()
    }
    default_plurals = load_android_plurals(default_path)
    # 缺省是 zh-Hans（无复数区分，只有 other 分支）；每键的占位符基准取 other。
    default_plural_placeholders: dict[str, Counter[str]] = {}
    for key, items in default_plurals.items():
        if "other" not in items:
            fail(f"android/values: <plurals name={key}> 缺 other 分支（getQuantityString 的兜底）")
            continue
        default_plural_placeholders[key] = Counter(ANDROID_PLACEHOLDER.findall(items["other"]))

    expected_dirs = {name for name in CATALOG.values() if name is not None}
    actual_dirs = {
        entry.name
        for entry in ANDROID_RES.iterdir()
        if entry.is_dir()
        and entry.name.startswith("values-")
        and (entry / "strings.xml").is_file()
    }
    for missing in sorted(expected_dirs - actual_dirs):
        fail(f"android: 目录缺失或缺 strings.xml：{missing}（P1#16 目录表要求）")
    for extra in sorted(actual_dirs - expected_dirs):
        fail(
            f"android: 未登记的语言目录 {extra} 含 strings.xml——"
            "新增语言须先改 settings-roadmap P1#16 目录表并同步本脚本 CATALOG"
        )

    for tag, dirname in CATALOG.items():
        if dirname is None or dirname not in actual_dirs:
            continue
        path = ANDROID_RES / dirname / "strings.xml"
        strings, fixed = load_android_strings(path)
        if fixed:
            fail(f"android/{dirname}: 卫星资源不应定义 translatable=false 键：{sorted(fixed)}")
        keys = set(strings)
        default_keys = set(default_strings)
        for key in sorted(default_keys - keys):
            fail(f"android/{dirname} ({tag}): 缺键 {key}")
        for key in sorted(keys - default_keys):
            if key in default_fixed:
                fail(f"android/{dirname} ({tag}): 定义了缺省中 translatable=false 的键 {key}")
            else:
                fail(f"android/{dirname} ({tag}): 多出缺省没有的键 {key}")
        for key in sorted(keys & default_keys):
            found = Counter(ANDROID_PLACEHOLDER.findall(strings[key]))
            if found != default_placeholders[key]:
                fail(
                    f"android/{dirname} ({tag}): 键 {key} 占位符不一致——"
                    f"缺省 {sorted(default_placeholders[key].elements())}，"
                    f"实际 {sorted(found.elements())}"
                )

        plurals = load_android_plurals(path)
        plural_keys = set(plurals)
        default_plural_keys = set(default_plurals)
        for key in sorted(default_plural_keys - plural_keys):
            fail(f"android/{dirname} ({tag}): 缺复数键 {key}")
        for key in sorted(plural_keys - default_plural_keys):
            fail(f"android/{dirname} ({tag}): 多出缺省没有的复数键 {key}")
        for key in sorted(plural_keys & default_plural_keys):
            if key not in default_plural_placeholders:
                continue
            if "other" not in plurals[key]:
                fail(f"android/{dirname} ({tag}): <plurals name={key}> 缺 other 分支")
            for quantity, text in sorted(plurals[key].items()):
                found = Counter(ANDROID_PLACEHOLDER.findall(text))
                if found != default_plural_placeholders[key]:
                    fail(
                        f"android/{dirname} ({tag}): 复数键 {key} 的 {quantity} 分支占位符不一致——"
                        f"缺省 {sorted(default_plural_placeholders[key].elements())}，"
                        f"实际 {sorted(found.elements())}"
                    )

    return (
        f"Android：{len(default_strings)} 键 + {len(default_plurals)} 复数键 × "
        f"{len(expected_dirs) + 1} 语（缺省 zh-Hans + {len(expected_dirs)} 卫星）"
    )


def check_windows() -> str:
    source = json.loads(WINDOWS_STRINGS.read_text(encoding="utf-8"))
    languages: list[str] = source["languages"]
    strings: dict[str, dict[str, str]] = source["strings"]

    duplicates = {lang for lang in languages if languages.count(lang) > 1}
    if duplicates:
        fail(f"windows: languages 有重复项：{sorted(duplicates)}")
    for missing in sorted(set(CATALOG) - set(languages)):
        fail(f"windows: languages 缺少目录表语言 {missing}")
    for extra in sorted(set(languages) - set(CATALOG)):
        fail(
            f"windows: languages 含未登记语言 {extra}——"
            "新增语言须先改 settings-roadmap P1#16 目录表并同步本脚本 CATALOG"
        )

    neutral_placeholders: dict[str, Counter[str]] = {}
    for key, texts in strings.items():
        if NEUTRAL not in texts:
            fail(f"windows: 键 {key} 缺中立语言（{NEUTRAL}）文案")
            continue
        neutral_placeholders[key] = Counter(WINDOWS_PLACEHOLDER.findall(texts[NEUTRAL]))

    for lang in languages:
        if lang == NEUTRAL:
            continue
        for key, texts in strings.items():
            if lang not in texts:
                fail(f"windows ({lang}): 缺键 {key}（回落面为空是 P1#16 的文档化承诺）")
                continue
            if key not in neutral_placeholders:
                continue
            found = Counter(WINDOWS_PLACEHOLDER.findall(texts[lang]))
            if found != neutral_placeholders[key]:
                fail(
                    f"windows ({lang}): 键 {key} 占位符不一致——"
                    f"中立 {sorted(neutral_placeholders[key].elements())}，"
                    f"实际 {sorted(found.elements())}"
                )

    return f"Windows：{len(strings)} 键 × {len(languages)} 语"


def main() -> int:
    summaries = [check_android(), check_windows()]
    if errors:
        for line in errors:
            print(f"error: {line}", file=sys.stderr)
        print(f"\n{len(errors)} 处差异。", file=sys.stderr)
        return 1
    print("；".join(summaries) + "——逐键齐全，占位符一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
