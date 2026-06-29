# 期間限定キャンペーン・自治体施策 設計書

最終更新: 2026-06-28 (rev.2 — レビューフィードバック反映)
ステータス: **Phase A〜E 実装完了**（2026-06-30）。Phase F（データ投入・実機検証）は未着手

## 1. 概要と目的

poikatsu の判定対象を、現在の常設カードプログラム（三井住友/MUFG）から**期間限定キャンペーン**へ拡張する。対象は大きく 2 種類:

| 種類 | 例 | 店舗データ | 地図表示 |
|---|---|---|---|
| **カード/QR 期間限定** | 三井住友×セブン 特別15%（7月限定） | 対象チェーンが明確 → merchant_rules で管理可 | ○ 可能 |
| **自治体施策** | 渋谷区×PayPay 20%還元 | 数百〜数千の個別加盟店 → アプリ内管理不可 | × 不可（公式に誘導） |

加えて、QR 決済では「還元（ポイントバック）」ではなく**「クーポン割引」**（定額値引き・定率値引き）の形態もある。データスキーマはこれも包含する。

### 設計の前提

- 自治体施策の対象店舗は PayPay/auPAY 等の公式アプリが地図+応援マークで提供しており、poikatsu が複製する意味はない。「キャンペーンの存在を知らせ、公式に誘導する」が限界であり、それで十分
- カード会社の期間限定キャンペーンが特定チェーンを対象とする場合は、既存の merchant_rules モデルがそのまま使える
- 現在の YOLP 検索パラメータ（gc コード・キーワード）がコード内ハードコードなのは、merchants が固定だった前提に依存しており、キャンペーンで新しいチェーンが増減する世界では**データ駆動に分離する必要がある**
- **「探す」タブに自治体施策（`store_scope == "external"`）は表示しない**（初回実装）。自治体施策の対象は中小企業が中心で、「探す」タブに出てくるチェーン店が対象外な場合が多いため。自治体施策の閲覧はキャンペーンタブに限定する
- キャンペーンの粒度基準: 還元率 **5% 以上**、主要チェーン/自治体対象のものを収録対象とする

---

## 2. データスキーマ変更

### 2.1 campaigns.json — 拡張フィールド

既存フィールド `period_start` / `period_end` は定義済み（現在 null）。以下を追加する。

```jsonc
{
  "id": "shibuya_paypay_2026_07",

  // ---- 新規フィールド ----

  // キャンペーン種別。判定エンジン・UI の分岐に使う
  //   "card_program"   : 常設カードプログラム（既存の SMCC/MUFG）
  //   "card_promotion" : カード/QR 会社の期間限定（特定チェーン対象）
  //   "municipal"      : 自治体施策（店舗データなし）
  "type": "municipal",

  // 特典の形態
  //   "rebate"          : ポイント還元（後日ポイント付与）。
  //                       rate_base(%) または discount_amount(円) のいずれかを設定（排他）。
  //                       ※ PayPay の「クーポン」も実態は後日ポイント付与のため rebate に分類する
  //   "coupon_percent"  : 即時割引（定率）。rate_base が割引率(%)
  //   "coupon_fixed"    : 即時割引（定額）。discount_amount が割引額(円)
  // 省略時は "rebate"（後方互換）
  //
  // rebate と coupon の違い:
  //   rebate = 後日ポイント付与（PayPay は原則30日後、auPAY 等も同様）
  //   coupon = その場で値引き（決済金額自体が減る）
  // PayPay の「クーポン」は名称こそクーポンだが実態は rebate（ポイント付与）であることに注意。
  "benefit_type": "rebate",

  // 決済手段の識別子（profile.json の qr_payments.id と対応）
  // card_program/card_promotion は既存の campaign_id 経由で紐付くため不要
  "payment_method_id": "paypay",

  // 期間（既存フィールド。null = 常設）
  "period_start": "2026-07-01",
  "period_end": "2026-07-31",

  // ---- rate_base と discount_amount は排他（どちらか一方が non-null） ----
  // rebate:         rate_base(%) → 定率ポイント還元 / discount_amount(円) → 定額ポイント還元
  // coupon_percent: rate_base(%) → 定率即時割引
  // coupon_fixed:   discount_amount(円) → 定額即時割引
  "rate_base": 20.0,              // 定率の場合の率(%)。定額の場合は null
  "discount_amount": null,        // 定額の場合の金額(円)。定率の場合は null

  // ---- 上限・条件 ----
  "per_transaction_cap": 2000,    // 1回あたりの付与/割引上限（円相当）。null = 上限なし
  "period_total_cap": 10000,      // 期間合計の付与/割引上限（円相当）。null = 上限なし
  "cap_note": "1回あたり付与上限2,000円相当、期間合計10,000円相当",
  "min_purchase": null,           // 適用条件の最低購入額（円）。例: 1000 → 「1,000円以上の決済で」
  "usage_limit": null,            // 利用回数上限。null = 期間中無制限、1 = 1回限り
  "usage_limit_note": null,       // 利用条件の補足（人間向け）

  // ---- 自治体施策用 ----
  "region": {
    "name": "渋谷区",             // 表示名
    "prefecture": "東京都",       // 都道府県（フィルタ用）
    "area_group": null            // 将来用: グループ名（"23区", "都下" 等）。null = 未分類
  },
  "campaign_url": "https://...",  // キャンペーン公式ページ
  "store_search_url": "https://...",  // 対象店舗検索ページ（PayPay等の公式）

  // 店舗データの有無
  //   "managed"  : merchant_rules で管理（card_program / card_promotion）
  //   "external" : 外部参照のみ（municipal）
  "store_scope": "external",

  // merchant_rules は既存と同じ構造。municipal は空配列
  "merchant_rules": [],

  "sources": ["https://..."],
  "verified_date": "2026-06-28"
}
```

