# 還元施策データ

アプリが読み込む施策マスタ。当面はアプリの assets に同梱し、Phase 1 の M4 で GitHub raw 配信に切り替える。

## ファイル

- `merchants.json` — チェーン店マスタ。`reading`(ひらがな読み)と `aliases`(略称・別ブランド名)は検索のヒット率に直結するので、追加時は必ず入れる。位置情報を持たない発行体(自販機など)は `location_hint`(`text`/`label`/`url`)を持たせる。これがあると判定詳細で「近くのこの店を探す」を出さず、代わりに位置を確認できる外部アプリ/サイトへ案内する(例: コカ・コーラ自販機 → Coke ON 公式アプリ)。`yolp_config` で YOLP 検索の gc グループ設定、各 merchant の `yolp_search`/`yolp_keyword` で検索方式を持つ(§ YOLP 検索設定 参照)。
- `campaigns.json` — 還元施策。`merchant_rules[].merchant_id` は merchants.json の `id`、`card_id` は payment_methods.json の `cards[].id` を参照する。**ユーザー固有の前提はここに書かず、汎用的な施策情報のみを持つ。** 常設施策(`card_program`)・期間限定施策(`promotion`)・自治体施策(`municipal`) の 3 種類をサポート。
- `payment_methods.json` — 決済手段(カード + QR 決済)の**カタログ(マスタ)**。`cards` は現状: 三井住友(`smcc`、7%、`point_multiplier` でウエル活×1.5)、三菱UFJ(`mufg`、基準7%)。`brands` はそのカード製品で**選べるブランドの選択肢**で、実際に持っているブランドはユーザー設定(`CardOverride.brand`)に分離する(カタログにユーザー属性を混ぜない)。**設定画面でカード所有・還元率・ブランド・ウエル活を編集でき、差分はカード id をキーに DataStore に保存して起動時にこのカタログへ重ねる(payment_methods.json 自体は書き換えない)**。判定エンジンは**所有カードのみ**を対象とし、実ブランドが Amex(または未選択で Amex を取りうる)なら `amex_excluded` の店を除外・他ブランドなら無視、`effective_rate_default` を実効還元率として用いる。`qr_payments` に QR 決済サービスのカタログを持つ。
- `municipalities.json` — 全国自治体マスタ(47 都道府県・約 1,700 市区町村)。設定画面で居住地・行動圏の自治体を登録する際のピッカーデータとして使う。現在は手動生成だが、将来的には総務省の全国地方公共団体コード等のオープンデータから自動生成する方針(roadmap.md バックログ参照)。アプリ固有のロジック(東京 23区/市部 のグループ分け等)は UI コード側で扱い、マスタ自体はフラットな構造を保つ。

## スキーマの要点

### campaigns.json

- `type` — 施策種別。判定エンジン・UI の分岐に使う:
  - `"card_program"`: 常設カードプログラム(既存の SMCC/MUFG)。`merchant_rules` で管理、「探す」「近く」タブに表示
  - `"promotion"`: カード/QR 会社の期間限定(特定チェーン対象)。`merchant_rules` で管理、「探す」「近く」タブに表示
  - `"municipal"`: 自治体施策(店舗データなし)。キャンペーンタブにのみ表示(`detail_url`/`store_search_url` で公式ページへリンク)
- `operator` — 施策の運営者(カード会社・QR 決済事業者)。バッジ表示のフォールバックに使う
- 施策の帰属は **`card_id` / `card_brand` / `payment_method_id` のちょうど 1 つ**(残り 2 つは null):
  - `card_id` — 紐づくカード(payment_methods.json の `cards[].id`)。card_program / promotion で使う。1 カードに複数施策を紐づけられる
  - `card_brand` — ブランド施策(イシュアー不問。例: 「タッチで Visa 割」、Amex 30% OFF)の対象ブランド。値は payment_methods.json の `card_brands` にあるものを使う。所有カードのうち実ブランド(ユーザー設定。単一ブランド製品は自動確定)が一致するカードが 1 枚でもあれば判定に出る(複数一致でも判定は施策につき 1 件)。バッジは特定カード名でなく**ブランド名**(イシュアー不問のため)。**カタログに無いカードの保有ブランド**は設定画面「カードブランド」(DataStore の `owned_brands` に保存)で登録でき、仮想カードとしてマッチする。セクションは常時表示し、事前登録しておけば施策開始と同時に判定へ現れる
  - `payment_method_id` — QR 決済(後述)
