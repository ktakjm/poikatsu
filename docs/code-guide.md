# poikatsu コード解説（学習用）

店舗名やカテゴリから「どの支払い方法が最も得か」を判定する Android アプリのコード解説ドキュメント。
Kotlin + Jetpack Compose の標準的な構成（MVVM + Repository）を学ぶための題材として、各レイヤの責務・設計判断・データフローを説明する。

- 対象リビジョン: Phase 1 完了 + 店舗単位の対象判定（公式リスト方式・3 状態）+ GPS 周辺検索 実装済み時点
- 全体計画: [PLAN.md](../PLAN.md) / データ仕様: [data/README.md](../data/README.md) / プロジェクト規約: [CLAUDE.md](../CLAUDE.md)

## 1. 全体アーキテクチャ

MVVM + Repository パターン。レイヤ間の依存は一方向（UI → Domain → Data）で、判定ロジック（domain/）と日本語処理（util/）は **Android 非依存の純 Kotlin** として書かれており、JVM ユニットテストで実データを使って検証できる。

```mermaid
graph TD
    subgraph ui["UI レイヤ（ui/）"]
        ACT["MainActivity<br/>（Compose エントリポイント）"]
        SCR["SearchScreen.kt<br/>（Composable 群）"]
        VM["MainViewModel<br/>（UiState を StateFlow で公開）"]
        MAP["NearbyMap.kt<br/>（osmdroid 地図ラッパ・差し替え境界）"]
    end
    subgraph domain["ドメインレイヤ（domain/）Android 非依存"]
        ENG["JudgmentEngine<br/>（検索・チェーン特定・判定）"]
    end
    subgraph dat["データレイヤ（data/）"]
        REPO["DataRepository<br/>（ロード戦略: cache→assets / remote）"]
        MODELS["Models.kt<br/>（kotlinx.serialization）"]
        GH["GithubRawClient<br/>（施策 JSON フェッチ）"]
        OP["OverpassClient<br/>（OSM 周辺店舗検索）"]
        LOC["LocationProvider<br/>（現在地 1 回取得）"]
    end
    subgraph util["ユーティリティ（util/）Android 非依存"]
        JT["JapaneseText<br/>（検索用正規化）"]
        GEO["GeoMath<br/>（ハバーサイン距離）"]
    end
    subgraph ext["外部"]
        RAW["GitHub raw<br/>data/*.json"]
        OSM["Overpass API<br/>（OpenStreetMap）"]
        OSMTILE["OSM タイルサーバ<br/>（osmdroid 地図描画）"]
    end

    ACT --> SCR
    SCR --> VM
    SCR --> MAP
    MAP --> OSMTILE
    VM --> ENG
    VM --> REPO
    VM --> OP
    VM --> LOC
    VM --> GEO
    ENG --> JT
    ENG --> MODELS
    REPO --> MODELS
    REPO --> GH
    GH --> RAW
    OP --> OSM

    style ACT fill:#1565C0,stroke:#0D47A1,color:#fff
    style SCR fill:#1565C0,stroke:#0D47A1,color:#fff
    style VM fill:#1565C0,stroke:#0D47A1,color:#fff
    style ENG fill:#2E7D32,stroke:#1B5E20,color:#fff
    style REPO fill:#E65100,stroke:#BF360C,color:#fff
    style MODELS fill:#E65100,stroke:#BF360C,color:#fff
    style GH fill:#E65100,stroke:#BF360C,color:#fff
    style OP fill:#E65100,stroke:#BF360C,color:#fff
    style LOC fill:#E65100,stroke:#BF360C,color:#fff
    style JT fill:#6A1B9A,stroke:#4A148C,color:#fff
    style GEO fill:#6A1B9A,stroke:#4A148C,color:#fff
    style RAW fill:#E0E0E0,stroke:#9E9E9E,color:#333
    style OSM fill:#E0E0E0,stroke:#9E9E9E,color:#333
    style OSMTILE fill:#E0E0E0,stroke:#9E9E9E,color:#333
    style MAP fill:#1565C0,stroke:#0D47A1,color:#fff
```