**カード会社の期間限定キャンペーン例:**

```jsonc
{
  "id": "smcc_uniqlo_2026_summer",
  "type": "card_promotion",
  "benefit_type": "rebate",
  "issuer": "三井住友カード",
  "brand_color": "#00A94F",
  "name": "ユニクロで最大15%還元キャンペーン",
  "payment_instruction": "スマホのVisaタッチ決済で支払う",
  "rate_base": 15.0,
  "period_start": "2026-07-01",
  "period_end": "2026-08-31",
  "per_transaction_cap": 1500,
  "period_total_cap": null,
  "cap_note": "1回あたり付与上限1,500円相当(税込10,000円で上限到達)",
  "store_scope": "managed",
  "merchant_rules": [
    { "merchant_id": "uniqlo", "note": null, "exclusion_note": null }
  ],
  // ...
}
```

**定額ポイント還元の例（PayPay「クーポン」= 実態は rebate）:**

PayPay の「クーポン」は名称こそクーポンだが、実態は後日ポイント付与（原則30日後）なので `benefit_type: "rebate"` + `discount_amount` で表現する。

```jsonc
{
  "id": "paypay_seven_coupon_2026_07",
  "type": "card_promotion",
  "benefit_type": "rebate",          // PayPay「クーポン」だが実態は rebate
  "issuer": "PayPay",
  "payment_method_id": "paypay",
  "brand_color": "#FF0033",
  "name": "セブン-イレブンで最大10%戻ってくる",
  "rate_base": 10.0,
  "per_transaction_cap": 100,        // 付与上限100ポイント/回
  "min_purchase": null,
  "usage_limit": 1,
  "usage_limit_note": "お一人様1回限り。事前にクーポンを獲得してください",
  "period_start": "2026-07-01",
  "period_end": "2026-07-15",
  "store_scope": "managed",
  "merchant_rules": [
    { "merchant_id": "seven_eleven" }
  ],
  // ...
}
```

**即時割引の例（定額・au PAY）:**

au PAY の「割引クーポン」は決済金額自体が減る即時割引なので `coupon_fixed`。

```jsonc
{
  "id": "aupay_coupon_life_2026_07",
  "type": "card_promotion",
  "benefit_type": "coupon_fixed",    // 即時割引
  "issuer": "au PAY",
  "payment_method_id": "aupay",
  "brand_color": "#FF5722",
  "name": "ライフで最大3%割引",
  "rate_base": null,
  "discount_amount": null,           // ※ 実は定率即時割引なので coupon_percent の方が正確（下記参照）
  // ...
}
```

※ au PAY の「3%割引（上限100円/回）」は実態としては `coupon_percent`（定率即時割引）+ `per_transaction_cap: 100`。`coupon_fixed` は「100円引き」のような固定額値引き用。

**即時割引の例（定額値引き）:**

```jsonc
{
  "id": "dpay_coupon_seven_2026_07",
  "type": "card_promotion",
  "benefit_type": "coupon_fixed",
  "issuer": "d払い",
  "payment_method_id": "dpay",
  "brand_color": "#E60033",
  "name": "セブン-イレブンで100円引きクーポン",
  "rate_base": null,
  "discount_amount": 100,
  "min_purchase": 200,
  "usage_limit": 1,
  "usage_limit_note": "お一人様1回限り",
  "period_start": "2026-07-01",
  "period_end": "2026-07-15",
  "store_scope": "managed",
  "merchant_rules": [
    { "merchant_id": "seven_eleven" }
  ],
  // ...
}
```

**クーポン割引の例（定率）:**

```jsonc
{
  "id": "dpay_coupon_matsuya_2026_07",
  "type": "card_promotion",
  "benefit_type": "coupon_percent",
  "issuer": "d払い",
  "payment_method_id": "dpay",
  "brand_color": "#E60033",
  "name": "松屋で10%OFFクーポン",
  "rate_base": 10.0,
  "per_transaction_cap": 500,
  "min_purchase": null,
  "usage_limit": null,
  "period_start": "2026-07-01",
  "period_end": "2026-07-31",
  "store_scope": "managed",
  "merchant_rules": [
    { "merchant_id": "matsuya" }
  ],
  // ...
}
```

**同一自治体・複数決済手段の場合:**

一つの自治体が複数の QR 決済と同時にキャンペーンを展開する場合（渋谷区×PayPay と 渋谷区×auPAY が同時期に開催）は、campaigns.json ではそれぞれ別レコードとして持つが、**キャンペーンタブの UI では同一カードにマージして表示する**（§4.2 参照）。マージの基準は `region.name` + 期間の重なり。

### 2.2 merchants.json — YOLP 検索設定の分離

各 merchant に **YOLP でどう検索するか**の情報を持たせる。

```jsonc
{
  "id": "uniqlo",
  "name": "ユニクロ",
  "reading": "ゆにくろ",
  "aliases": ["UNIQLO"],
  "category": "ファッション",

  // ---- 新規フィールド ----

  // YOLP 検索方式。省略時は "gc"（カテゴリの gc グループで検索）
  //   "gc"      : カテゴリに紐づく gc コードで一括取得される（大半のチェーン）
  //   "keyword"  : 店名キーワードで個別取得（gc で取れないチェーン）
  //   "none"     : YOLP で検索しない（location_hint があるもの、自販機等）
  "yolp_search": "keyword",

  // yolp_search == "keyword" のとき、検索に使うキーワード。省略時は name を使う
  "yolp_keyword": "ユニクロ"
}
```

