#!/usr/bin/env python3
"""data/municipalities.json を気象庁の予報区データから生成する。

ソース: 気象庁 area.json(https://www.jma.go.jp/bosai/common/const/area.json)
  - class10s: 一次細分区域(例: 埼玉県「南部」) → level="primary" のグループ
  - class15s: 市町村等をまとめた地域(例: 東京都「23区西部」) → level="detail" のグループ
  - class20s: 市町村等 → 自治体マスタ本体
利用条件: 気象庁ホームページは政府標準利用規約(第2.0版)準拠(CC BY 4.0 互換)。

class20 は防災情報の発表単位のため、そのままでは自治体と 1:1 にならない。
以下の正規化で「全国地方公共団体コード 5桁 + 名称」の自治体マスタに揃える:
  1. 地域分割(釧路市釧路/阿寒/音別 等 40 市町村)  → 5桁コード(コード先頭5桁)で統合
  2. 括弧付き分割(佐世保市（宇久地域）等)          → 「（」以降を落として統合
  3. 政令市の区分割(神戸市・広島市のみ)            → 区名を落とし市コード(SEIREI_CITIES)へ統合

気象庁区分に無いが登録ニーズのあるグループ(東京23区 等)は EXTRA_GROUPS で補完する
(level="custom")。気象庁 class20 に存在しない自治体(北方領土 6 村等)があれば
EXTRA_MUNICIPALITIES で補う。

使い方:
  python3 scripts/generate_municipalities.py            # JMA からダウンロードして生成
  python3 scripts/generate_municipalities.py --input area.json  # ローカルファイルから生成
生成後、旧ファイルとの (都道府県, 自治体名) 差分を表示する。
"""

import argparse
import json
import re
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

JMA_AREA_URL = "https://www.jma.go.jp/bosai/common/const/area.json"
OUTPUT = Path(__file__).resolve().parent.parent / "data" / "municipalities.json"

PREFECTURES = {
    "01": "北海道", "02": "青森県", "03": "岩手県", "04": "宮城県", "05": "秋田県",
    "06": "山形県", "07": "福島県", "08": "茨城県", "09": "栃木県", "10": "群馬県",
    "11": "埼玉県", "12": "千葉県", "13": "東京都", "14": "神奈川県", "15": "新潟県",
    "16": "富山県", "17": "石川県", "18": "福井県", "19": "山梨県", "20": "長野県",
    "21": "岐阜県", "22": "静岡県", "23": "愛知県", "24": "三重県", "25": "滋賀県",
    "26": "京都府", "27": "大阪府", "28": "兵庫県", "29": "奈良県", "30": "和歌山県",
    "31": "鳥取県", "32": "島根県", "33": "岡山県", "34": "広島県", "35": "山口県",
    "36": "徳島県", "37": "香川県", "38": "愛媛県", "39": "高知県", "40": "福岡県",
    "41": "佐賀県", "42": "長崎県", "43": "熊本県", "44": "大分県", "45": "宮崎県",
    "46": "鹿児島県", "47": "沖縄県",
}

# 政令指定都市: 市名 → 5桁コード。気象庁 class20 が区単位で収録する市(現状は神戸市・広島市)を
# 市へ集約する際のコード解決に使う。区コードから市コードは機械的に導けない(福岡市 40130 の
# 区は 4013x だが北九州市 40100 の区も 401xx)ため固定表を持つ。
SEIREI_CITIES = {
    "札幌市": "01100", "仙台市": "04100", "さいたま市": "11100", "千葉市": "12100",
    "横浜市": "14100", "川崎市": "14130", "相模原市": "14150", "新潟市": "15100",
    "静岡市": "22100", "浜松市": "22130", "名古屋市": "23100", "京都市": "26100",
    "大阪市": "27100", "堺市": "27140", "神戸市": "28100", "岡山市": "33100",
    "広島市": "34100", "北九州市": "40100", "福岡市": "40130", "熊本市": "43100",
}

WARD_RE = re.compile(r"^(.+?市)(.+区)$")
FULLWIDTH_DIGITS = str.maketrans("０１２３４５６７８９", "0123456789")