### 設計判断のポイント

| 判断 | 理由 |
|---|---|
| Room を使わない | 施策データは数十件の JSON で全件メモリに乗る。リモート JSON をファイルキャッシュ（テキストのまま保存）するだけで十分だった（PLAN.md M4 実績メモ参照） |
| DI フレームワーク（Hilt）なし | クラス数が少なく手動 DI で足りる。`DataRepository` は関数（`readAsset` / `fetchRemote`）と `File` をコンストラクタ注入する形にして、フレームワークなしでテスタビリティを確保 |
| Google Play Services 不使用 | 位置情報はフレームワーク標準の `LocationManager`、地図は osmdroid（OSM）で実装し、プロプライエタリ依存を増やさない。Google Maps SDK は地図表示自体は無料無制限だが Play Services 依存＋API キー＋請求先登録が必要なため不採用（判断の経緯は docs/licenses.md） |
| 判定ロジックは純 Kotlin | `domain/` と `util/` は Android API に触れないため、実データ（`data/*.json`）を読むユニットテストが JVM 上で高速に回る |
| ロゴ画像不使用 | 商標・著作権リスク回避。`campaigns.json` の `brand_color`（#RRGGBB）で発行体を識別 |

## 2. ディレクトリ構成

```
poikatsu/
├── PLAN.md                 # 全体計画（フェーズ・マイルストーン）
├── CLAUDE.md               # プロジェクト規約（ライセンス方針ほか）
├── data/                   # 施策データ（単一ソース）
│   ├── merchants.json      # チェーン店マスタ（59 件、読み・エイリアス付き）
│   ├── campaigns.json      # 還元施策（三井住友・MUFG の 2 施策、計 62 ルール）
│   ├── profile.json        # ユーザー前提条件（保有カード・エントリー状況）
│   └── README.md           # データスキーマ仕様・更新ルール
├── docs/
│   ├── licenses.md         # 依存ライブラリのライセンス調査
│   ├── roadmap.md          # 進捗とロードマップ
│   └── code-guide.md       # このドキュメント
└── app/src/
    ├── main/java/com/ktakjm/poikatsu/
    │   ├── MainActivity.kt
    │   ├── ui/             # MainViewModel, SearchScreen, NearbyMap, theme/
    │   ├── domain/         # JudgmentEngine（純 Kotlin）
    │   ├── data/           # Models, DataRepository, GithubRawClient,
    │   │                   # OverpassClient, LocationProvider
    │   └── util/           # JapaneseText, GeoMath（純 Kotlin）
    └── test/java/com/ktakjm/poikatsu/
        ├── JudgmentEngineTest.kt   # 実データを使った検索・判定・店舗対象判定テスト（27 件）
        ├── DataRepositoryTest.kt   # ロード戦略のテスト（5 件）
        └── NearbyTest.kt           # Overpass パース・距離計算のテスト（10 件）
```

ポイント: `data/` はリポジトリ直下にあり、`app/build.gradle.kts` の `assets.srcDir(rootProject.file("data"))` で **そのまま assets として同梱** される。同じファイルが GitHub raw 配信のソースでもあるため、「同梱データ」「リモートデータ」「テストデータ」が常に単一ソースで一致する。

## 3. データモデル（data/Models.kt）

`kotlinx.serialization` の `@Serializable` データクラス。JSON のスネークケースは `@SerialName` でマッピングする。