あわせて、**カテゴリ → gc コード** のマッピングも merchants.json のメタデータとして外出しする。

#### gc グループの分割戦略

**現状の問題と設計意図の保存:**

現在の `GENRE_CODES` は `listOf("0123,0115,0101013", "0205")` と **2 つのソース** に分割されている。これは YOLP の 500 件/ソース上限に対する密度制御:
- グルメ系（0123+0115+0101013）とコンビニ/スーパー（0205）を同じソースにすると、グルメの密度がコンビニ/スーパーを圧倒し、`mergeAndClip` のカバー半径が縮む
- 密度の近いカテゴリは同一ソースに、密度が大きく異なるカテゴリは別ソースにすることで、全ソースが同程度の範囲をカバーする

**データ構造でこの分割を再現する:**

```jsonc
{
  "schema_version": 2,
  "updated_at": "2026-07-01",

  "yolp_config": {
    // 各 gc_group = 1 つの YOLP ソース（1 コール系列）
    // 密度が近いカテゴリは同一グループに、密度差が大きいものは別グループに
    "gc_groups": [
      {
        "gc": "0123,0115,0101013",
        "categories": ["ファストフード", "ファミレス", "カフェ", "回転寿司"],
        "max_pages": 5,
        "note": "グルメのうち対象チェーンが集中する業種。密度: 中〜高"
      },
      {
        "gc": "0205",
        "categories": ["コンビニ", "スーパー"],
        "max_pages": 5,
        "note": "スーパー+コンビニ。密度: 中"
      }
    ],
    // キーワードソースの 1 検索あたり上限
    "max_keyword_sources": 20
  },

  "merchants": [...]
}
```

**`gc_groups` の設計ルール:**
- 各 group は **1 つの YOLP ソース**（`fetchPaged` の 1 呼び出し系列 = 最大 `max_pages × 100` 件）に対応する。group 内のカテゴリはカンマ OR で 1 リクエストにまとめられる
- group を分ける基準は**密度差**。新宿駅 3km で 1,000 件を超える密度のカテゴリは、疎なカテゴリと同じ group にしない（`mergeAndClip` でカバー半径が潰れるため）
- `max_pages` は group ごとに設定可能。密度が低い group は少なくしてリクエスト数を節約できる
- **新カテゴリの追加手順**: (1) そのカテゴリの gc コードを YOLP で調べる、(2) 新宿駅周辺等で密度を確認、(3) 密度が近い既存 group に追加するか、密度が大きく異なれば新 group を作成

**移行の影響:**
- `YolpClient` の `GENRE_CODES` / `KEYWORD_QUERIES` を廃止し、`yolp_config` + 各 merchant の `yolp_search` から動的に構築
- 既存 merchant に `yolp_search` フィールドを追加（現在 keyword 検索しているカーブス等 6 チェーンは `"keyword"` 明示、それ以外は省略＝`"gc"` デフォルト、Coke ON は `"none"`）
- 新カテゴリ（例: "ファッション"）を追加する際は `gc_groups` にも対応する gc コードを追加する
- **gc_groups の分割は密度チューニングの結果であり、データの追加だけでは最適化できない**。新 group を追加する際は実 API で密度を確認してから決める

### 2.3 profile.json — QR 決済・自治体の登録

```jsonc
{
  "schema_version": 2,
  "updated_at": "2026-07-01",
  "profile": {
    "cards": [...],  // 既存

    // ---- 新規 ----

    // 利用中の QR 決済サービス（カタログ兼デフォルト）
    "qr_payments": [
      {
        "id": "paypay",
        "name": "PayPay",
        "brand_color": "#FF0033",
        "app_package": "jp.ne.paypay.android.app",
        "store_search_label": "PayPayアプリの「近くのおトク」で対象店舗を確認",
        "enabled_default": false
      },
      {
        "id": "aupay",
        "name": "au PAY",
        "brand_color": "#FF5722",
        "app_package": "com.kddi.android.aupay",
        "store_search_label": "au PAYアプリで対象店舗を確認",
        "enabled_default": false
      },
      {
        "id": "dpay",
        "name": "d払い",
        "brand_color": "#E60033",
        "app_package": "com.nttdocomo.android.payment",
        "store_search_label": "d払いアプリで対象店舗を確認",
        "enabled_default": false
      },
      {
        "id": "rakuten_pay",
        "name": "楽天ペイ",
        "brand_color": "#BF0000",
        "app_package": "jp.co.rakuten.pay",
        "store_search_label": "楽天ペイアプリで対象店舗を確認",
        "enabled_default": false
      }
    ],

    // 登録自治体（ユーザーが設定画面で追加。デフォルトは空）
    // ここはカタログではなくユーザー入力のため DataStore に保存
    "municipalities": []
  }
}
```

**設定画面での扱い:**
- QR 決済: profile.json にカタログとして定義し、設定画面で利用中のものにチェック → DataStore に差分保存（cards と同じ overlay 方式）
- 自治体: 設定画面で都道府県→市区町村を選んで登録。DataStore に保存。campaigns.json の `region.name` と照合してフィルタ

### 2.4 新規: payment_methods.json は作らない

QR 決済のメタデータ（名称・色・パッケージ名）は profile.json の `qr_payments` に含めることで、ファイルの増加を避ける。決済手段が 10 種類を超えるようなら分離を検討。