- `benefit_type` — 特典のタイミング(3 値)。省略時は `"rebate"`:
  - `"rebate"`: ポイント還元(後日ポイント付与)。PayPay の「クーポン」も実態は後日ポイント付与のため rebate に分類
  - `"discount"`: 即時割引。定率(`rate_base`)か定額(`discount_amount`)かはフィールドから導出
  - `"lottery"`: 抽選型(PayPay スクラッチくじ等)。確定還元ではないため**「最良特典」比較には載せない**(判定詳細・キャンペーンタブに「抽選」として表示のみ)。当選確率・最大額は `conditions` の文章で持ち、`rate_base` / `discount_amount` はどちらも null にする
- `store_scope` — 店舗データの有無:
  - `"managed"`: `merchant_rules` で管理。「探す」「近く」タブに表示
  - `"external"`: 外部参照のみ。キャンペーンタブにのみ表示
- `payment_method_id` — QR 決済の識別子(payment_methods.json の `qr_payments[].id` と対応)。カード施策は null
- `rate_base` — 定率の場合の率(%)。定額の場合は null。常設カード施策では現実的な基準還元率
- `discount_amount` — 定額の場合の金額(円)。定率の場合は null
  - **`rate_base` と `discount_amount` は排他(どちらか一方が non-null)**
- `per_transaction_cap` — 1 回あたりの付与/割引上限(円相当)。null = 上限なし
- `period_total_cap` — 期間合計の付与/割引上限(円相当)。null = 上限なし
- `cap_note` — 上限の但し書き（数値で表せない補足専用）。`per_transaction_cap` / `period_total_cap` と重複する情報は書かない（UI で数値から自動生成する）
- `min_purchase` — 適用条件の最低購入額(円)。例: 200 →「200 円以上の決済で」
- `usage_limit` — 利用回数上限。null = 期間中無制限、1 = 1 回限り
- `usage_limit_note` — 利用条件の人間向け補足
- `eligible_wallets` / `ineligible_wallets` — **公式がウォレット単位で還元対象/対象外を言い切っている場合のみ**登録する(値: `"apple_pay"` / `"google_pay"`)。未掲載 = 不明として扱い、網羅性を仮定しない(official_store_list と同じ3状態の設計思想)。抽象フラグにしないのは「Apple Pay は対象・Google Pay は対象外」(MUFG)のような非対称な事実を表現するため:
  - `eligible_wallets` に `google_pay` → 判定詳細に「ウォレット(Google Pay)を開く」起動リンクを表示
  - `ineligible_wallets` に `google_pay` → 判定詳細に「Google Pay での支払いは還元対象外」警告を表示(Android ユーザーが自然に Google Pay でタッチして還元を取り逃すのを防ぐ)
  - どちらにも無い → 何も出さない(`payment_instruction` の文章が担う)
  - `apple_pay` エントリはアプリでは読まないが、sources と同じ「検証済み事実の記録」として断定できるものだけ書く(プラットフォーム非依存の施策側の事実。Android 固有の消費はコード側に閉じる)