# 気象庁区分に無い補完グループ。municipalities は自治体名で指定し、コードへ解決できなければ
# エラーにする(名称変更への追従漏れを検知するため)
EXTRA_GROUPS = [
    {
        "prefecture": "13",
        "id": "custom-tokyo23",
        "name": "東京23区",
        "municipalities": [
            "千代田区", "中央区", "港区", "新宿区", "文京区", "台東区", "墨田区", "江東区",
            "品川区", "目黒区", "大田区", "世田谷区", "渋谷区", "中野区", "杉並区", "豊島区",
            "北区", "荒川区", "板橋区", "練馬区", "足立区", "葛飾区", "江戸川区",
        ],
    },
]

# 気象庁 class20 に存在しない自治体の補完(コード, 名称)。北方領土 6 村が該当するが、
# ピッカーの実用性が無い(施策も存在し得ない)ため意図的に収載しない。他に欠落が見つかったら
# ここへ追加する
EXTRA_MUNICIPALITIES: list[tuple[str, str]] = []


def normalize_name(name: str) -> str:
    """括弧付き分割(佐世保市（宇久地域）等)の括弧以降を落とし、全角数字を半角へ。"""
    return re.split(r"[（(]", name)[0].translate(FULLWIDTH_DIGITS)


def common_prefix(names: list[str]) -> str:
    prefix = names[0]
    for name in names[1:]:
        while not name.startswith(prefix):
            prefix = prefix[:-1]
    return prefix


def build_municipalities(class20s: dict) -> tuple[dict[str, str], dict[str, str]]:
    """class20 を自治体(5桁コード → 名称)へ正規化する。

    戻り値: (自治体コード → 名称, class20 コード → 自治体コード)
    """
    # 1. 地域分割・括弧付き分割を5桁コードで統合(名称は共通接頭辞)
    by5: dict[str, list[str]] = defaultdict(list)
    code_of_class20: dict[str, str] = {}
    for c20, entry in class20s.items():
        by5[c20[:5]].append(normalize_name(entry["name"]))
        code_of_class20[c20] = c20[:5]
    municipalities: dict[str, str] = {}
    for code5, names in by5.items():
        name = common_prefix(names)
        if not name:
            sys.exit(f"共通接頭辞を導けない分割自治体: {code5} {names}")
        municipalities[code5] = name

    # 2. 政令市の区分割を市へ統合
    ward_remap: dict[str, str] = {}
    for code5, name in list(municipalities.items()):
        m = WARD_RE.match(name)
        if not m:
            continue
        city_name = m.group(1)
        city_code = SEIREI_CITIES.get(city_name)
        if city_code is None:
            sys.exit(f"政令市表に無い区分割: {name}(SEIREI_CITIES に {city_name} を追加すること)")
        del municipalities[code5]
        municipalities[city_code] = city_name
        ward_remap[code5] = city_code
    for c20, code5 in code_of_class20.items():
        code_of_class20[c20] = ward_remap.get(code5, code5)
    return municipalities, code_of_class20


def build_groups(data: dict, code_of_class20: dict[str, str]) -> dict[str, list[dict]]:
    """class10(primary)/class15(detail) を都道府県ごとのグループへ展開する。

    - 自治体1つのグループは登録単位として意味が無いので落とす
    - primary と構成が同一の detail(例: 秩父地方)は重複なので落とす
    - 並び順は primary → その配下の detail(ピッカーの表示順にそのまま使う)
    """
    class15_members: dict[str, list[str]] = {}
    for c15, entry in data["class15s"].items():
        members = sorted({code_of_class20[c20] for c20 in entry["children"] if c20 in code_of_class20})
        class15_members[c15] = members

    groups_by_pref: dict[str, list[dict]] = defaultdict(list)
    for c10, entry in sorted(data["class10s"].items()):
        pref = c10[:2]
        children = [c15 for c15 in sorted(entry.get("children", [])) if c15 in class15_members]
        primary_members = sorted({code for c15 in children for code in class15_members[c15]})
        primary = {
            "id": f"jma10-{c10}",
            "name": normalize_name(entry["name"]),
            "level": "primary",
            "municipalities": primary_members,
        }
        details = [
            {
                "id": f"jma15-{c15}",
                "name": normalize_name(data["class15s"][c15]["name"]),
                "level": "detail",
                "municipalities": class15_members[c15],
            }
            for c15 in children
        ]
        # detail が1つしか無い primary は detail と同一構成なので detail 側を落とす
        details = [d for d in details if len(d["municipalities"]) >= 2 and d["municipalities"] != primary_members]
        if len(primary["municipalities"]) >= 2:
            groups_by_pref[pref].append(primary)
        groups_by_pref[pref].extend(details)
    return groups_by_pref