```mermaid
erDiagram
    MERCHANT {
        string id PK
        string name
        string reading "ひらがな読み（検索用）"
        string_list aliases "略称・別表記"
        string category "コンビニ/ファミレス等"
    }
    CAMPAIGN {
        string id PK
        string issuer "発行体"
        string brand_color "#RRGGBB"
        double rate_base "基準還元率"
        double rate_max "理論最大"
        bool entry_required
        string verified_date "最終確認日"
    }
    MERCHANT_RULE {
        string merchant_id FK
        string note "店固有条件"
        string exclusion_note "対象外の但し書き(人間向け)"
        bool amex_excluded
        string store_list_url "公式店舗一覧/検索リンク"
    }
    OFFICIAL_STORE_LIST {
        string_list eligible_stores "公式の対象店舗"
        string_list ineligible_stores "公式の対象外店舗"
        string updated_date "鮮度(確認日 or 公式更新日)"
        bool date_is_official
        string source_url
    }
    PROFILE_CARD {
        string campaign_id FK
        string card_name
        string brand "Visa/Mastercard/Amex"
        bool entry_done
        double effective_rate_default
    }
    CAMPAIGN ||--o{ MERCHANT_RULE : "merchant_rules[]"
    MERCHANT_RULE }o--|| MERCHANT : "merchant_id で参照"
    MERCHANT_RULE ||--o| OFFICIAL_STORE_LIST : "official_store_list(任意)"
    PROFILE_CARD }o--|| CAMPAIGN : "campaign_id で参照"
```

3 つの JSON の役割分担:

- `merchants.json` — チェーンの正規化マスタ。検索ヒット率は `reading` / `aliases` の充実度で決まる
- `campaigns.json` — 汎用的な施策情報のみ。**ユーザー固有の前提を書かない**（規約）
- `profile.json` — ユーザー前提（保有ブランド・エントリー済みか等）。常にローカル（assets）から読み、リモート更新の対象外

パースは `PoikatsuJson.parse()` に集約。`ignoreUnknownKeys = true` + `coerceInputValues = true` により、スキーマに後からフィールドを追加しても旧アプリが壊れない（前方互換）。

## 4. データ取得戦略（data/DataRepository.kt）

「即時表示 + 裏で更新」の 2 段構え。リモート取得失敗時は静かにローカルを使い続ける。

```mermaid
flowchart TD
    START(["アプリ起動 / ViewModel init"]) --> LL["loadLocal()"]
    LL --> HASCACHE{"キャッシュファイル<br/>あり?"}
    HASCACHE -- "あり" --> PARSE1{"パース成功?"}
    PARSE1 -- "成功" --> CACHE["DataSource.CACHE<br/>で即時表示"]
    PARSE1 -- "失敗（破損）" --> BUNDLED["assets から読込<br/>DataSource.BUNDLED"]
    HASCACHE -- "なし（初回）" --> BUNDLED
    CACHE --> FG["ON_START（フォアグラウンド復帰）"]
    BUNDLED --> FG
    FG --> SKIP{"前回成功から<br/>1時間以内?"}
    SKIP -- "はい（手動更新以外）" --> NOP(["スキップ"])
    SKIP -- "いいえ" --> FETCH["refresh(): GitHub raw から<br/>merchants/campaigns を取得"]
    FETCH --> OK{"取得+パース<br/>成功?"}
    OK -- "成功" --> SAVE["キャッシュ保存 +<br/>DataSource.REMOTE で表示更新"]
    OK -- "失敗" --> KEEP["null を返す<br/>（ローカルデータ継続 + 失敗表示）"]

    style START fill:#1565C0,stroke:#0D47A1,color:#fff
    style SAVE fill:#2E7D32,stroke:#1B5E20,color:#fff
    style KEEP fill:#C2185B,stroke:#880E4F,color:#fff
    style BUNDLED fill:#E65100,stroke:#BF360C,color:#fff
    style CACHE fill:#E65100,stroke:#BF360C,color:#fff
```

学習ポイント:

- **テスタビリティのための依存注入**: `DataRepository(readAsset, cacheDir, fetchRemote)` は Android の `Context` を一切受け取らない。本番では `app.assets.open(...)` と `GithubRawClient::fetch` を渡し、テストではラムダとテンポラリディレクトリを渡す
- **「成功した場合のみキャッシュ」**: 取得した生テキストをまずパースし、成功したものだけ `writeText` する。壊れた JSON でキャッシュを汚染しない
- **データソースの可視化**: `DataSource`（REMOTE / CACHE / BUNDLED）を UI まで運び、「最新データ取得済み / 前回取得データ(オフライン?)」と表示してデータ鮮度をユーザーに伝える