- `may_end_early` — 予算到達次第の早期終了があり得るか(省略時 false)。true なら判定詳細・キャンペーンタブに「早期終了の可能性」注記を出し、「残り○日」が断定に見えないようにする。**自治体系はほぼ全件 true にする**(標準条項のため)
- `recurrence` — 繰り返し日付条件。`{ "days_of_week": ["FRI", "SAT"] }`(毎週金土)または `{ "days_of_month": [20, 30] }`(毎月20・30日)の**どちらか一方**(併用は実在確認できるまで未対応)。`period_start/end`(外枠の開催期間)と併用し、「探す」「近く」の判定は期間内かつ**今日が対象日**のときだけ出す。キャンペーンタブは期間内なら非対象日でも「開催中」に出し「次の対象日: ○/○」を案内する
- `region` — 自治体施策用。`{ name, prefecture, area_group }`。`area_group` は将来のグループフィルタ用(現在 null)
- `detail_url` — 施策の詳細ページ URL（全タイプ共通。ユーザーに「詳細はこちら」として案内する先）
- `store_search_url` — 対象店舗検索ページ URL(PayPay 等の公式)
- `period_start` / `period_end` — 施策期間(ISO 8601 日付)。null = 常設
- `merchant_rules[].rate_override` — その店だけ還元率が異なる場合の上書き値(%)。非 null ならその merchant では `rate_base` の代わりに使う(自治体系の「中小20%/大手10%」、Visa 系の「基礎+特定店で追加」等)。判定と一覧の「最良特典」計算に反映され、キャンペーンタブのサマリーは rate_base と rate_override の最大値を「最大○%」として出す。card_program ではユーザー設定の実効率が優先されるため事実上 promotion / QR 施策用。
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
- `merchant_rules[].amex_excluded` — Amex ブランドだと優遇対象外の店(汎用フラグ。現状使うのは MUFG のデータのみ)。実ブランドが Amex の場合、これらの店は判定・検索・地図から除外される。**ブランド未選択でもそのカードが Amex を取りうる(`brands` に Amex を含む)なら除外側に倒す**(不確かな情報で実際より好条件を提示しない方針。実ブランドを選択すると正確になる)。
- `verified_date` — 公式ページで最後に確認した日。**判定画面に必ず表示する。**
- 識別色(brand_color)は campaigns.json には**持たない**。発行体(payment_methods.json の cards / card_brands / qr_payments)側で一元管理し、アプリが帰属(card_id / card_brand / payment_method_id)から解決する(同一発行体の施策間で色がぶれないようにするため。§ payment_methods.json 参照)

### payment_methods.json

- `card_brands` — 登録できるカードブランドの選択肢(国際ブランドのマスタ)。`{ name, color }` で、`name` は campaigns.json の `card_brand` から参照され(整合性テストで強制)、設定画面「カードブランド」に常時表示する。`color` はブランド施策の識別色
- **識別色(`brand_color` / `color`)** — 発行体ごとに 1 色をここで一元管理し、施策のストライプ/バッジ/地図ピンは帰属先(カード/ブランド/QR)から解決する。**ロゴ画像は商標・著作権の問題があるため使用しない**(公開リポジトリでの再配布になる)。色には権利が及ばないのでブランドカラーで識別する。
  - カード: 三井住友=フレッシュグリーン `#00A94F`(SMFG VI にはトラッドグリーン `#004831` もあるが、視認性と従来表示の継続のため明るい方に統一)、三菱UFJ=MUFGレッド `#E60000`
  - ブランド: Visa `#1A1F71`、Mastercard `#EB001B`、JCB `#005BAC`、Amex `#016FD0`(各社ロゴの近似色)
  - QR 決済: PayPay `#FF0033`、au PAY `#FF5722`、d払い `#E60033`、楽天ペイ `#BF0000`
- `cards` — カードのカタログ。`{ id, card_name, brand_color, brands, effective_rate_default, point_multiplier }`。`id`(例: `"smcc"`)は campaigns.json の `card_id` と DataStore のカード差分キーから参照される
- `brands` — そのカード製品で**選べるブランドの選択肢**(カタログの事実。例: 三菱UFJカードは Visa/Mastercard/JCB/Amex)。**ユーザーが実際に持っているブランドはカタログに置かず** `CardOverride.brand`(DataStore)で持つ。`brands` が単一なら自動確定、複数なら未選択(空)から設定画面で選ぶ。未選択の間は**好条件側に倒さない**: `card_brand` 施策には一致せず(特典を出さない)、Amex 除外はそのカードが Amex を取りうる限り除外側に倒す。加えて、ブランドが判定に効くカードは有効化時にブランド選択を必須にしている
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

## data-test/ — ショーケースデータ

`data-test/` は実機検証用のテストデータ。実データ(`data/`)では網羅できないパターン(4 象限の特典、UPCOMING、残り 3 日警告、Amex 除外、複数施策競合など)を 1 画面で見渡すための構成。

### 切替方法

設定画面 → 開発者向け → 「テストデータを使う」トグルを ON にすると、アプリのリモート取得先が `data/` から `data-test/` に切り替わる。`data-test/` のデータは `data/` と同じスキーマ(campaigns.json / merchants.json / payment_methods.json)に従い、カードもテスト専用カタログ(`test_card`)に切り替わる。municipalities.json のみ assets 固定のため切替対象外。

### 収録パターン一覧