### 2.5 自治体マスタ

**決定: 案 C ベース**（都道府県は選択式、市区町村はテキスト入力のハイブリッド）に全国データを持つ。

- 全国約 1,700 市区町村の JSON マスタ（~100KB）を `data/municipalities.json` として同梱
- **データソース**: 総務省の全国地方公共団体コード等のオープンデータから生成する。アプリ固有のロジック（東京23区/市部のグループ分け等）は UI コード側で扱い、マスタ自体にはアプリ固有の構造を持ち込まない
- **作成タイミング**: Phase E（設定画面 UI 実装時）。Phase A ではスキーマ定義のみ
- UI は都道府県をドロップダウンで選択 → 該当する市区町村一覧から選択
- 東京都のみ特殊: 「23区」と「市部」をグループとして表示（区の一覧 / 市の一覧）
- **将来の拡張余地**: `region.area_group` フィールドで「23区」「都下」等のグループ分けを campaigns.json に持たせておく（初回は null）。グループフィルタの UI は初回実装対象外だが、データとしては用意する

---

## 3. ドメインレイヤの変更

### 3.1 JudgmentEngine — 期間フィルタと複数決済手段の比較

```
現在: judge(merchant) → List<Judgment>  （カード施策のみ）
拡張: judge(merchant, today) → JudgmentResult
```

**JudgmentResult の構造:**

```kotlin
data class JudgmentResult(
    // カード決済の判定（既存と同じ構造。常設 + 期間限定の card_promotion）
    val cardJudgments: List<Judgment>,
    // QR 決済の判定（card_promotion の QR 施策。ユーザーの利用 QR でフィルタ済み）
    // ※ municipal（store_scope == "external"）は「探す」タブには含めない
    val qrJudgments: List<QrJudgment>,
    // 最も還元率/割引が高い決済手段の要約（store_scope == "managed" のみで比較）
    val bestOption: BestPaymentOption?,
)

data class QrJudgment(
    val campaign: Campaign,
    val paymentMethod: QrPayment,
    val benefitType: BenefitType,   // REBATE / COUPON_PERCENT / COUPON_FIXED
    val effectiveRate: Double?,     // 定率（rebate% / coupon_percent%）のときの率
    val discountAmount: Int?,       // 定額（rebate固定額 / coupon_fixed）のときの金額(円)
    val minPurchase: Int?,          // 適用条件の最低購入額
    val usageLimit: Int?,           // 利用回数上限
    val daysRemaining: Int?,        // 残り日数（期限切れ間近の表示用）
    val perTransactionCap: Int?,
    val periodTotalCap: Int?,
)

data class BestPaymentOption(
    val method: String,             // "三井住友カード Visaタッチ" or "PayPay" etc.
    val rate: Double?,              // rebate/percent の場合
    val discountAmount: Int?,       // coupon_fixed の場合
    val benefitType: BenefitType,
    val isTimeLimited: Boolean,
    val daysRemaining: Int?,
)

enum class BenefitType { REBATE, COUPON_PERCENT, COUPON_FIXED }
```

**期間フィルタのルール:**
1. `period_end` が過去 → 非表示（期限切れ）。**UI には一切表示しない**
2. `period_start` が未来 → 「もうすぐ開始」としてキャンペーンタブにのみ表示（判定・検索には入れない）
3. `period_start` ≤ today ≤ `period_end` → アクティブ
4. `period_start` / `period_end` が null → 常設（既存動作）

**「探す」タブでの表示ルール（初回実装）:**
- `store_scope == "managed"` の施策のみ表示（card_program + card_promotion）
- `store_scope == "external"` の施策は「探す」タブに出さない
- → チェーン名で検索して出てくるのは、そのチェーンが対象であることが確実な施策のみ

**domain 層の純 Kotlin 維持:**
- `today` は外から `LocalDate` として渡す（`LocalDate.now()` をドメイン内で呼ばない）
- QR 決済の情報も `PoikatsuData` 経由で受け取る

### 3.2 YOLP 検索のデータ駆動化

**YolpClient の変更:**

```kotlin
// Before (hardcoded)
private val GENRE_CODES = listOf("0123,0115,0101013", "0205")
private val KEYWORD_QUERIES = listOf("カーブス", "アカチャンホンポ", ...)

// After (data-driven)
data class GcGroup(
    val gc: String,                 // "0123,0115,0101013" (カンマ OR)
    val maxPages: Int = 5,          // このソースの最大ページ数
)
data class YolpSearchConfig(
    val gcGroups: List<GcGroup>,              // merchants.json の yolp_config.gc_groups
    val keywordQueries: List<String>,         // yolp_search == "keyword" の merchant のキーワード
    val maxKeywordSources: Int = 20,          // キーワードソースの上限
)

// YolpClient.fetchNearby に config を渡す
fun fetchNearby(config: YolpSearchConfig, lat, lon, radiusM): List<Poi>?
```

**検索対象の動的決定:**
- 常設 + **現在アクティブな期間限定キャンペーン（`store_scope == "managed"`）の merchant_rules に含まれる merchant** のみを YOLP 検索対象にする
- 期限切れキャンペーンの merchant が他のアクティブキャンペーンに含まれなければ、YOLP 検索から除外（不要なリクエストの削減）