### リモート更新の発火タイミング（ui/MainViewModel.kt）

リモート取得は `init` ではなく **Lifecycle の ON_START 起点**（`SearchScreen` の `LifecycleEventObserver` → `onAppForeground()`）。初回起動でも必ず一度走り、フォアグラウンド復帰のたびに試行されるが、直近 1 時間以内に成功していればスキップする（施策データの更新は月数回なので十分）。`initialLoad.join()` でローカルロード完了を待ってからリモート結果を適用し、表示順序の競合を防いでいる。

## 5. 判定エンジン（domain/JudgmentEngine.kt）

このアプリの心臓部。Android 非依存で、3 つの仕事をする。

### 5.1 検索（search）

コンストラクタで全チェーンの検索キー（正規化済みの name / reading / aliases）を `searchIndex` として前計算する。検索はスコアリング方式:

| スコア | 条件 | 例 |
|---|---|---|
| 0 | 前方一致 | 「ガスト」→ ガスト |
| 1 | 部分一致 | 「ガスト」→ ステーキガスト |
| 2 | 単語境界つき包含（キー 3 文字以上） | 「マクドナルド渋谷店」→ マクドナルド |

`containsAsWord` は「マックスバリュ」が「マック」に誤ヒットしないための仕組み。キーの前後の隣接文字が **同じ文字種（かな同士・英数同士）で連続している場合は別単語の一部** とみなして弾く。漢字→かなのような文字種の切り替わりは単語境界として許容する（「くら寿司ららぽーと店」は OK）。

### 5.2 日本語正規化（util/JapaneseText.kt）

「セブン-イレブン」「ｾﾌﾞﾝｲﾚﾌﾞﾝ」「せぶんいれぶん」を同一視するための正規化パイプライン:

1. NFKC 正規化（全角・半角の統一。半角カナ→全角カナもここで解決）
2. 小文字化
3. 記号除去（スペース・中点・各種ハイフン等。長音「ー」は読みの一部なので残す）
4. カタカナ→ひらがな（コードポイントを `-0x60` シフト）

### 5.3 チェーン判定（judge）と店舗単位の対象判定（checkStore）

`judge` はチェーン単位の判定（店舗名は受け取らない）。店舗単位の対象/対象外は別関数 `checkStore` が担う。

```mermaid
flowchart LR
    IN["Merchant"] --> LOOP["全 Campaign を走査"]
    LOOP --> RULE{"merchant_rules に<br/>この店の rule あり?"}
    RULE -- "なし" --> DROP(["対象外（リスト除外）"])
    RULE -- "あり" --> CARD["profile から保有カードを引く<br/>effectiveRate を決定"]
    CARD --> WARN["警告生成<br/>・未エントリー<br/>・Amex 対象外"]
    WARN --> SORT["effectiveRate 降順で<br/>Judgment リスト返却"]

    style IN fill:#1565C0,stroke:#0D47A1,color:#fff
    style SORT fill:#2E7D32,stroke:#1B5E20,color:#fff
    style DROP fill:#E0E0E0,stroke:#9E9E9E,color:#333
```