| ID | 検証対象 | 安定性 |
|----|---------|--------|
| `test_card_program` | 常設 rebate+rate(7%)、Amex 除外(`test_super`)、`official_store_list` 3 状態、`store_list_url`、`location_hint`(`test_vending`)、`cap_note`、`eligible_wallets`(ウォレット起動リンク) | 常時安定 |
| `test_promotion` | 期間限定 rebate+rate(10%)、`rate_override`(15%)、`may_end_early`、`ineligible_wallets`(Google Pay 対象外警告) | 常時安定 |
| `test_brand_promotion` | `card_brand`(Visa)、即時定率 discount+rate(30% OFF)、`per_transaction_cap` | 常時安定 |
| `test_recurrence_weekly` | `recurrence` 曜日型(毎週金土) | 検証日依存 |
| `test_recurrence_monthly` | `recurrence` 日付型(5・20・30 日) | 検証日依存 |
| `test_lottery` | 抽選型(`lottery`)、`conditions` | 常時安定 |
| `test_discount_fixed` | **即時定額** discount+`discount_amount`(300 円引き)、`min_purchase`(500 円)、`usage_limit`(1 回) | 常時安定 |
| `test_rebate_fixed` | **後日定額** rebate+`discount_amount`(500 円還元)、`usage_limit`(3 回)、`usage_limit_note`、`period_total_cap` | 常時安定 |
| `test_upcoming` | **UPCOMING** 状態(常時未開始) | 常時安定 |
| `test_ending_soon` | **残り 3 日警告**(検証日に `period_end` を手直し) | **要手直し** |
| `test_municipal` | 自治体施策(`municipal`+`external`)、`region`、`store_search_url`、`per_transaction_cap`+`period_total_cap`、`may_end_early` | 常時安定 |

#### 複数施策競合の確認

- **テストコンビニ**: test_card_program(7%)・test_promotion(10%)・test_recurrence_weekly(20% 金土)・test_lottery(抽選)・test_rebate_fixed(500 円還元 PayPay)・test_upcoming(25% 未開始)・test_ending_soon(15% 終了間近)
- **テストバーガー**: test_card_program(7%)・test_promotion(15% override)・test_brand_promotion(Visa 30% OFF)・test_recurrence_monthly(12% 5・20・30 日)・test_discount_fixed(300 円引き PayPay)・test_upcoming(25% 未開始)

#### 日付依存パターンの手直し手順

- **`test_ending_soon`**: `period_end` を「検証日の 3 日後」に設定する(例: 7/10 に検証するなら `"2026-07-13"`)。残り 0〜3 日で警告が表示される
- **`test_upcoming` → ACTIVE 遷移**: 常時 UPCOMING(2099 年)。ACTIVE 状態を見たい場合は `period_start` を検証日以前に変更する
- **`test_recurrence_weekly`**: 金・土曜に検証すると「対象日」、他の曜日では「次の対象日: ○/○」が表示される
- **`test_recurrence_monthly`**: 5・20・30 日に検証すると「対象日」、他の日では「次の対象日」が表示される

### 更新ルール

- `data/` のスキーマ変更時は `data-test/` も同時に更新する(同一コミット)
- CI の整合性テスト(`TestDataIntegrityTest`)がパース成功・参照切れ・フィールド排他を検証する
- 日付依存パターンは 2 種類: 常時安定(期間を極端な未来/過去に設定)と検証時要手直し(残り 3 日警告等)。前者を基本とする

## 更新ルール

- 月 1 回、`sources` の公式 URL を確認して `verified_date` を更新する。
- 施策の改定があったら率・店舗リストを直し、`updated_at` を更新する。
- 整合性チェック: merchant_id の参照切れ・エイリアス衝突がないこと(リポジトリの検証スクリプトを流す)。

### 期間限定キャンペーン・クーポンの運用

- **収録基準**: 還元率 5% 以上(自治体施策・カード会社期間限定)。クーポンは割引率 5% 以上 OR 割引額 100 円以上 AND 全員配布 AND 主要チェーン対象。
- **情報源**: PayPay 自治体キャンペーン告知、au PAY/d払い/楽天ペイ公式、カード会社 Web サイト・メール通知。
- **運用フロー**: 月末に翌月分を収集 → campaigns.json に追加 → テスト → main にプッシュ。
- **期限切れデータ**: UI には一切表示しない。データは終了後 30 日保持し、その後手動削除。