```
アクティブな campaign (store_scope == "managed") → merchant_rules → merchant_id 一覧
  ↓
merchants.json から該当 merchant を収集
  ↓
yolp_search == "gc" → merchant の category → gc_groups でどの group に属するか決定
  → 該当 merchant がいない group はスキップ（リクエスト節約）
yolp_search == "keyword" → 個別キーワードソース
  → maxKeywordSources で上限ガード
yolp_search == "none" → スキップ
```

**gc グループの動的スキップ:**

アクティブなキャンペーンがカバーするカテゴリだけを検索することで、無関係な gc グループのリクエストを節約する。ただし、常設施策（三井住友/MUFG）がカバーするカテゴリは常にアクティブ。

**mergeAndClip との整合:**

`mergeAndClip` のロジックは無変更。各 gc_group が 1 ソース、各 keyword が 1 ソースとして `SourceResult` を返し、密度差の偏り是正はそのまま機能する。`max_pages` を gc_group ごとに持つことで、密度の低い group はページ数を減らしてリクエスト数を節約できる（将来的なチューニング余地）。

**リクエスト数への影響:**
- 現在: 2 gc ソース + 6 keyword ソース = 最大 8 × 5 ページ = 40 リクエスト/検索
- keyword merchant が増えると比例してリクエスト数が増える。`max_keyword_sources` で上限を設ける
- gc グループはカンマ OR で 1 リクエストにまとめられるため、同一 group 内のカテゴリ追加ではリクエスト数は増えない。**新 group を追加するとソース数が 1 増える**
- 新 group 追加は密度チューニングを伴うため、データ変更だけでは行えず必ず実 API 確認が必要

---

## 4. UI 変更

### 4.1 ナビゲーション構造 — 3 タブ + 設定を 4 番目に

```
現在: [対象チェーン店] [近く]        設定 = 歯車オーバーレイ
変更: [探す] [近く] [キャンペーン] [設定]
```

**変更理由:**
- 「キャンペーン」タブは自治体施策の一覧・ブラウズに必要（検索/近くのどちらにも属さない）
- 3 タブになると設定の歯車アイコンを各画面の TopAppBar に置く必要があり、地図モード（full-bleed）との整合が煩雑になる
- 4 タブは M3 NavigationBar の標準的な使い方（Google Maps は 5 タブ）
- 設定をタブ化することで、常時アクセスの導線が明確になる

**3 層モデルとの整合:**
- roadmap.md の 3 層モデル（モード/レンズ/ブリッジ）で「モードは 2 つ、増やさない」としていたが、これは期間限定キャンペーン対応前の前提
- 「キャンペーン」タブは「問いの種類」としては「**今お得なキャンペーンは何か？**」で、既存 2 モード（名前起点/位置起点）とは直交する新しい問い
- 「設定」はモードではなくユーティリティなので 3 層モデルの枠外

**各タブの内容:**

| タブ | アイコン | 内容 |
|---|---|---|
| 探す | `Search` | 既存のチェーン名検索。**managed 施策のみ表示**（自治体施策は出さない） |
| 近く | `NearMe` / `LocationOn` | 既存の地図（変更なし。期間限定チェーン施策は自動的に地図に出る） |
| キャンペーン | `Campaign` / `LocalOffer` | アクティブな期間限定キャンペーン一覧（自治体施策のメイン閲覧先） |
| 設定 | `Settings` | 現在の設定オーバーレイの中身をタブ化 |

### 4.2 キャンペーンタブの UI

**レイアウト:**

```
┌─────────────────────────────┐
│  TopAppBar「キャンペーン」    │
├─────────────────────────────┤
│ [フィルタチップ: 全て | 自治体 | カード | QR]  │
├─────────────────────────────┤
│                             │
│  ── 開催中 ──               │
│  ┌─ Card ─────────────────┐ │
│  │ 渋谷区 キャッシュレス還元 │ │
│  │   7/1〜7/31 残り3日      │ │
│  │   ■ PayPay 20% (上限2千) │ │
│  │   ■ auPAY  20% (上限1万) │ │
│  │   [対象店舗を確認 →]      │ │
│  └─────────────────────────┘ │
│  ┌─ Card ─────────────────┐ │
│  │ 三井住友×ユニクロ 15%還元 │ │
│  │   7/1〜8/31 残り62日     │ │
│  │   1回1,500円             │ │
│  │   [詳細 →]               │ │
│  └─────────────────────────┘ │
│  ┌─ Card ─────────────────┐ │
│  │ d払い×松屋 10%OFFクーポン │ │
│  │   7/1〜7/31 残り3日      │ │
│  │   お一人様1回限り         │ │
│  │   [詳細 →]               │ │
│  └─────────────────────────┘ │
│                             │
│  ── もうすぐ開始 ──          │
│  ┌─ Card ─────────────────┐ │
│  │ ⏳ 港区×auPAY 20%       │ │
│  │    8/1〜8/31 あと4日で開始│ │
│  └─────────────────────────┘ │
│                             │
└─────────────────────────────┘
```

**同一自治体の複数決済手段をマージ:**

campaigns.json では `shibuya_paypay_2026_07` と `shibuya_aupay_2026_07` は別レコードだが、キャンペーンタブでは **同一自治体 × 期間重複** のキャンペーンを 1 枚のカードにまとめる。

マージ条件: `region.name` が一致 AND 期間の重なりがある

カード内の表示:
- タイトル: 自治体名 + 施策名（最初のレコードから取得、または共通部分）
- 期間: 最も早い `period_start` 〜 最も遅い `period_end`
- 各決済手段: ブランドカラー付きで還元率/上限を行ごとに表示
- アクション: 「対象店舗を確認」（各 QR アプリへの誘導を展開可能に）