- `effectiveRate = card?.effectiveRateDefault ?: campaign.rateBase` — ユーザー前提（profile）があればそれを優先
- **店舗単位の判定 `checkStore(merchant, storeName)`**: `official_store_list` を持つ施策ごとに、`ineligible_stores` 一致 → 対象外 / `eligible_stores` 一致 → 対象 / どちらにも無し → 要確認（`StoreEligibility.UNKNOWN`）の **3 状態**を返す（対象外を優先）。リスト網羅性を仮定せず、**公式が店舗名で明示した店だけ言い切る**設計（旧 `facility_risk_patterns` によるキーワード推測警告は実際の対象外店舗との乖離が大きく廃止）。
- `canCheckStore(merchant)`: `official_store_list` を持つ施策が 1 つでもあれば、対象判定画面（`StoreCheckScreen`）に遷移できる。
- `isExcludedStore(merchant, storeName)`: 近隣リスト用。`checkStore` の結果が INELIGIBLE を含み ELIGIBLE を含まないときだけ true（**明示的対象外のみ**近隣リストから除外する）。
- チェーン rule の引き当ては `Campaign.ruleFor(merchant)`（private 拡張）に集約。
- `matchStore(storeName, brand)` は GPS 検索用。OSM の POI 名（「マクドナルド 渋谷駅前店」）からチェーンを特定する。「ステーキガスト」が「ガスト」に負けないよう、**一致キーが最長のチェーンを採用**する

## 6. UI レイヤ（ui/）

### 6.1 状態管理

`MainViewModel` が単一の `UiState`（data class）を `StateFlow` で公開し、`SearchScreen` が `collectAsState()` で購読する単方向データフロー（UDF）。状態更新はすべて `MutableStateFlow.update {}`（スレッドセーフな copy）で行う。

画面遷移は Navigation ライブラリを使わず、**UiState のフィールドで排他的に表現**するシンプルな状態機械:

```mermaid
stateDiagram-v2
    [*] --> Loading: 起動
    Loading --> Search: loadLocal 完了
    Loading --> Error: 読込失敗
    Search --> Detail: 店舗を選択（selection != null）
    Search --> Nearby: 位置情報ボタン（nearby != null）
    Nearby --> Detail: 周辺店舗をタップ
    Detail --> StoreCheck: 「対象か調べる」（storeCheck != null・公式リスト有のみ）
    StoreCheck --> Detail: 戻る（storeCheck = null）
    Detail --> Search: 戻る（selection = null）
    Nearby --> Search: 閉じる（nearby = null）
```

戻る操作は Compose の `BackHandler` で実装。`storeCheck` 分岐は `selection` より先に評価する（両方非 null のとき対象判定画面を優先表示）。データ差し替え時（リモート更新成功時）は、表示中の `selection` / `storeCheck` があれば新データで判定を引き直す（`applyData` 内）。Selection の組み立ては `JudgmentEngine.selectionFor(merchant, hint)`（private 拡張）に集約し、判定・遷移可否・プリフィルをまとめて引く。

### 6.2 判定カード表示

`JudgmentCard` は施策ごとに 1 枚。左端のストライプとカード名バッジに `brand_color` を使い、ロゴ画像なしで発行体を識別する。表示要素は「還元率（大）→ 条件達成時最大 → 支払い方法 → 店固有条件 → エントリー警告 → 公式店舗一覧リンク → 上限 → **情報確認日**」の順。`verified_date` の表示は必須ルール（データが古くなるリスクへの対処）。

公式リストを持つチェーン（`canCheckStore` が true）では判定詳細に「この店舗が対象か調べる →」ボタンを出し、別画面 `StoreCheckScreen` へ遷移する。同画面は店舗名入力に対し `StoreVerdictCard` で ✅対象 / ⛔対象外 / ❓要確認 を表示し、断定の鮮度（`date_is_official` に応じて「公式情報の更新日」or「確認日」）を併記する。公式リストの無いチェーンはボタンを出さない（判定画面を意識させない）。

### 6.3 位置情報パーミッション

パーミッション要求は UI 層（`rememberLauncherForActivityResult`）の責務で、ViewModel は「許可済み前提」の `fetchNearby()` と「拒否された」`onLocationDenied()` だけを持つ。FINE / COARSE のどちらか片方でも許可されれば検索する。

## 7. GPS 周辺検索のデータフロー

