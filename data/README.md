# 還元施策データ

アプリが読み込む施策マスタ。当面はアプリの assets に同梱し、Phase 1 の M4 で GitHub raw 配信に切り替える。

## ファイル

- `merchants.json` — チェーン店マスタ。`reading`(ひらがな読み)と `aliases`(略称・別ブランド名)は検索のヒット率に直結するので、追加時は必ず入れる。位置情報を持たない発行体(自販機など)は `location_hint`(`text`/`label`/`url`)を持たせる。これがあると判定詳細で「近くのこの店を探す」を出さず、代わりに位置を確認できる外部アプリ/サイトへ案内する(例: コカ・コーラ自販機 → Coke ON 公式アプリ)。`yolp_config` で YOLP 検索の gc グループ設定、各 merchant の `yolp_search`/`yolp_keyword` で検索方式を持つ(§ YOLP 検索設定 参照)。
- `campaigns.json` — 還元施策。`merchant_rules[].merchant_id` は merchants.json の `id` を参照する。**ユーザー固有の前提はここに書かず、汎用的な施策情報のみを持つ。** 常設施策(`card_program`)・期間限定施策(`card_promotion`)・自治体施策(`municipal`) の 3 種類をサポート。
- `profile.json` — ユーザー前提条件の**デフォルト値(カタログ)**。現状: 三井住友=Visa(7%, `point_multiplier` でウエル活×1.5)、MUFG=Mastercard(基準7%)。**設定画面でカード所有・還元率・ブランド・ウエル活を編集でき、差分は DataStore に保存して起動時にこのプロファイルへ重ねる(profile.json 自体は書き換えない)**。判定エンジンは**所有カードのみ**を対象とし、brand が Amex なら `amex_excluded` の店を除外・Mastercard 等なら無視、`effective_rate_default` を実効還元率として用いる。`qr_payments` に QR 決済サービスのカタログを持つ。
- `municipalities.json` — 全国自治体マスタ(47 都道府県・約 1,700 市区町村)。設定画面で居住地・行動圏の自治体を登録する際のピッカーデータとして使う。現在は手動生成だが、将来的には総務省の全国地方公共団体コード等のオープンデータから自動生成する方針(design-campaigns.md §10)。アプリ固有のロジック(東京 23区/市部 のグループ分け等)は UI コード側で扱い、マスタ自体はフラットな構造を保つ。

## スキーマの要点

### campaigns.json

- `type` — 施策種別。判定エンジン・UI の分岐に使う:
  - `"card_program"`: 常設カードプログラム(既存の SMCC/MUFG)
  - `"card_promotion"`: カード/QR 会社の期間限定(特定チェーン対象)
  - `"municipal"`: 自治体施策(店舗データなし)
- `benefit_type` — 特典の形態。省略時は `"rebate"`:
  - `"rebate"`: ポイント還元(後日ポイント付与)。PayPay の「クーポン」も実態は後日ポイント付与のため rebate に分類
  - `"coupon_percent"`: 即時割引(定率)。`rate_base` が割引率(%)
  - `"coupon_fixed"`: 即時割引(定額)。`discount_amount` が割引額(円)
- `store_scope` — 店舗データの有無:
  - `"managed"`: `merchant_rules` で管理(card_program / card_promotion)。「探す」「近く」タブに表示
  - `"external"`: 外部参照のみ(municipal)。キャンペーンタブにのみ表示
- `payment_method_id` — QR 決済の識別子(`profile.json` の `qr_payments.id` と対応)。カード施策は null
- `rate_base` — 定率(rebate/coupon_percent)の場合の率(%)。定額の場合は null。常設カード施策では現実的な基準還元率
- `discount_amount` — 定額(rebate 固定額/coupon_fixed)の場合の金額(円)。定率の場合は null
  - **`rate_base` と `discount_amount` は排他(どちらか一方が non-null)**