**カードの要素:**
- ブランドカラーのストライプ（左辺）— 複数決済手段マージ時はグラデーション or 自治体名の色
- キャンペーン名（issuer × 対象 / rate）
- 期間 + 残り日数（urgency 表示: 残り 3 日以下は warning 色）
- 還元上限 / クーポン条件
- アクションボタン:
  - `store_scope == "external"` → 「対象店舗を確認」（外部アプリ/URL へ誘導）
  - `store_scope == "managed"` → 「詳細」（判定詳細へ遷移）

**UI には期限切れキャンペーンを一切表示しない。**

**フィルタ:**
- 「自治体」: ユーザーの登録自治体に該当するもの + 全都道府県ブラウズ
- 「カード」: card_promotion のみ
- 「QR」: ユーザーが利用中の QR 決済に関連するもの
- 未登録の自治体施策も「他の地域のキャンペーン」としてブラウズ可能にする（発見の導線）

### 4.3 検索タブ（探す）への影響

**初回実装では `store_scope == "external"` の施策（自治体施策）を検索タブに表示しない。**

理由:
- 自治体施策の対象は中小個人店が中心。「探す」タブに出てくるチェーン店が対象外な場合が多い
- 「対象かもしれない」という曖昧な表示はユーザーを混乱させる
- 自治体施策の閲覧はキャンペーンタブに一本化する

**card_promotion（特定チェーン対象）のみ、判定結果に追加表示する:**

```
┌─────────────────────────────┐
│ セブン-イレブン               │
├─────────────────────────────┤
│ ── カード決済 ──             │
│ ■ 三井住友カード  7%  常設   │ ← 既存
│ ■ 三菱UFJカード   7%  常設   │ ← 既存
│                              │
│ ── QR 決済 ──                │
│ ■ PayPay  100円引き  7/15迄  │ ← クーポン
│   200円以上の決済で/1回限り   │
│                              │
│ 💡 最もお得: PayPay 100円引き │
└─────────────────────────────┘
```

方針: **極力一画面に全ての対象決済手段を並べる。** カード決済とQR決済のセクション分けはするが、スクロールせずに全選択肢が見えるのが理想。

### 4.4 近くタブへの影響

**地図表示は card_promotion（特定チェーン対象）のみ。自治体施策は地図に出さない。**

変更点:
- 期間限定 card_promotion で対象になったチェーンが地図に自動表示される（YOLP 検索のデータ駆動化により）
- ピンの色分けは issuer の brand_color（既存ロジックで対応可能）
- プレビューに期間限定バッジを追加

### 4.5 判定詳細画面の拡張

既存の `JudgmentDetail` に以下を追加:

- **期間表示**: 「2026年7月1日〜8月31日」+ 残り日数
- **還元上限**: per_transaction_cap / period_total_cap を表示
- **クーポン条件**: min_purchase / usage_limit を表示
- **支払い方法の比較**: 「カード決済の場合」「QR 決済の場合」にセクション分けし、全選択肢を一画面に並べる。レアケース（カードとQRが重複適用できない旨）も明示

---

## 5. 設定画面の拡張

現在の設定セクション（表示/マイカード/データ/このアプリ）に以下を追加:

### 5.1 QR 決済

```
── QR 決済 ──
☑ PayPay
☐ au PAY
☐ d払い
☐ 楽天ペイ
```

利用中の QR 決済にチェック → 判定エンジンがフィルタに使う。profile.json の `qr_payments` がカタログ、DataStore に差分保存。

### 5.2 登録自治体

```
── 自治体 ──
居住地・行動圏の自治体を登録すると、該当するキャンペーンが
キャンペーン一覧に表示されます。

  渋谷区（東京都）  [×]
  港区（東京都）    [×]

  [+ 自治体を追加]
```

自治体の追加は都道府県選択 → 市区町村選択の 2 段ピッカー。データは `data/municipalities.json` から。東京都の場合は「23区」「市部」のグループヘッダ付きで表示。DataStore に保存。

**将来のグループ登録（初回対象外）:**
- 「東京23区をまとめて登録」のような操作を将来的にサポートしたい
- campaigns.json の `region.area_group` にグループ名を入れておけば、グループ一括登録に対応できる
- ただしグループの定義（「都下」「島嶼部」等）は自治体ごとに標準が異なるため要検討

---

## 6. クーポン割引への対応

### 6.1 スキーマ上の扱い

`benefit_type` × (`rate_base` or `discount_amount`) の組み合わせで特典を表現する。`rate_base` と `discount_amount` は排他（どちらか一方が non-null）。

| `benefit_type` | `rate_base` | `discount_amount` | タイミング | 例 | 表示 |
|---|---|---|---|---|---|
| `"rebate"` | 20.0 | — | 後日ポイント | PayPay 渋谷区 20%還元 | 「20% 還元」 |
| `"rebate"` | — | 100 | 後日ポイント | PayPay セブン 100pt 付与 | 「100円相当 還元」 |
| `"coupon_percent"` | 3.0 | — | 即時割引 | au PAY ライフ 3%割引 | 「3% OFF」 |
| `"coupon_fixed"` | — | 100 | 即時割引 | d払い セブン 100円引き | 「100円引き」 |

**rebate と coupon の違い:**
- `rebate`: 後日ポイント付与（PayPay は原則30日後）。**PayPay の「クーポン」も実態は rebate**
- `coupon_percent` / `coupon_fixed`: その場で決済金額が減る即時割引