def attach_extras(
    municipalities: dict[str, str], groups_by_pref: dict[str, list[dict]]
) -> None:
    for code, name in EXTRA_MUNICIPALITIES:
        if code in municipalities and municipalities[code] != name:
            sys.exit(f"補完自治体のコード衝突: {code} {name} vs {municipalities[code]}")
        municipalities.setdefault(code, name)
    for extra in EXTRA_GROUPS:
        pref = extra["prefecture"]
        name_to_code = {
            name: code for code, name in municipalities.items() if code[:2] == pref
        }
        codes = []
        for name in extra["municipalities"]:
            if name not in name_to_code:
                sys.exit(f"補完グループ {extra['name']} の自治体が見つからない: {name}")
            codes.append(name_to_code[name])
        group = {
            "id": extra["id"],
            "name": extra["name"],
            "level": "custom",
            "municipalities": sorted(codes),
        }
        # 既存グループと構成が同一なら補完不要になったということなので気付けるよう警告だけ出す
        for g in groups_by_pref[pref]:
            if g["municipalities"] == group["municipalities"]:
                print(f"note: 補完グループ {extra['name']} は {g['name']}({g['id']}) と同一構成")
        groups_by_pref[pref].insert(0, group)


def diff_against_old(old_path: Path, prefectures: list[dict]) -> None:
    if not old_path.exists():
        return
    old = json.loads(old_path.read_text(encoding="utf-8"))
    if isinstance(old, dict) and "prefectures" in old:
        old_set = {
            (p["name"], m["name"]) for p in old["prefectures"] for m in p["municipalities"]
        }
    else:  # 旧v1(都道府県名 → 名称リスト)
        old_set = {(pref, name) for pref, names in old.items() for name in names}
    new_set = {(p["name"], m["name"]) for p in prefectures for m in p["municipalities"]}
    for label, diff in (("旧のみ", old_set - new_set), ("新のみ", new_set - old_set)):
        if diff:
            print(f"{label} ({len(diff)}件): {sorted(diff)}")
    if old_set == new_set:
        print("旧ファイルと自治体集合は一致")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", help="ローカルの area.json(省略時は気象庁からダウンロード)")
    args = parser.parse_args()

    if args.input:
        data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(JMA_AREA_URL) as res:
            data = json.loads(res.read().decode("utf-8"))

    municipalities, code_of_class20 = build_municipalities(data["class20s"])
    groups_by_pref = build_groups(data, code_of_class20)
    attach_extras(municipalities, groups_by_pref)

    by_pref: dict[str, list[dict]] = defaultdict(list)
    for code, name in sorted(municipalities.items()):
        pref = code[:2]
        if pref not in PREFECTURES:
            sys.exit(f"不明な都道府県コード: {code} {name}")
        by_pref[pref].append({"code": code, "name": name})
    prefectures = [
        {
            "code": pref,
            "name": PREFECTURES[pref],
            "municipalities": by_pref[pref],
            "groups": groups_by_pref.get(pref, []),
        }
        for pref in sorted(by_pref)
    ]

    diff_against_old(OUTPUT, prefectures)

    output = {
        "version": 2,
        "source": "気象庁 予報区等(市町村等)一覧 " + JMA_AREA_URL,
        "prefectures": prefectures,
    }
    OUTPUT.write_text(
        json.dumps(output, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    total = sum(len(p["municipalities"]) for p in prefectures)
    total_groups = sum(len(p["groups"]) for p in prefectures)
    print(f"wrote {OUTPUT}: {len(prefectures)}都道府県 {total}自治体 {total_groups}グループ")


if __name__ == "__main__":
    main()