```mermaid
sequenceDiagram
    participant U as ユーザー
    participant SC as SearchScreen
    participant VM as MainViewModel
    participant LP as LocationProvider
    participant OC as OverpassClient
    participant JE as JudgmentEngine

    U->>SC: 位置情報アイコンをタップ
    SC->>SC: パーミッション確認/要求
    SC->>VM: fetchNearby()
    VM->>LP: currentLocation()
    Note over LP: NETWORK 優先・15 秒タイムアウト<br/>失敗時は getLastKnownLocation
    LP-->>VM: 緯度経度
    VM->>OC: fetchNearby(lat, lon, radiusM)
    Note over OC: Overpass QL で飲食店/コンビニ/<br/>スーパーの POI を取得<br/>（>1km は node のみ＝速度優先）<br/>本家失敗時はミラーへフォールバック
    OC-->>VM: List(Poi)
    loop 各 POI
        VM->>JE: matchStore(poi.name, poi.brand)
        JE-->>VM: 該当チェーン or null（捨てる）
        VM->>JE: isExcludedStore(merchant, displayName)
        Note over JE: 公式に明示的「対象外」なら<br/>リストから除外
        VM->>JE: judge(merchant) → 最高還元率
    end
    VM-->>SC: NearbyUi（距離昇順・対象チェーンのみ・明示的対象外は除外）
    U->>SC: 店舗をタップ
    SC->>VM: onSelectNearby(place)
    Note over VM: POI 名を storeNameHint に引き継ぐ<br/>（対象判定画面で店舗名にプリフィル）
    VM-->>SC: Selection（判定詳細へ）
```

### 7.1 地図表示と差し替え境界（ui/NearbyMap.kt）

近隣検索は **地図 + 距離順リストの上下分割**で表示する（`NearbyPane`）。地図は上部（**固定高 300dp**、はみ出しは `clipToBounds` で抑止し、チップ・リストに被らせない）に検索中心（初期は現在地）で対象店舗をピン表示し、下部に距離順リスト（還元率・距離。残り領域を `weight(1f)` でスクロール）を残す。

- ピンは `campaign.brand_color` で着色（ロゴ不使用方針と整合）。ピン/リスト行のタップはどちらも `onSelectNearby` で判定詳細へ遷移する。
- 明示的「対象外」店舗（`isExcludedStore`）は地図・リストの両方に出さない。
- **地図ライブラリの差し替え境界**: osmdroid 固有の型（`MapView`/`GeoPoint`/`Marker`）は `NearbyMap.kt` 1ファイルに閉じ込め、アプリ側（ViewModel/`NearbyPane`）は自前の `MapPoint`/`MapMarker` だけを扱う。これにより将来 Google Maps 等へ**表示層だけ**を差し替える場合も、変更は NearbyMap 本体・依存・API キー設定・docs に閉じる（ViewModel/テストは無変更）。Google Maps の地図表示は無料無制限だが Play Services 依存のため現在は採用していない（docs/licenses.md）。
- 座標は ViewModel が `NearbyPlace.lat/lon`・`NearbyUi.centerLat/centerLon`（検索の起点＝地図カメラ中心）・`NearbyUi.userLat/userLon`（実際の現在地＝青ドット）で UI まで運ぶ。地図の初期ズームは検索半径から決める（`zoomForRadius`）。OSM タイル利用規約のため `Configuration.userAgentValue` にパッケージ名を設定する。
- **「このエリアを検索」**: スクロール/ズームは osmdroid に任せ、Compose からは触らない（操作中の再描画でズレないため）。地図上のボタンを押すとその時点の地図中心を `searchHere(lat, lon)` に渡し、**その中心を起点に Overpass を引き直して**距離順リストを作り直す（現在地の青ドットは維持）。半径変更も直近の検索中心を保ったまま再検索する。ピンはパン/ズームでは再描画せず、検索結果（`markers` の参照）が変わったときだけ描き直す。

Overpass 側の設計判断（`OverpassClient` のコメント参照）:

- 日本語名の正規表現フィルタはサーバ側で遅すぎるため、**カテゴリタグで広く取ってチェーン照合はクライアント側**で行う
- 半径 1km 超は `node` のみ取得（`way`＝建物ポリゴン店舗は落ちるが速度優先）
- 日本の OSM は支店名を `branch` タグに分ける慣習があるため、表示名は `name + branch` を結合
- 公式に「対象外」と明示された店舗（`isExcludedStore`）は近隣リストに出さない。判定には支店名結合後の `displayName` を使うため、OSM 名に支店名が無いと判定できず表示される（データ粒度依存）
- **エンドポイントのフォールバック**: overpass-api.de は混雑時に 429/504/タイムアウトを返しやすいため、本家が失敗したらミラー（`maps.mail.ru`）へ順に試す。失敗理由（HTTP コード/例外）は `OverpassClient` タグで logcat に記録し、全エンドポイント失敗時のみ null を返す

## 8. テスト戦略

`./gradlew :app:testDebugUnitTest` で全 43 テストが JVM 上で実行される（エミュレータ不要）。

| テスト | 対象 | 特徴 |
|---|---|---|
| `JudgmentEngineTest`（27 件） | 検索・正規化・判定・店舗対象判定（3 状態）・近隣除外 | **リポジトリ直下 `data/` の実データを読み込む**。「マック→マクドナルド」「マックスバリュは誤ヒットしない」等の振る舞いと、merchant_id 参照切れ等のデータ整合性チェックを兼ねる。アカチャンホンポの公式リストで対象/対象外/要確認の 3 状態も検証 |
| `DataRepositoryTest`（5 件） | ロード戦略 | ラムダ注入により、キャッシュあり/なし/破損、リモート成功/失敗の各経路を File システムだけで検証 |
| `NearbyTest`（10 件） | Overpass パース・距離計算・チェーン特定 | 固定 JSON フィクスチャでネットワーク非依存 |

「実データをテストに使う」のがこのプロジェクトの肝で、**データ更新（JSON 編集）だけの変更でも CI 的にテストを流せば参照切れやエイリアス衝突を検出できる**。

## 9. 技術スタック早見表

| 項目 | 採用 | 備考 |
|---|---|---|
| 言語 / UI | Kotlin + Jetpack Compose (Material 3) | minSdk 29 / targetSdk 36 |
| アーキテクチャ | MVVM + Repository、手動 DI | 単一 ViewModel・単一 UiState |
| シリアライズ | kotlinx.serialization | ignoreUnknownKeys で前方互換 |
| HTTP | OkHttp（素のまま） | Retrofit/Ktor なし。GET 2 種 + POST 1 種のみなので十分 |
| ローカル保存 | ファイルキャッシュ（filesDir/remote_data/） | Room は見送り |
| 位置情報 | LocationManager（フレームワーク標準） | Play Services 不使用 |
| 周辺店舗 | Overpass API（OSM） | 無料・API キー不要。本家失敗時はミラーにフォールバック |
| 地図表示 | osmdroid（OSM ラスタタイル） | Play Services 非依存・Apache-2.0。`NearbyMap.kt` に閉じ込め将来差し替え可能 |
| データ配信 | GitHub raw（main ブランチ data/） | 更新はアプリ再ビルド不要 |

依存追加時は **ライセンス確認 → docs/licenses.md へ追記** が必須ルール（GPL/AGPL 不可。詳細は [CLAUDE.md](../CLAUDE.md)）。

## 10. この構成から学べること

- **単方向データフロー（UDF）**: StateFlow + 不変 UiState + update のパターン
- **依存注入をフレームワークなしでやる**: 関数型インターフェース（ラムダ）注入によるテスタブル設計
- **オフラインファースト**: 即時ローカル表示 + バックグラウンド更新 + 鮮度の可視化
- **ドメインロジックの分離**: Android 非依存に保つことで実データテストが高速に回る
- **日本語検索の実務**: NFKC 正規化・かなカナ同一視・単語境界判定・前方一致優先
- **外部 API との付き合い方**: Overpass の性能特性に合わせたクエリ設計、失敗時の null フォールバック