共通フィールド:
- `per_transaction_cap` / `period_total_cap`: 還元/割引の上限
- `min_purchase`: 適用条件の最低購入額（「200円以上の決済で」等）
- `usage_limit`: 利用回数上限（null = 無制限、1 = 1回限り）
- `usage_limit_note`: 人間向け補足

### 6.2 判定エンジンでの比較

`rebate` と `coupon` の比較、定率と定額の比較は単純にできない（100円引き vs 7%還元は購入額に依存）。初回実装では:

- **同じ表現形式（定率同士 / 定額同士）は率/額で比較** してソート
- **定率と定額は並列表示** し、ユーザーの判断に委ねる（「○○円の買い物なら…」の計算は将来課題 — Phase 3 の期待価値スコアと統合）
- **rebate と coupon（即時割引）はタイミングが異なるため区別表示** する（「還元」vs「割引」のラベル）
- `bestOption` は表現形式ごとに 1 つずつ提示（「還元で最もお得: 三井住友 7%」「割引で最もお得: d払い 100円引き」）

### 6.3 情報収集

クーポン情報は還元施策以上に短期・大量で、収集負荷が高い:
- PayPay クーポン: アプリ内で随時配布。全員配布系の大型クーポンのみ手動で収録
- d払い/auPAY クーポン: 同上
- **収録基準**: 全員配布 AND （割引率 5% 以上 OR 割引額 100 円以上） AND 主要チェーン対象

初回はクーポン対応のスキーマと表示ロジックを実装しつつ、データ投入はごく少数に留める。運用が回ったらスキルで半自動化。

---

## 7. 実装フェーズ

期間限定キャンペーン対応は複数のフェーズに分けて段階的に実装する。

### Phase A: データ基盤（スキーマ拡張 + 判定エンジン）

**データ面:**
1. campaigns.json に `type` / `benefit_type` / `per_transaction_cap` / `period_total_cap` / `cap_note` / `discount_amount` / `min_purchase` / `usage_limit` / `region` / `campaign_url` / `store_search_url` / `store_scope` を追加（既存キャンペーンは `type: "card_program"`, `benefit_type: "rebate"`, `store_scope: "managed"` で後方互換）
2. merchants.json に `yolp_config` / 各 merchant に `yolp_search` / `yolp_keyword` を追加
3. profile.json に `qr_payments` を追加
4. `data/municipalities.json` を作成（全国自治体マスタ）
5. data/README.md にスキーマ変更を反映
6. `schema_version` を 2 に上げる

**ドメインレイヤ:**
7. `JudgmentEngine` に期間フィルタを実装（`period_start` / `period_end` による active/upcoming/expired 判定）
8. `JudgmentEngine` に QR 決済・クーポン割引の判定ロジックを追加
9. 「探す」タブでは `store_scope == "managed"` のみ表示するフィルタ
10. ユニットテスト（期限切れ非表示、もうすぐ開始、上限表示、QR フィルタ、クーポン表示、benefit_type 別比較）

**データレイヤ:**
11. `Models.kt` にデータクラス追加（`QrPayment`, `Region`, `BenefitType` 等）
12. `DataRepository` で `schema_version` 2 のパースに対応

**見積もり:** 中規模。テスト込みで 2〜3 日。

### Phase B: YOLP 検索のデータ駆動化

13. `YolpClient` を `YolpSearchConfig` ベースに改修（gc_group ごとの `max_pages` 対応含む）
14. `MainViewModel` で アクティブ campaign → 検索対象 merchant → `YolpSearchConfig` を構築するロジックを実装（該当 merchant がいない gc_group はスキップ）
15. 既存の `GENRE_CODES` / `KEYWORD_QUERIES` を削除
16. 既存の YOLP 検索がデータ駆動で同じ結果を返すことのユニットテスト

**見積もり:** 小〜中規模。1〜2 日。ただし実 API での動作確認が重要。

### Phase C: キャンペーンタブ + ナビゲーション変更

17. `NavigationBar` を 4 タブ化（探す/近く/キャンペーン/設定）
18. 設定画面をオーバーレイからタブに移行（`showSettings` → ナビゲーション状態として表現）
19. キャンペーン一覧画面の実装（フィルタチップ、カードリスト、開催中/もうすぐのセクション分け。**期限切れは非表示**）
20. 同一自治体の複数決済手段マージ表示
21. 地図モード（full-bleed）の TopAppBar 変更（歯車アイコン → タブに移動したため撤去）

**見積もり:** 中規模。2〜3 日。

### Phase D: 検索・判定画面の期間限定対応

22. 判定結果カードに期間限定バッジ・クーポン表示を追加（「カード決済」「QR決済」セクション分け、全決済手段を一画面に）
23. 判定詳細に期間/上限/クーポン条件/最適決済の提案を追加
24. 外部誘導ボタン（card_promotion × QR 決済 → 各 QR アプリ）
25. 近くタブのプレビューに期間限定バッジを追加

**見積もり:** 中規模。2〜3 日。

### Phase E: 設定画面の拡張

26. QR 決済の利用登録 UI
27. 自治体の登録 UI（都道府県ドロップダウン → 市区町村選択）
28. DataStore への保存・ViewModel へのマージ

**見積もり:** 小〜中規模。1〜2 日。

### Phase F: データ投入と実機検証

29. 実際の自治体キャンペーンデータを 2〜3 件投入（例: 2026年7月の PayPay 自治体施策）
30. 実際のカード会社期間限定キャンペーン・クーポンがあれば投入
31. 実機 3 台（Android 10/12/16）での検証
32. data/README.md の更新ルールに期間限定キャンペーン・クーポンの運用手順を追加