- `per_transaction_cap` — 1 回あたりの付与/割引上限(円相当)。null = 上限なし
- `period_total_cap` — 期間合計の付与/割引上限(円相当)。null = 上限なし
- `cap_note` — 上限の人間向け補足
- `min_purchase` — 適用条件の最低購入額(円)。例: 200 →「200 円以上の決済で」
- `usage_limit` — 利用回数上限。null = 期間中無制限、1 = 1 回限り
- `usage_limit_note` — 利用条件の人間向け補足
- `region` — 自治体施策用。`{ name, prefecture, area_group }`。`area_group` は将来のグループフィルタ用(現在 null)
- `campaign_url` — キャンペーン公式ページ URL
- `store_search_url` — 対象店舗検索ページ URL(PayPay 等の公式)
- `period_start` / `period_end` — 施策期間(ISO 8601 日付)。null = 常設
- `rate_base` — 現実的な基準還元率。判定画面で主表示する。理論上の最大還元率(家族ポイント等の積み上げ)は「キリがなく本質から外れる」ため持たない。
- `entry_required` — エントリー必須か(施策メタデータ)。MUFG はエントリー+三菱UFJ銀行口座がないと 0.5% に落ちる。**還元率はユーザーが公式アプリの実効値を手入力する方針のため、現在この値による警告は出していない**(将来の汎用「要エントリー」表示の余地として残す)。
- `merchant_rules[].note` — その店でのみ成立する条件(例: スタバはモバイルオーダーのみ)。
- `merchant_rules[].exclusion_note` — 対象外になるケースの説明文(人間向け)。「一部対象外店舗があります」程度の但し書きとして判定詳細に表示する。**公式が店舗単位で対象/対象外を言い切っていない情報(「例: ○○店」レベルの例示)はここに文章で書くにとどめ、`official_store_list` には入れない**。
- `merchant_rules[].store_list_url` — 「一部店舗のみ対象」のチェーン(サイゼリヤ・KFC等)で、公式の対象店舗一覧へのリンク。判定詳細から開ける。
- `merchant_rules[].official_store_list` — **公式が対象/対象外を店舗名で言い切っているリストがある場合だけ**設定する。これがあるチェーンのみ「この店舗が対象か調べる」別画面に遷移でき、入力店舗名を判定する。判定は3状態:
  - `ineligible_stores` に一致 → **対象外**(⛔)。`eligible_stores` に一致 → **対象**(✅)。どちらにも無い → **要確認**(❓。公式リスト外。一部対象外店舗があるため断定しない)。対象外(ineligible)を優先判定する。
  - 各 store は店舗名の部分文字列(正規化後 `contains` 判定。カナ種・全半角・記号は正規化で吸収されるので「らら/ララ」等は気にしなくてよい)。識別できる範囲で短く書く(「ららぽーと豊洲」等、`店`接尾辞は不要)。
  - 片方のリストだけでも可。ただし「一致しなければ反対」と断定はせず、未掲載は常に要確認になる(網羅性を仮定しない設計)。
  - `updated_date` + `date_is_official` — 断定の鮮度として判定画面に表示する日付。`date_is_official: true` なら**公式情報自体の更新日**、`false` なら**当方の確認日**(「公式に更新日記載なし」付き)として表示。いずれもこのアプリのデータ更新日 `verified_date`/`updated_at` とは別物。
  - `source_url` — (任意)根拠とした公式ページ。
  - 例(アカチャンホンポ/MUFG): 公式([akachan.jp](https://www.akachan.jp/topics/mufgCPlist/))が◯対象/×対象外を店舗名で明示。両方を `eligible_stores`/`ineligible_stores` に登録し、未掲載店は要確認。公式ページに更新日表記が無いため `date_is_official: false`(確認日表示)。
  - 注意: 網羅的でない例示リストをここに入れると「非一致=対象」を誤って断定してしまう。断定できる完全なリストだけを登録すること。
- `merchant_rules[].amex_excluded` — (MUFGのみ) Amex ブランドだと優遇対象外の店。設定でブランドを Amex にした場合、これらの店は判定・検索・地図から除外される。
- `verified_date` — 公式ページで最後に確認した日。**判定画面に必ず表示する。**
- `brand_color` — 発行体の識別色(#RRGGBB)。UIのストライプ/バッジに使う。**ロゴ画像は商標・著作権の問題があるため使用しない**(公開リポジトリでの再配布になる)。色には権利が及ばないのでブランドカラーで識別する。
  - 確認済み: 三井住友=トラッドグリーン `#004831` (SMFG VI)、三菱UFJ=MUFGレッド `#E60000`
  - QR 決済: PayPay `#FF0033`、au PAY `#FF5722`、d払い `#E60033`、楽天ペイ `#BF0000`

### profile.json

- `cards` — カード施策のカタログ(既存)
- `point_multiplier`(任意) — ポイント価値の倍率。`{ label, factor, color }`。設定画面で「ウエル活利用時の還元率を表示」チェックを出し、ON で `factor` 倍した実効還元率を表示する。`color` はバッジ色(ウエルシアのロゴ色 #RRGGBB)。三井住友(Vポイント)に設定。
- `qr_payments` — 利用中の QR 決済サービスのカタログ。`{ id, name, brand_color, app_package, store_search_label, enabled_default }`。設定画面でチェックした QR 決済が判定エンジンのフィルタに使われる。DataStore に差分保存。

### merchants.json — YOLP 検索設定

- `yolp_config.gc_groups` — YOLP の gc(ジャンルコード)グループ定義。各 group = 1 つの YOLP ソース(1 コール系列)。密度の近いカテゴリは同一グループに、密度差が大きいものは別グループにする。
  - `gc`: カンマ OR で 1 リクエストにまとめられる gc コード
  - `categories`: このグループに含まれるカテゴリ名(参照用)
  - `max_pages`: このソースの最大ページ数(密度チューニング用)
- `yolp_config.max_keyword_sources` — キーワードソースの上限
- 各 merchant の `yolp_search`:
  - `"gc"`(デフォルト): カテゴリに紐づく gc コードで一括取得
  - `"keyword"`: 店名キーワードで個別取得(gc で取れないチェーン)。`yolp_keyword` があればそれを使い、なければ `name` を使う
  - `"none"`: YOLP で検索しない(`location_hint` があるもの等)

### municipalities.json

都道府県名をキー、市区町村名の配列を値とする単純な Map 構造。

```json
{ "北海道": ["札幌市", "函館市", ...], "東京都": ["千代田区", ..., "八王子市", ...], ... }
```

- 47 都道府県を標準順序(JIS)で保持
- 東京都は 23 特別区を先に、その後に市・町・村の順で並べる(グループ分けは UI コード側)
- 政令指定都市は市名のみ(行政区は含めない)
- リモート更新の対象外(assets 同梱のみ)

## 更新ルール

- 月 1 回、`sources` の公式 URL を確認して `verified_date` を更新する。
- 施策の改定があったら率・店舗リストを直し、`updated_at` を更新する。
- 整合性チェック: merchant_id の参照切れ・エイリアス衝突がないこと(リポジトリの検証スクリプトを流す)。

### 期間限定キャンペーン・クーポンの運用

- **収録基準**: 還元率 5% 以上(自治体施策・カード会社期間限定)。クーポンは割引率 5% 以上 OR 割引額 100 円以上 AND 全員配布 AND 主要チェーン対象。
- **情報源**: PayPay 自治体キャンペーン告知、au PAY/d払い/楽天ペイ公式、カード会社 Web サイト・メール通知。
- **運用フロー**: 月末に翌月分を収集 → campaigns.json に追加 → テスト → main にプッシュ。
- **期限切れデータ**: UI には一切表示しない。データは終了後 30 日保持し、その後手動削除。