**見積もり:** 1 日。

**合計見積もり: 10〜16 日**（Phase A → F の順序依存は A→B→(実機確認)、A→C、A→D、A→E。C/D/E は並行可能）

---

## 8. データメンテナンスの運用

### 8.1 既存の月次更新（変更なし）
- 常設施策の `verified_date` 更新
- 対象店舗リストの変更確認

### 8.2 期間限定キャンペーン・クーポンの追加・更新（新規）

**情報源:**
- PayPay: [自治体キャンペーン告知ページ](https://paypay.ne.jp/event/support-local/)（月次で翌月以降分を公開）/ アプリ内クーポン
- au PAY: au PAY 公式ニュース / アプリ内クーポン
- d払い: d払い公式 / アプリ内クーポン
- 楽天ペイ: 楽天ペイ公式
- カード会社: 各社の Web サイト・メール通知
- まとめサイト: kojinabi.com 等（二次情報として参照）

**収録基準:**
- 自治体施策: 還元率 5% 以上
- カード会社期間限定: 還元率 5% 以上
- クーポン: 割引率 5% 以上 OR 割引額 100 円以上 AND 全員配布 AND 主要チェーン対象

**運用フロー:**
1. 月末に翌月の自治体キャンペーン情報を収集
2. campaigns.json に追加（`type` / `benefit_type` / 期間・上限・URL）
3. カード会社の期間限定・クーポンがあれば追加（必要に応じて merchants.json にも新規 merchant を追加）
4. `./gradlew :app:testDebugUnitTest` で整合性チェック
5. main にプッシュ（アプリは GitHub raw を取得するため、プッシュ時点でユーザーに反映）

### 8.3 Skills 化の提案

以下の運用をスキルとして定義し、データ更新の半自動化を図る:

| スキル名 | 内容 |
|---|---|
| `campaign-update` | キャンペーン・クーポン情報の追加・更新。Web 検索で最新情報を収集し、campaigns.json の差分を生成。verified_date の更新も含む |
| `merchant-add` | 新規チェーンの追加。名前・読み・エイリアス・カテゴリ・YOLP 検索設定を対話的に設定し、merchants.json に追加 |
| `data-validate` | campaigns.json / merchants.json / profile.json の整合性チェック。merchant_id の参照切れ、期限切れの確認、YOLP 検索設定の妥当性チェック |

---

## 9. 決定事項（レビューフィードバック反映）

| # | 事項 | 決定 |
|---|---|---|
| 8.1 | 自治体マスタの持ち方 | **案 C ベース**: 全国データを同梱。都道府県選択式+市区町村選択。東京都は 23区/市部のグループ表示。将来の area_group フィルタは `region.area_group` で用意 |
| 8.2 | stacking 表示 | **セクション分け**: 「カード決済の場合」「QR 決済の場合」。ただし全決済手段を一画面に並べることを優先 |
| 8.3 | 期限切れの表示 | **UI には一切表示しない（必須）**。データは終了後 30 日保持、その後手動削除 |
| 8.4 | 収録粒度 | 還元率/割引率 **5% 以上**（当初案 10% から引き下げ） |
| 8.5 | 通知 | 現段階では実装しない。将来: アプリ起動時の Snackbar |
| 8.6 | schema_version 互換 | 新フィールドは追加のみ。`ignoreUnknownKeys = true` で後方互換 |
| 追加 | 探すタブでの自治体施策 | **初回実装では非表示**。`store_scope == "managed"` のみ。チェーン店が対象外の場合が多いため |
| 追加 | 同一自治体のマージ | キャンペーンタブで同一 `region.name` × 期間重複をマージ表示 |
| 追加 | クーポン割引 | `benefit_type` で rebate / coupon_percent / coupon_fixed を区別。`rate_base` と `discount_amount` は排他で定率/定額を表現。rebate でも `discount_amount` を使える（定額ポイント還元用）。PayPay「クーポン」は実態が後日ポイント付与のため rebate に分類。初回から対応 |
| 追加 | 自治体グループ | `region.area_group` をデータに用意（初回 null）。UI でのグループ登録は将来 |

## 10. 将来の検討候補（ドキュメント記録用）

以下は初回実装の対象外だが、設計時に検討した将来候補。

| 候補 | 内容 | 前提・トリガー |
|---|---|---|
| カスタムキャンペーン | ユーザーが自分でキャンペーン情報を追加・編集 | 運用負荷が高まり「自分でやりたい」需要が出たとき |
| 通知・リマインダー | キャンペーン開始/終了間近のプッシュ通知 | プッシュ通知基盤の導入後。Firebase Messaging 等 |
| 自治体グループ登録 | 「東京23区をまとめて登録」のような操作 | area_group のデータが整備されてから |
| 探すタブでの自治体施策表示 | 「この店は ○○区の施策対象かも」の表示 | 初回の運用で需要と精度を見てから判断 |
| rebate vs coupon の自動比較 | 「○○円の買い物なら還元が得、○○円ならクーポンが得」の計算 | Phase 3（期待価値スコア）と統合 |
| クーポン収集の自動化 | QR 各社のクーポン情報をスクレイピングで取得 | 規約確認＋スクレイピング基盤の整備後 |
| 自治体マスタの自動更新 | 総務省の全国地方公共団体コード等のオープンデータから municipalities.json を自動生成・更新する仕組み。現在は手動生成のため、市町村合併・名称変更時に手動対応が必要 | 合併等でデータが陳腐化したとき。GitHub Actions での定期生成が候補 |
