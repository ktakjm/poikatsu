# 還元施策データ

アプリが読み込む施策マスタ。当面はアプリの assets に同梱し、Phase 1 の M4 で GitHub raw 配信に切り替える。

## ファイル

- `merchants.json` — チェーン店マスタ。1 merchant = 1 系列(施策の帰属単位)で、傘下の看板(店頭の名前。UI 表記は「業態」)は `banners` に持つ(§ 系列と看板 参照)。`reading`(ひらがな読み)と `aliases`(略称・別ブランド名)は検索のヒット率に直結するので、追加時は必ず入れる。位置情報を持たない発行体(自販機など)は `location_hint`(`text`/`label`/`url`/`app_package`(任意))を持たせる。これがあると判定詳細で「近くのこのお店を探す」を出さず、代わりに位置を確認できる外部アプリ/サイトへ案内する(例: コカ・コーラ自販機 → Coke ON 公式アプリ)。`app_package` を書くとインストール済みのアプリを直接起動し、未インストールなら `url` へフォールバックする(**パッケージ名は app の AndroidManifest `<queries>` と対で管理**。qr_payments の app_packages と同じ制約)。アプリ内に店舗マップがある確証が無い発行体は `url`(公式 Web マップ等)だけにする(例: ジハンピ → サントリー公式設置マップ)。`yolp_config` で YOLP 検索の gc グループ設定、各 merchant の `yolp_search`/`yolp_keyword` で検索方式を持つ(§ YOLP 検索設定 参照)。YOLP の提供データにそのチェーンの店舗がほぼ無いなど**地図に出にくい事実**は `yolp_coverage_note`(任意。表示文をそのまま持つ)に書き、判定詳細の「近くのこのお店を探す」直下に通常の補足として表示される(#70。対象外の意味ではないので warning にはしない)。**実測の根拠がある場合だけ書く**(推測で書かない)。alias 不足による取りこぼし(alias 補完で直せる)と、データセット自体に無いもの(補完で直せない)を混同しないこと(実例: OWNDAYS。#52 の実測記録参照)。
- `campaigns.json` — 還元施策。`merchant_rules[].merchant_id` は merchants.json の `id`、`card_id` は payment_methods.json の `cards[].id` を参照する。**ユーザー固有の前提はここに書かず、汎用的な施策情報のみを持つ。** 常設施策(`card_program`)・期間限定施策(`promotion`)・自治体施策(`municipal`) の 3 種類をサポート。
- `payment_methods.json` — 決済手段(カード + QR 決済)と**ポイント通貨のカタログ(マスタ)**。`cards` は現状: 三井住友(`smcc`、7%、Vポイント)、三菱UFJ(`mufg`、基準7%、グローバルポイント。#84)、JCBオリジナルシリーズ(`jcb_original`、最大10%、`card_classes` で W/S・`point_currency_id` で J-POINT(1pt 価値は通貨側の `point_value` で設定。#13)を参照)、dカード(`dcard`、特約店最大4%、dポイント)、エポスカード(`epos`、ポイントアップ優待最大2.5%。#59)。`brands` はそのカード製品で**選べるブランドの選択肢**で、実際に持っているブランドはユーザー設定(`CardOverride.brand`)に分離する(カタログにユーザー属性を混ぜない)。**設定画面でカード所有・還元率・ブランドを編集でき、差分はカード id をキーに DataStore に保存して起動時にこのカタログへ重ねる(payment_methods.json 自体は書き換えない)**。判定エンジンは**所有カードのみ**を対象とし、実ブランドが `ineligible_brands` に一致(または未選択でその除外ブランドを取りうる)ならその店を除外・リストに無いブランドなら無視、`effective_rate_default` を実効還元率として用いる。`qr_payments` に QR 決済サービスのカタログを持つ。`point_currencies` は**ポイント通貨・プログラムのマスタ**(#39): 倍率(ウエル活×1.5。WAON POINT の価値特性で、Vポイントも等価交換の連鎖で同倍率=同一グループで連動。#84)と会員プログラム(dポイント等。提示型施策の帰属先)を通貨単位で持ち、ウエル活 ON/OFF・会員かどうかは設定画面「ポイント」から DataStore(通貨 id の Set)に保存する。
- `municipalities.json` — 全国自治体マスタ(47 都道府県・1,741 市区町村・自治体グループ)。設定画面で居住地・行動圏を登録する際のピッカーデータと、おトクタブの地域フィルタ(グループ→自治体の展開)に使う。`scripts/generate_municipalities.py` が気象庁の予報区データから自動生成する(§ municipalities.json 参照)。

## スキーマの要点

### campaigns.json

- `type` — 施策種別。判定エンジン・UI の分岐に使う:
  - `"card_program"`: 常設カードプログラム(既存の SMCC/MUFG)。`merchant_rules` で管理、「お店」「地図」タブに表示。おトクタブでは「常設」セクションに出る(#44)
  - `"promotion"`: カード/QR 会社のキャンペーン。`store_scope` で 2 形(整合性テストで強制。#44):
    - `managed`(特定チェーン対象): `merchant_rules` で管理、「お店」「地図」タブにも表示。`period_start`/`period_end` と `merchant_rules` が必須(書き忘れると判定に一切出ない死にデータになるため)
    - `external`(全加盟店対象): 抽選会・全員対象キャンペーン等、チェーンを列挙できないもの。`merchant_rules` は持たせない(空必須)・期間は任意(両方 null なら常設)。**おトクタブ専用**(お店/地図タブの判定エンジンは managed のみ対象)
  - `"municipal"`: 自治体施策(店舗データなし)。おトクタブにのみ表示(`detail_url`/`store_search_url` で公式ページへリンク)。**帰属は `payment_variants` で持つ**(下記。施策直下に `payment_method_id` を書かない)
- `payment_variants`(municipal 専用・必須。#89) — 1 キャンペーンを決済サービス別に複製せず、共通項(名称・率・期間・上限・region・共通の notes/memo)を施策直下に 1 度だけ書き、**サービス別に本当に違うものだけ**を `[{ payment_method_id, detail_url, verified_date, point_currency_id?, payment_instruction?, store_search_url?, eligible_notes?, ineligible_notes?, memo? }]` に持つ(1 手段でも 1 要素)。アプリは読み込み時(`resolveCampaigns`)に従来の「1 施策 = 1 決済手段」へ展開し、判定エンジン・UI は展開後しか見ない。展開後の id は **`{施策id}_{payment_method_id}`**(施策 id は `{自治体}_{YYYY_MM}`。例: `yuzawa_2026_07` → `yuzawa_2026_07_paypay`)。variant の `eligible_notes` / `ineligible_notes` / `memo` は共通側の**後ろに連結**、それ以外は variant 側が non-null なら**上書き**(`detail_url` と `verified_date` は variant 必須=整合性テスト。サービスごとに確認日がずれるため)。同一自治体でも率・上限がサービスで違うものは別施策にする(variant は共通項を上書きできない)
  - **サービス既定の文言**(`payment_methods.json` の `qr_payments[].municipal_defaults`): 自治体キャンペーンはサービス側の共通規約で対象決済・対象外機能が決まる(PayPay の「PayPayクレジット以外のクレジットカード併用は対象外」、楽天ペイの「請求書払い・楽天ポイントカード払いは対象外」等)ため、施策側に毎回書かずサービス側に 1 度だけ持つ。展開時に `payment_instruction` は施策側が空なら既定で補い、`ineligible_notes` は施策側の末尾に既定を連結する(同文は重複排除)。**municipal にのみ適用**(promotion はキャンペーンごとに条件が違う。例: PayPay×ネイチャーラボは他社クレジットカードも対象)。施策側には既定と違う上書き・既定に無い差分行だけを書く。既定に入れる文言は**公式ページで全件確認できたものだけ**(2026-08-26 確認: PayPay 自治体 18 件すべて・楽天ペイ 8/9 件+楽天ペイ公式 FAQ「地域限定還元キャンペーン」の共通ルール。d払いの「クーポン利用分は対象外」は 11 件中 3 件にしか無いため既定にせず施策側に残す)
  - **カスタムキャンペーン(アプリ内で自分で登録)の自治体キャンペーンも同じ規則**(#91): 登録の `region`(自治体ピッカーで選択。県全域は name == prefecture)で `municipal`+`external` に変換され、決済手段ごとの差分(詳細 URL・注記・払い出し通貨)は `payment_variants` と同じ「単値は上書き・注記は連結」で合成、`municipal_defaults` も同じ関数(`applyMunicipalDefaults`)で補われる。同じ自治体の同梱施策とはおトクタブで 1 カードに束なる(穴埋め登録が後で収録されても並ぶだけ。自動で消さない)
- `operator` — (任意)施策の運営者。バッジ表示のフォールバック・通知の「PayPay ほか4件」・card_program 束ねタイトルに使う。**省略時は帰属先のカタログ名から導出**する(`card_id`→`cards[].card_name`、`card_brand`→ブランド名、`payment_method_id`→`qr_payments[].name`、`point_program_id`→`point_currencies[].name`。#89)。導出値で困る場合だけ明示する(明示値 == 導出値 は整合性テストで禁止。現状ゼロ件)
- **明示 `null` を書かない**(省略 = null。#89)。`"store_search_url": null` / `"period_end": null` のような行は書かず、値が無いフィールドはキーごと省く(整合性テストが campaigns.json / payment_methods.json / merchants.json の JsonNull を検出する)。「null = 常設」のような意味は省略で表す
- `display_name` — (任意)おトクタブのカード表示用の短いタイトル。**多チェーン promotion と card_program 用**(単一チェーン promotion は merchant 名、自治体系は region タイトルが使われるため不要。municipal には持たせない=整合性テストで検証)。`name` は公式表記の写し(メンテ時の照合キー+判定詳細の説明文)の役割を持つため、「ウエル/スギ」のような**略記の編集判断はこちらに分離**する。未指定時のフォールバック: card_program は `name`(常設プログラムは固有名で呼ぶ) → 単一チェーンは merchant 名 → 複数チェーンは「{先頭チェーン} 他Nチェーン」の自動生成。系列の 1 看板だけが対象の施策(`banner_ids` が 1 看板。例: くすりの福太郎限定クーポン)は**コード側がその看板名を出す**ので `display_name` での手当ては不要(#69)。登録規則(`{メーカー/運営}×{対象の略}` 形式・15 文字目安・率と期間は入れない。card_program は `{発行体名} {プログラム略称}`)は collect-campaigns スキルの mapping.md 参照
- 施策の帰属は **`card_id` / `card_brand` / `payment_method_id` / `point_program_id` のちょうど 1 つ**(残りは null。整合性テストで強制):
  - `card_id` — 紐づくカード(payment_methods.json の `cards[].id`)。card_program / promotion で使う。1 カードに複数施策を紐づけられる
  - `card_brand` — ブランド施策(イシュアー不問。例: 「タッチで Visa 割」、Amex 30% OFF)の対象ブランド。値は payment_methods.json の `card_brands` にあるものを使う。所有カードのうち実ブランド(ユーザー設定。単一ブランド製品は自動確定)が一致するカードが 1 枚でもあれば判定に出る(複数一致でも判定は施策につき 1 件)。バッジは特定カード名でなく**ブランド名**(イシュアー不問のため)。**カタログに無いカードの保有ブランド**は設定画面「国際ブランド」(DataStore の `owned_brands` に保存)で登録でき、仮想カードとしてマッチする。セクションは常時表示し、事前登録しておけば施策開始と同時に判定へ現れる
  - `payment_method_id` — QR 決済(後述)
  - `point_program_id` — **プログラム会員提示型施策**(dポイントカード提示 +3% 等。#39)の帰属先プログラム(payment_methods.json の `point_currencies[].id`)。カード所有でなく「プログラムの会員かどうか」(設定画面「ポイント」の会員チェック → DataStore `point_program_memberships`)に紐づく施策の帰属で、**提示型専用**(`presentation_only: true` 必須=整合性テストで強制。決済型をプログラムに帰属させると判定エンジンが支払い方法を解決できない)。判定は会員登録済みのプログラムの施策だけが「あわせて提示」の並記枠に出る。バッジ・識別色はプログラム名・`point_currencies[].brand_color` から解決する
- `point_currency_id` — (任意)rebate の**払い出し通貨**(payment_methods.json の `point_currencies[].id`。#39)。ポイント倍率(ウエル活等)の適用判定に使う。未指定時の解決: card_id 施策はカードの通貨、QR 施策はサービスの通貨、提示型はプログラム自体を継承する。**card_brand 施策は継承元が無いため明示必須**(未指定なら倍率適用なし。「Visa の施策なら Vポイント」のような固定マッピングは採らない——報酬通貨は施策ごとに異なるため施策データで都度指定する)。discount(即時割引)・lottery には通貨の概念が無い
  - **同じ決済手段でも施策ごとに払い出し通貨が違うときは明示する**(#83)。au PAY は Pontaポイント還元と au PAY残高還元(自治体施策等)が混在し、QR 継承だけでは全部 Ponta になる。多数派(Ponta)を `qr_payments` の既定に置き、**残高の施策側に `point_currency_id: "aupay_balance"` を書く**。放置すると Ponta の交換所倍率が残高還元にも掛かって過大評価になる。収集時は「還元は◯◯」を `memo` に残すだけでなく、通貨マスタに行があるなら `point_currency_id` まで付ける(公式で確認できないうちは付けず、確認待ちであることを `memo` に書く)。**AEON Pay も同じ構造**(#84): 多数派の AEON Pay残高(`aeon_pay_balance`。円建て)を `qr_payments` の既定に置き、**WAON POINT 付与の施策(岐阜市。一次情報確認済み 2026-08-22)だけ施策側に `point_currency_id: "waon_point"` を明示**する。どちらも「多数派を既定・例外を明示」の同じ規則
- `benefit_type` — 特典のタイミング(3 値)。省略時は `"rebate"`:
  - `"rebate"`: ポイント還元(後日ポイント付与)。PayPay の「クーポン」も実態は後日ポイント付与のため rebate に分類
  - `"discount"`: 即時割引。定率(`rate_base`)か定額(`discount_amount`)かはフィールドから導出
  - `"lottery"`: 抽選型(PayPay スクラッチくじ・たぬきの抽選会等)。確定還元ではないため**「最良特典」比較には載せない**(判定詳細・おトクタブに「抽選」として表示のみ)。当選確率・最大額は `memo` の文章で持ち(UI 非表示)、必ず当たる・エントリー不要のような安心情報は `eligible_notes` に書く。`rate_base` / `discount_amount` はどちらも null にする。全加盟店対象の抽選は promotion + external で収録する(#44)
- `store_scope` — 店舗データの有無:
  - `"managed"`: `merchant_rules` で管理。「お店」「地図」タブに表示
  - `"external"`: 店舗データを持たない(自治体施策・全加盟店対象の promotion)。おトクタブにのみ表示
- `period_start` / `period_end` — 開催期間(YYYY-MM-DD)。**両方 null は常設**(card_program と external promotion のみ許容)で、おトクタブの「常設」セクションに出る。managed promotion は両方必須(整合性テスト)
- `payment_method_id` — QR 決済の識別子(payment_methods.json の `qr_payments[].id` と対応)。カード施策は書かない。**municipal は施策直下でなく `payment_variants[].payment_method_id` に書く**(展開後の Campaign では従来どおりこのフィールドに入る)
- `rate_base` — 定率の場合の率(%)。定額の場合は null。常設カード施策では現実的な基準還元率。`rate_rules`(段階制)がある施策では**必ずその最大値**を入れる(整合性テストで強制)
- `rate_rules` — 店舗に紐づかない**条件別の還元率**(段階制)。`[{ "condition": "中小企業・小規模企業の店舗", "rate": 20.0 }, ...]`。managed の `merchant_rules[].rate_override`(店舗キーの上書き)と対になる、条件キーの構造。かなトク(中小20%/大手10%)のような external 施策で使う
  - **登録規則(データ収集時)**: 公式に複数の率がある施策は、全条件と率をここに列挙し、`rate_base` にはその**最大値**を入れる。単一率の施策では書かない(空/省略)。「base に入れる値の判断」を推論に委ねないための無条件規則で、`rate_base == rate_rules の最大値` を CI(整合性テスト)が検証する
  - 表示: これがある施策は数字に「最大」が付き、内訳が判定詳細に列挙される。率の内訳を `conditions` に重複して書かない
- `discount_amount` — 定額の場合の金額(円)。定率の場合は null
  - **`rate_base` と `discount_amount` は排他(どちらか一方が non-null)**
- `per_transaction_cap` — 1 回あたりの付与/割引上限(円相当)。null = 上限なし
- `period_total_cap` — 期間合計の付与/割引上限(円相当)。null = 上限なし
- `cap_note` — 上限の但し書き（数値で表せない補足専用）。`per_transaction_cap` / `period_total_cap` と重複する情報は書かない（UI で数値から自動生成する）
- `min_purchase` — 適用条件の最低購入額(円)。例: 200 →「200 円以上の決済で」
- `min_purchase_scope` — `min_purchase` の集計単位: `"transaction"`(1決済ごと。省略時) | `"period_total"`(期間中の購入合計に掛かる型。PayPay×花王の「期間累計3,000円以上」等)。`period_total` は表示が「期間中の購入合計○円以上で適用(複数回の買い物の合算可)」になる。指定するなら `min_purchase` 必須(整合性テストで強制)
- `product_scope` — 対象商品限定(メーカー×小売×決済連動キャンペーンの「花王商品のみ」等)。`{ "label": "花王商品(メリーズ・キュレル・ソフィーナ・カネボウ除く)" }`。これがある施策は店の全商品に効かないため**「最良特典」比較(bestOption)から分離**される: 一覧・地図のラベルは無条件の特典を優先し、商品限定しか無いチェーンは「○% 還元(対象商品)」と付記、判定詳細では率の数字に「対象商品」を冠し「対象商品限定：{label}」の注意を表示する。判定カード・おトクタブのサマリーには「期間限定」と同列に**「商品限定」バッジ**(warning 系)が付く。なお**メーカー主催のレシート応募型**(決済不問。P&G ツルハ/ウエルシア等)は帰属先が無く「どの支払いを選ぶか」にも影響しないため収録対象外(#43)
- `requires_entry` — 事前エントリーしないと還元されない施策(楽天ペイ×花王等)は true(省略時 false)。判定詳細に「事前エントリーが必要です(詳細ページから)」の警告を出す。エントリー導線は `detail_url` が担う
- `presentation_only` — カード現物の**提示のみ**で受けられる特典(エポス優待「エポスカード提示で10%OFF」等。#80)は true(省略時 false)。支払いは別の決済手段でも対象のため**「最良特典」比較(bestOption)から分離**される: 一覧・地図のラベルは決済で受けられる特典を優先し、提示のみ施策しか無いチェーンは「○% OFF(提示のみ)」と付記、判定詳細には「提示のみ」バッジ(利点の表示なので warning 系でなく secondary 系)+「支払いは別の支払い方法でも対象」の注記が出る。帰属(`card_id`)は「提示にはカード現物の所有が必要」の意味でそのまま維持し(未所有カードの施策が判定に出ない所有フィルタがそのまま正しく機能する)、常設 `card_program` でもカードの通常還元率でなく**施策側の率**を表示する。**提示分と決済分は別施策として分離して起こす**(1 施策に混ぜるとフラグが施策単位で破綻する。mapping.md「提示と決済の分離」)。**ポイントプログラム会員の提示**(dポイントカード提示等。カード所有と無関係)はこのフラグ+`point_program_id` 帰属で表現する(#39)。提示のみ施策(両タイプとも)は判定リストでなく**「あわせて提示」の並記枠**に出る
- `usage_limit` — 利用回数上限。null = 期間中無制限、1 = 1 回限り
- `usage_limit_note` — 利用条件の人間向け補足
- `eligible_wallets` / `ineligible_wallets` — **公式がウォレット単位で還元対象/対象外を言い切っている場合のみ**登録する(値: `"apple_pay"` / `"google_pay"`)。未掲載 = 不明として扱い、網羅性を仮定しない(official_store_list と同じ3状態の設計思想)。抽象フラグにしないのは「Apple Pay は対象・Google Pay は対象外」(MUFG)のような非対称な事実を表現するため:
  - `eligible_wallets` に `google_pay` → 判定詳細に「ウォレット(Google Pay)を開く」起動リンクを表示
  - `ineligible_wallets` に `google_pay` → 判定詳細に「Google Pay での支払いは還元対象外」警告を表示(Android ユーザーが自然に Google Pay でタッチして還元を取り逃すのを防ぐ)。このとき `apple_pay` が eligible なら「(Apple Payは対象)」を付記する(MUFG のような非対称ケース)
  - どちらにも無い → 何も出さない(`payment_instruction` の文章が担う)
  - `apple_pay` エントリは起動リンクには使わず、上記の警告付記にのみ使う。sources と同じ「検証済み事実の記録」として断定できるものだけ書く(プラットフォーム非依存の施策側の事実。Android 固有の消費はコード側に閉じる)
- `eligible_notes` / `ineligible_notes`(施策レベル) — 施策全体に一様に効く「対象/対象外」の言い切り(`[String]`)。municipal ではサービス固有の差分行を `payment_variants[]` 側に、サービス共通規約の定型を `qr_payments[].municipal_defaults` に分け、施策直下には全サービス共通の行だけを書く(展開時に「共通 → variant → 既定」の順で連結される)。判定詳細・おトクタブ詳細で、店舗側(`merchant_rules[].eligible_notes`/`ineligible_notes`)と**レベル横断で連結**して「対象」(通常ロール)/「対象外」(warning 面 1 コンテナに箇条書き)の 2 セクションに表示する。線引きは**「見落とすとユーザーが損するか」**:
  - `ineligible_notes` = 対象外の言い切り + **対象範囲の限定**。限定は対象外リスクが伝わる形で登録する(例: 「対象店舗はキャンペーンツール掲出店」→「キャンペーンツール掲出店以外は対象外」。ただし「一部店舗のみ対象」のように公式表現がそれ自体でリスクを伝えるならそのまま使い、冗長な言い換えをしない)。参加店舗限定の記載がある施策は `store_search_url` で店舗検索へ誘導する
  - `eligible_notes` = 対象の**拡張・追加・明確化のみ**(「〜も含む」「県内在住・在勤を問わず対象」)。見落としても損しない安心情報。既定値どおりの事実(「事前エントリー不要」等)は書かない
  - 店舗ごとに実態・呼び名が異なる情報は、似た文言でも `merchant_rules` 側に店舗別で持つ(集約しすぎない)。**単一チェーン promotion のチャネル限定は施策レベルに書く**(merchant 側だとおトクタブ詳細で見えない)
- `overview_ineligible_notes` — **おトクタブのキャンペーン詳細(施策全体のビュー)だけに出す**「対象外・注記」(#52)。お店タブ・地図の店舗判定カードには連結されない。アプリの収録範囲の注記(「還元率が低いお店は表示対象外」等)のように、施策全体を眺めるときは有用だが特定のお店の判定を見ているユーザーには無関係な情報に使う。公式の対象外の言い切りは従来どおり `ineligible_notes` へ(こちらは両方に出る)
- `memo` — 収集時の内部メモ(`[String]`。**UI 非表示**)。付与時期・集計期間・還元通貨・操作のコツ等、表示フィールドに行き場の無い事実を残し、収集スキルの照合台帳を兼ねる(旧 `conditions`)。対象外/のみ対象の言い切りは置かない(整合性テストで検証。別フィールドに反映済みの総括文だけは「〜に反映済み」注記付きで可)。数値フィールドと重複する文章も書かない
- `may_end_early` — 予算到達次第の早期終了があり得るか(省略時 false)。true なら判定詳細・おトクタブに「早期終了の可能性」注記を出し、「残り○日」が断定に見えないようにする。**自治体系はほぼ全件 true にする**(標準条項のため)
- `recurrence` — 繰り返し日付条件。`{ "days_of_week": ["FRI", "SAT"] }`(毎週金土)または `{ "days_of_month": [20, 30] }`(毎月20・30日)の**どちらか一方**(併用は実在確認できるまで未対応)。`period_start/end`(外枠の開催期間)と併用し、「お店」「地図」の判定は期間内かつ**今日が対象日**のときだけ出す。おトクタブは期間内なら非対象日でも一覧に出し(「期間限定（本日対象外）」セクション)「次の対象日: ○/○」を案内する
- `region` — 自治体施策用。`{ name, prefecture }`。名称は municipalities.json の自治体名と一致させる(おトクタブの地域フィルタが (都道府県名, 自治体名) で突合するため。不一致でも施策は消えず全表示側に倒れる)。グループ所属はマスタ側(municipalities.json の groups)が持つので施策側には書かない
  - **県全域施策**(かながわトクトクキャンペーン等)は `name` に都道府県名をそのまま入れる(例: `{ "name": "神奈川県", "prefecture": "神奈川県" }`)。`name == prefecture` が県全域のマーカー(`Region.isPrefectureWide`)で、地域フィルタ・お知らせバナーは「その県の自治体を1つでも登録していれば表示」、地図タブのピルは「地点がその県内なら表示」になる
  - 政令指定都市はマスタが市単位のため `name` は市名で入れる(例: 横浜市)。行政区名(例: 金沢区)でも登録自体は可能で、地図タブのピルはその区でのみ出るが、マスタに区が無いため登録地域との突合(バナー・地域フィルタ)は効かない(フィルタは防御的全通し=全員に表示)
- `detail_url` — 施策の詳細ページ URL（全タイプ共通。ユーザーに「詳細はこちら」として案内する先）
- `store_search_url` — 対象店舗検索ページ URL(PayPay 等の公式)。municipal は `payment_variants[]` 側(サービスごとに検索ページが違うため)
- `period_start` / `period_end` — 施策期間(ISO 8601 日付)。省略 = 常設(明示 null は書かない)
- `merchant_rules[].rate_override` — その店だけ還元率が異なる場合の上書き値(%)。非 null ならその merchant では `rate_base` の代わりに使う(自治体系の「中小20%/大手10%」、Visa 系の「基礎+特定店で追加」等)。判定と一覧の「最良特典」計算に反映され、おトクタブのサマリーは rate_base と rate_override の最大値を「最大○%」として出す。
  - **card_program の店舗別レート(#52)**: J-POINT パートナーのように 1 施策内で店舗ごとに率が異なる常設プログラムでは、**基準構成(カタログ既定クラス・1pt=既定価値)の絶対%を全ルールに収録**し(1 つでも rate_override を使うなら省略不可)、`rate_base` と発行体カタログの `effective_rate_default` を**その最大値**にする(整合性テストで強制)。判定時にアプリが `(rate_override + クラス加算) × 1pt価値` でユーザー設定(`card_classes`・通貨単位の 1pt 価値。#13)を合成する。rate_override の無い従来の card_program(SMCC/MUFG)は従来どおりユーザー実効率の単一値
  - 施策詳細の対象チェーン列挙は、rate_override の率が 2 種類以上あると**率別グルーピング**(「10%: マクドナルド・ガスト / 1.5%: セブン-イレブン」)で表示される
- `merchant_rules[].eligible_notes` — その店固有の「対象」の言い切り(`[String]`。例: 「ナチュラルローソン・ローソンストア100含む」)。施策レベルの `eligible_notes` と連結して表示される。
- `merchant_rules[].ineligible_notes` — その店固有の「対象外・限定」の言い切り(`[String]`。例: 「スマホレジは対象外」)。「〜のみ対象」型の限定もこちらに言い換えて入れる(線引きは施策レベルと同じ)。**公式が店舗単位で対象/対象外を言い切っていない情報(「例: ○○店」レベルの例示)はここに文章で書くにとどめ、`official_store_list` には入れない**。
- `merchant_rules[].store_list_url` — 「一部店舗のみ対象」のチェーン(サイゼリヤ・KFC等)で、公式の対象店舗一覧へのリンク。判定詳細から開ける。
- `merchant_rules[].official_store_list` — **公式が対象/対象外を店舗名で言い切っているリストがある場合だけ**設定する。これがあるチェーンのみ「この店舗が対象か調べる」別画面に遷移でき、入力店舗名を判定する。判定は3状態:
  - `ineligible_stores` に一致 → **対象外**(⛔)。`eligible_stores` に一致 → **対象**(✅)。どちらにも無い → **要確認**(❓。公式リスト外。一部対象外店舗があるため断定しない)。対象外(ineligible)を優先判定する。
  - 各 store は店舗名の部分文字列(正規化後 `contains` 判定。カナ種・全半角・記号は正規化で吸収されるので「らら/ララ」等は気にしなくてよい)。識別できる範囲で短く書く(「ららぽーと豊洲」等、`店`接尾辞は不要)。
  - 片方のリストだけでも可。ただし「一致しなければ反対」と断定はせず、未掲載は常に要確認になる(網羅性を仮定しない設計)。
  - `list_is_exhaustive: true`(#64) — `eligible_stores` が**対象店舗の網羅リスト**であることの宣言(「対象は次のN店舗のみ」型の特定複数店舗限定キャンペーン。例: コジマ×ビックカメラの au PAY クーポン)。掲載のない店舗は要確認でなく**対象外**と断定し、その施策だけが判定・地図から店舗単位で間引かれる(店のピン自体は他の施策があれば残る)。**公式が「対象店舗はこのリストが全て」と言い切っている場合のみ** true にする。網羅リストのエントリは、部分一致で別の支店を誤って対象と判定しないよう**識別性の高い表記で書く**(非網羅リストの「短く書く」と逆で、`店`接尾辞まで含める。非網羅の誤登録は要確認どまりだが、網羅では誤って「対象」と断定してしまうため)。お店タブの判定カード・おトクタブには「対象のお店のみ」バッジ+注記が付く(#79。チェーン文脈はその merchant_rule、おトクタブの施策単位は**全 merchant_rule が網羅**のときのみ——J-POINTパートナーのように一部チェーンだけ網羅の施策に施策単位で付けると過剰表示になるため)。網羅リストだけのチェーンにも「このお店が対象か調べる」導線を出す(#70。当初 #64 では「対象店しか表示されないため不要」としていたが、掲載のない店が理由なく消えたように見え、対象外の根拠をユーザーが確かめる手段が無かったため方針を変更した)。**地図では、この間引きで全施策が消えた店(および `ineligible_stores` で公式対象外と明示された店)を消さず「薄いピン」で残し**、タップで理由(「対象のお店リストに掲載がない」/「公式に対象外と記載」)と店舗判定画面(公式リストの一致箇所・出典)への導線を出す(#77。「対象店しか出さない」から「対象外は対象外と分かる形で残す」への方針変更。除外が無ければ特典が出ていた店だけが薄いピンになり、所有カードの都合等で元々出ない店は従来どおり描かない。設定→表示で OFF にできる)。
  - `updated_date` + `date_is_official` — 断定の鮮度として判定画面に表示する日付。`date_is_official: true` なら**公式情報自体の更新日**、`false` なら**当方の確認日**(「公式に更新日記載なし」付き)として表示。いずれもこのアプリのデータ更新日 `verified_date`/`updated_at` とは別物。
  - `source_url` — (任意)根拠とした公式ページ。
  - 例(アカチャンホンポ/MUFG): 公式([akachan.jp](https://www.akachan.jp/topics/mufgCPlist/))が◯対象/×対象外を店舗名で明示。両方を `eligible_stores`/`ineligible_stores` に登録し、未掲載店は要確認。公式ページに更新日表記が無いため `date_is_official: false`(確認日表示)。
  - 注意: 網羅的でない例示リストをここに入れると「非一致=対象」を誤って断定してしまう。断定できる完全なリストだけを登録すること。
- `merchant_rules[].ineligible_brands` — この店で優遇対象外になる国際ブランド名のリスト(例: `["Amex"]`。現状使うのは MUFG のデータのみ)。値は payment_methods.json の `card_brands` の `name` を使う(整合性テストで強制)。実ブランドが一致する場合、これらの店は判定・検索・地図から除外される。**ブランド未選択でもそのカードが除外ブランドを取りうる(`brands` に含む)なら除外側に倒す**(不確かな情報で実際より好条件を提示しない方針。実ブランドを選択すると正確になる)。「Visa/MC のみ対象」のような限定も残りブランドの除外として登録する(対象側リストは持たない)。公式ページで店ごとの除外有無を確認済みの施策では、除外が無い店にも明示的に `[]` を書いて「確認済み」を表す。
- `merchant_rules[].banner_ids` / `ineligible_banner_ids` — 看板(業態)単位の対象/対象外(#60)。値は merchants.json のその merchant の `banners[].id`(代表看板は merchant の `id` そのもの。整合性テストで参照を強制)。**両方は指定できない(排他)**。省略時は全看板対象(通常ケース):
  - `banner_ids` — その看板**だけ**が対象(例: グループ内の 1 看板限定の施策)
  - `ineligible_banner_ids` — その看板だけ**対象外**(実例: MUFG ポイントアッププログラムはローソン対象だがローソンスリーエフの公式記載なし → 除外。#62)。該当看板の POI は判定なし = 地図ピン・一覧からも消える
  - 判定詳細では「対象は◯◯のみ」「◯◯は対象外」の注記に自動合成されるので、同じ内容を `ineligible_notes` に重ねて書かない
- `verified_date` — 公式ページで最後に確認した日。**判定画面に必ず表示する。**
- 識別色(brand_color)は campaigns.json には**持たない**。発行体(payment_methods.json の cards / card_brands / qr_payments / point_currencies)側で一元管理し、アプリが帰属(card_id / card_brand / payment_method_id / point_program_id)から解決する(同一発行体の施策間で色がぶれないようにするため。§ payment_methods.json 参照)

### payment_methods.json

- `card_brands` — 登録できる国際ブランドの選択肢(マスタ)。`{ name, color }` で、`name` は campaigns.json の `card_brand` から参照され(整合性テストで強制)、設定画面「国際ブランド」に常時表示する。`color` はブランド施策の識別色
- **識別色(`brand_color` / `color`)** — 発行体ごとに 1 色をここで一元管理し、施策のストライプ/バッジ/地図ピンは帰属先(カード/ブランド/QR)から解決する。**ロゴ画像は商標・著作権の問題があるため使用しない**(公開リポジトリでの再配布になる)。色には権利が及ばないのでブランドカラーで識別する。
  - カード: 三井住友=フレッシュグリーン `#00A94F`(SMFG VI にはトラッドグリーン `#004831` もあるが、視認性と従来表示の継続のため明るい方に統一)、三菱UFJ=MUFGレッド `#E60000`、JCB=ティール `#00707C`、dカード=dレッド `#E60033`(d払いと同色。同一発行体グループとして色を統一し、判定・バッジはラベル文字で区別する。#58)、エポス=エポスレッド `#E60012`(MUFG・dカードと近接する赤系だが公式VIを維持し、区別はラベル文字に委ねる。#59)
  - ブランド: Visa `#1A1F71`、Mastercard `#EB001B`、JCB `#005BAC`、Amex `#016FD0`(各社ロゴの近似色)
  - QR 決済: PayPay `#FF0033`、au PAY `#FF5722`、d払い `#E60033`、楽天ペイ `#BF0000`
  - ポイントプログラム: Vポイント `#0F3F8F`(ロゴの青系)、dポイント `#E60033`(dカード・d払いと同色)、楽天ポイント `#BF0000`(楽天ペイと同色)、Ponta `#F39800`(オレンジ)、PayPayポイント `#FF0033`(PayPay と同色)、J-POINT `#00707C`(JCBオリジナルシリーズと同色。#13)
- `point_currencies` — **ポイント通貨・プログラムのマスタ**(#39)。`{ id, name, brand_color, membership_program, point_multiplier, point_value, value_fixed }`。「Vポイント」「dポイント」のようなエンティティを、通貨価値(倍率・1pt 価値の帰属先)と会員プログラム(提示型施策 `point_program_id` の帰属先)の両面で表す 1 行。カード・QR は `point_currency_id` でこの通貨を「稼ぐ手段」として参照する
  - `membership_program` — カード/アプリ提示の**会員プログラムがあるか**(dポイント・Ponta 等)。true の通貨だけ設定画面「ポイント」に会員チェックを出す(PayPayポイントのように提示の仕組みが無い通貨に意味のないトグルを出さない)。会員かどうかは DataStore(`point_program_memberships`)に分離
  - `point_multiplier` — (任意)ポイント価値の倍率。`{ label, factor, factor_options, group, color, badge_label, applied_note }`。設定画面「ポイント」に label のチェックを出し、ON でこの通貨で払い出される率(通貨を稼ぐカードの実効率+施策側の rebate 率)を `factor` 倍で表示する。`color` はバッジ色(ウエルシアのロゴ色)。Vポイント・WAON POINT(ウエル活×1.5。#84)・Pontaポイント(au PAY マーケットのポイント交換所 ×1.1〜1.5。#83)に設定。**条件付きの増価**(特定の使い方をしたときの価値)であり、恒常的な 1pt 価値(`point_value`)とは別軸。判定カードのバッジは `badge_label` を出し、**倍率が実際に表示中の率へ掛かっているときだけ** `×{factor}` を併記する(`multiplierBadgeLabel`。#83)。倍率 OFF のまま既定値を併記すると、選択肢を持つ通貨(Ponta の ×1.1/×1.5)でユーザーが選んでいない片方の倍率を提示してしまうため。OFF でもバッジ自体は出す(「条件次第で増価する」事実の告知)
    - `factor_options`(任意) — **ユーザーが選べる倍率の選択肢**(#83)。空 = 選択の余地なし(ウエル活のように条件が一意)。2 つ以上あると設定画面に倍率ピッカーが出て、選択値は DataStore(`point_multiplier_factors`: 通貨 id → 倍率)に保存される。`factor` は**未選択時の既定を兼ねるため選択肢の最小値**(保守側)にする(整合性テストで強制)。選択肢外の値・選択肢を持たない通貨の値はマージで無視してカタログの `factor` に落ちる
    - **`factor_options` に自由入力は足さない**。役割分担は「**発行体が定めた離散的な条件**付きの増価は `factor_options`(交換所の 1.1/1.5)、**ユーザー個人のルートによる恒常的な増価**は `point_value`(複数の共通ポイントを経由して 1.3 倍等)」。この 2 つは判定で**積**になる(`currencyValueFactor` = 1pt 価値 × 倍率)ため、両方を持つ通貨は設定画面に掛け合わせの注記を出す(実際に合成されているときは合成後の 1pt 価値も併記。禁止・排他にはせず判断はユーザーに残す)
    - `group`(任意) — **倍率グループ**(#84)。同じ事実の倍率を複数通貨が持つとき(ウエル活 ×1.5 は WAON POINT の価値特性で、Vポイントは等価交換の連鎖で同倍率になる)、同じグループ id を振って ON/OFF を連動させる。マージは「グループの誰かが有効なら全員有効」、設定画面のトグルはグループ全員の通貨 id を DataStore に書く(`multiplierToggleIds`)——倍率改定・切り替えで片方だけ直す事故を防ぐ。**同一グループの倍率定義は完全一致させる**(整合性テストで強制)。交換レートのモデル化(通貨間エッジ)はウエル活 1 件のために過剰なので採らず、重複はグループ+整合性テストで管理する
  - `point_value`(任意) — **1pt 価値の設定定義**(#13 で通貨単位へ移設済み)。`{ label, default, note }`。1pt 価値は全通貨でユーザー設定可能(既定 1.0 円。DataStore の通貨 id → 円のマップ)で、実効率・店舗別レートに乗算される。`point_value` 定義自体は**説明(label/note)が要る通貨のみ**に置く(J-POINT のように使い道で 1pt=0.7〜1円と変動するもの、グローバルポイントのように交換先で 1pt=3〜5円相当と変動するもの(#84。収録率は 1pt=5円相当の交換先基準)。ウエル活のような条件付き増価は上の `point_multiplier` で表現するため、単に「1円固定」の通貨には不要)。収録レートは `default` 基準で書く。カード・QR は `point_currency_id` でこの通貨を稼ぐ手段として参照する(旧: #52 でカード単位に暫定導入、#13 で通貨単位へ一般化)
  - `value_fixed`(任意) — **円建てで 1pt 価値が固定の通貨か**(#83)。au PAY残高のように増価の概念が無く、ユーザーが調整する余地も無いものに立てる。true の通貨は設定画面「ポイント」のリストに出さず(#58「設定の余地があるものだけを置く」と同じ判断)、マージで 1pt=1.0 円に固定する(DataStore に残った値も無視)。判定では払い出し通貨を Ponta 等と区別するためだけに存在するので、`point_multiplier` も `point_value` も同居させない(整合性テストで強制)
- `cards` — カードのカタログ。`{ id, card_name, brand_color, brands, effective_rate_default, point_currency_id }`。`id`(例: `"smcc"`)は campaigns.json の `card_id` と DataStore のカード差分キーから参照される。`point_currency_id` はこのカードが稼ぐ通貨(point_currencies.id。任意)で、rebate 施策の払い出し通貨の既定継承元になる
- `brands` — そのカード製品で**選べるブランドの選択肢**(カタログの事実。例: 三菱UFJカードは Visa/Mastercard/JCB/Amex)。**ユーザーが実際に持っているブランドはカタログに置かず** `CardOverride.brand`(DataStore)で持つ。`brands` が単一なら自動確定、複数なら未選択(空)から設定画面で選ぶ。未選択の間は**好条件側に倒さない**: `card_brand` 施策には一致せず(特典を出さない)、`ineligible_brands` はそのカードが除外ブランドを取りうる限り除外側に倒す。加えて、ブランドが判定に効くカードは有効化時にブランド選択を必須にしている
- `card_classes`(任意) — 同一カード製品内の**グレード差の選択肢**(#52)。`[{ id, label, rate_bonus }]`。`rate_bonus` は店舗別レート(rate_override)と実効率既定値に**加算**する率(%。例: JCB CARD W はパートナー店で S より +1pt/200円 = +0.5)。どのクラスを持っているかはユーザー設定(`CardOverride.cardClass`)で、未選択は**先頭**が既定になるため**保守側(加算の小さい方)を先頭に置く**。JCBオリジナルシリーズ(S / W)に設定
- **設定画面の還元率行は手入力に意味があるカード(単一率プログラム=SMCC/MUFG)だけに出す**。クラスを持つカード(JCB)は率が設定からの導出値 `(effective_rate_default + rate_bonus)` の名目率(判定時にさらに稼ぐ通貨=`point_currency_id` の 1pt 価値で円換算される。#13)、店舗別レートプログラム(`rate_override`)のカード(dカード)は率が店舗ごとの収録値で決まり、どちらも設定の余地が無いため行自体を出さず、保存済みの手入力値もマージで無視する(`allowsManualRate`。#58)
- `qr_payments` — 利用中の QR 決済サービスのカタログ。`{ id, name, brand_color, app_packages, store_search_label, enabled_default, point_currency_id, municipal_defaults }`。設定画面でチェックした QR 決済が判定エンジンのフィルタに使われる。DataStore に差分保存。`point_currency_id` はこのサービスが稼ぐ通貨(任意。rebate の払い出し通貨の既定継承元)。`name` は帰属施策の `operator` 省略時の導出元(#89)。
  - `municipal_defaults`(任意。#89) — `{ payment_instruction, ineligible_notes }`。このサービスの**自治体施策**に共通する既定文言(サービス側の共通規約)。展開時に施策側が空/未記載のときだけ補われる(詳細は campaigns.json の `payment_variants` 参照)。**municipal にのみ適用**され promotion には効かない。文言は公式ページで全件確認できた事実だけを入れる
  - `app_packages` — そのサービスで決済できるアプリのリスト `[{ package, label }]`(優先順)。1 サービスを複数アプリが担える(AEON Pay = 単独アプリ / iAEON の 2 本立て)ため 1:N で持ち、判定詳細の起動リンクは候補全部をボタンで出す。`label` は起動先アプリの実名(サービス名と一致するとは限らない。メルペイ → メルカリ)。**パッケージ名は app の AndroidManifest `<queries>` と対で管理**(宣言が無いと Android 11+ でインストール済みでも起動 Intent が取れず Play ストア送りになる。リモート JSON で追加してもアプリ更新が要る)

### merchants.json — 系列と看板(banners)

1 merchant = 1 **系列**(施策の帰属単位。ホールディングス等)で、傘下で別の名前を掲げる**看板**(UI 表記は「業態」)は `banners` に入れ子で持つ(#60。ドラッグストア系の「系列単位で対象・公式は看板を全列挙」型の施策に対応するため)。

```jsonc
{ "id": "tsuruha", "name": "ツルハドラッグ", "reading": "つるはどらっぐ", "aliases": ["ツルハ"],
  "category": "ドラッグストア", "group_label": "ツルハグループ",
  "banners": [
    { "id": "kyorindo", "name": "杏林堂薬局", "reading": "きょうりんどうやっきょく", "aliases": ["杏林堂"] }
  ] }
```

- merchant 自体の `name`/`reading`/`aliases` は**代表看板**(banner id = merchant の `id`)として扱われる。`campaigns.json` の `merchant_rules` は従来どおり `merchant_id` を書くだけで傘下看板がすべて対象・地図表示になる(看板の列挙は不要)
- **alias と banner の線引き**: alias = **同一看板の略称・表記ゆれ**(マツキヨ・welcia・KFC)/ banner = **街で別の名前を掲げる店**(ハックドラッグ・杏林堂薬局)。看板を alias に入れると絞り込み・表示ラベルが系列名に化ける(#60 の不都合)ので banner にする
- **banners 化は飲食店以外(コンビニ・スーパー・ドラッグストア等)に限り、飲食店の系列は業態ごとに独立 merchant にする**(#62)。飲食系列はジャンルを跨ぐ(すかいらーく=ファミレス+カフェ、松屋フーズ=牛丼+とんかつ+寿司)のにカテゴリは merchant 単位で 1 つしか持てず、施策も業態単位で対象が割れる(例: JCB のガスト・バーミヤンのみ対象)ため、banners にまとめると却って表現できなくなる。すかいらーく・ゼンショー・松屋フーズが該当し、飲食グループ全体の施策は merchant_rules で業態を列挙する
  - 例外 1: **POI 名が基本的に親ブランドで出る併設・変種看板**(モスバーガー&カフェ)は独立 merchant にせず alias に残す
  - 例外 2: `yolp_search: "keyword"` の merchant(上島珈琲店)は banners を持てない(下記)ため、UCC Cafe Plaza は alias のまま
  - 「肉のハナマサ」の「ハナマサ」は略称(同一看板)なので alias(線引きどおり)
- `group_label` — グループ名(例: 「ツルハグループ」)。検索結果の従属表示・地図の絞り込みの束ね見出し・判定詳細の業態行・施策詳細の「対象:」ラベル(全業態対象の系列はグループ名で出す)に使う。未設定時は「{name}グループ」で代用。**その merchant の傘下範囲を正確に表す名前**を入れること(マツキヨココカラのように複数 merchant に跨る持株会社名は使わない — matsukiyo は「マツモトキヨシグループ」、cocokara_fine は「ココカラファイングループ」)
- `banners` は**網羅リストでなくてよい**。未登録の看板は照合されず「出ないだけ」(誤って対象と表示されない安全側)。施策のカバレッジやユーザーの必要が生じた分だけ登録する
- `banners` の**並び順 = 表示優先順**(店舗数・知名度の降順の目安)。お店タブのカテゴリ一覧カードは先頭 2 業態を「◯◯・◯◯ など N業態」と併記し、判定詳細の全列挙・カスタム登録ピッカーもこの順で出す。照合には順序は影響しない
- **既存の系列に看板を追加するときは、その merchant を参照している全施策を見直す**こと。追加した瞬間、グループ対象の既存施策が(公式では対象外でも)新看板に効いてしまう。公式が対象外にしている看板なら同時に該当施策へ `ineligible_banner_ids` を入れる(手順は collect-campaigns スキルの mapping.md)
- 看板名の登録時は照合の制約に注意: **正規化後 3 文字未満(かな・英数)のキーは POI 照合されない**(B&D→「B&Dドラッグストア」で登録)。かな 3 文字は誤爆しやすい(シミズ→「シミズ薬品」、ダルマ→「ダルマ薬局」)。登録の有効性・キーの一意性・banner_ids の参照はユニットテスト(BannerTest)が検証する
- **公式表記だけでなく YOLP の実 POI 名も確認して alias を補う**。実データには短縮形が混在する(「ぱぱす」「くすりセイジョー」等。2026-08 実測)。かな 3〜4 文字の短縮 alias は誤爆(かな後続は自動ブロックされるが**漢字後続は素通り**: 「だるま食堂」)と取りこぼしを天秤にかけて判断し、否定テストを BannerTest に残す
- `yolp_search: "keyword"` の merchant には banners を持たせない(キーワードは merchant 単位で 1 つのため、看板ぶんの取得ができない)。ドラッグストア等の gc 取得では banner を増やしても YOLP コールは増えない

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

`scripts/generate_municipalities.py` が気象庁の予報区データ([area.json](https://www.jma.go.jp/bosai/common/const/area.json)。政府標準利用規約 v2.0 = CC BY 4.0 互換)から生成する。市町村合併・名称変更時はスクリプトを再実行すれば追従できる(実行は手動。旧ファイルとの差分が表示されるので確認してからコミットする)。

```json
{
  "version": 2,
  "source": "気象庁 予報区等(市町村等)一覧 https://...",
  "prefectures": [
    {
      "code": "13",
      "name": "東京都",
      "municipalities": [ { "code": "13101", "name": "千代田区" }, ... ],
      "groups": [
        { "id": "custom-tokyo23", "name": "東京23区", "level": "custom", "municipalities": ["13101", ...] },
        { "id": "jma10-130010", "name": "東京地方", "level": "primary", "municipalities": [...] },
        { "id": "jma15-130011", "name": "23区西部", "level": "detail", "municipalities": [...] }
      ]
    }, ...
  ]
}
```

- `municipalities[].code` — 全国地方公共団体コード 5 桁(チェックディジットなし)。政令指定都市は市名のみ(行政区は含めない。気象庁側が区単位の神戸市・広島市はスクリプトが市へ集約)
- `groups` — 自治体グループ(「まとめて登録」とおトクタブの地域フィルタ用)。`level` は粒度:
  - `primary` — 気象庁の一次細分区域(例: 埼玉県「南部」)
  - `detail` — 市町村等をまとめた地域(例: 「23区西部」「多摩北部」)
  - `custom` — 気象庁区分に無い補完定義(スクリプト内 `EXTRA_GROUPS`)。「東京23区」は気象庁だと 23区西部/東部に分かれるため補完している
  - 自治体 1 つだけのグループと、primary と構成が同一の detail はスクリプトが除去する
- `groups[].municipalities` は自治体コードの配列。並び順(custom → primary → 配下の detail)はそのままピッカーの表示順になる
- リモート更新の対象外(assets 同梱のみ)。「テストデータを使う」ON 時も `data/municipalities.json` を読む(自治体マスタは施策データでなく参照表なので `data-test/` にコピーを置かない。#90)。ユーザーの登録内容は `RegisteredArea`(type=municipality|group + code)として DataStore に保存される

## data-test/ — ショーケースデータ

`data-test/` は実機検証用のテストデータ。実データ(`data/`)では網羅できないパターン(4 象限の特典、UPCOMING、残り 3 日警告、Amex 除外、複数施策競合など)を 1 画面で見渡すための構成。

### 切替方法

設定画面 → 「開発者モード」を ON → 開発者向け設定画面の「テストデータを使う」トグルを ON にすると、アプリのデータ取得先が `data/` から `data-test/` に切り替わる(リモート取得・同梱 assets とも)。`data-test/` のデータは `data/` と同じスキーマ(campaigns.json / merchants.json / payment_methods.json)に従い、カードもテスト専用カタログ(`test_card`)に切り替わる。municipalities.json は切替の対象外で常に `data/` 側を読む(`data-test/` には置かない)。

さらに「同梱データを使う」トグルを ON にすると、リモート取得(GitHub raw)とキャッシュを使わず APK 同梱の assets を直接読む。ローカルで編集した JSON を **push せずに実機検証**できる(反映には `installDebug` での焼き直しが必要)。ON 中はリモート更新を停止し、「テストデータを使う」との組み合わせで assets 内の `data/`⇔`data-test/` を読み分ける。

### 収録パターン一覧

| ID | 検証対象 | 安定性 |
|----|---------|--------|
| `test_card_program` | 常設 rebate+rate(7%)、Amex 除外(`test_super`)、`official_store_list` 3 状態、`store_list_url`、`location_hint`(`test_vending`。`app_package` は Coke ON を指しており、インストール済み端末ではアプリ起動・未インストールでは URL フォールバックを確認できる)、`cap_note`、`eligible_wallets`(ウォレット起動リンク)、**両階層の対象/対象外**(campaign 直下+merchant_rules の `eligible_notes`/`ineligible_notes` 連結。SMCC/MUFG 相当)、`memo`(UI 非表示) | 常時安定 |
| `test_store_rate_program` | **card_program の店舗別レート**(#52。J-POINT パートナー相当): `rate_override` がテストバーガー 10%・テストコンビニ 1.5%。カード `test_card_jcb` の**カードクラス**(テストW=+0.5%)と **1pt価値**(既定1円)を設定画面で変えると判定・詳細の率別グルーピングがスケールする。`requires_entry`、`overview_ineligible_notes`(おトクタブのキャンペーン詳細だけに出る注記。店舗判定カードには出ない) | 常時安定 |
| `test_promotion` | 期間限定 rebate+rate(10%)、`rate_override`(15%)、`may_end_early`、`ineligible_wallets`(Google Pay 対象外警告) | 常時安定 |
| `test_brand_promotion` | `card_brand`(Visa)、即時定率 discount+rate(30% OFF)、`per_transaction_cap`、`ineligible_wallets` 両ウォレット対象外(付記なし警告) | 常時安定 |
| `test_recurrence_weekly` | `recurrence` 曜日型(毎週金土) | 検証日依存 |
| `test_recurrence_monthly` | `recurrence` 日付型(5・20・30 日) | 検証日依存 |
| `test_lottery` | 抽選型(`lottery`)、`memo`(当選確率。非表示)、`ineligible_wallets` Google Pay のみ対象外・Apple Pay 情報なし(付記なし警告) | 常時安定 |
| `test_discount_fixed` | **即時定額** discount+`discount_amount`(300 円引き)、`min_purchase`(500 円)、`usage_limit`(1 回) | 常時安定 |
| `test_exhaustive_store_list` | **網羅リスト**(`list_is_exhaustive`。#64): 掲載店(テスト対象1号店/2号店)以外のドラッグストアではこの施策だけ判定から消える(他施策があれば店は通常ピンで残り、この施策しか無い店は「対象のお店リストに掲載がない」理由付きの薄いピンになる。#77)。網羅リストのみのチェーンでも「このお店が対象か調べる」導線が出る(#70) | 常時安定 |
| `test_rebate_fixed` | **後日定額** rebate+`discount_amount`(500 円還元)、`usage_limit`(3 回)、`usage_limit_note`、`period_total_cap` | 常時安定 |
| `test_product_scope` | **対象商品限定**(`product_scope`。最良比較から分離・「対象商品」冠表示)、`min_purchase_scope: period_total`(期間累計の最低購入額表示)、`requires_entry`(要エントリー警告)、**多チェーン+`display_name`**(カードタイトルの手動略記。`display_name` の無い多チェーンの自動生成「{先頭} 他Nチェーン」は `test_promotion` で確認) | 常時安定 |
| `test_presentation_only` | **提示のみ**(`presentation_only`。#80): 常設 card_program のカード現物提示型優待(エポス優待相当の 10% OFF)。「提示のみ」バッジ+「支払いは別の支払い方法でも対象」注記、判定リストと分かれた**「あわせて提示」並記枠**(#39)、最良比較からの分離(テストスーパーで最大おトク率が 7% のまま)、常設 card_program でもカードの通常率(7%)でなく**施策側の率(10% OFF)**が出ることを確認 | 常時安定 |
| `test_program_presentation` | **プログラム会員提示**(`point_program_id`。#39): dポイント特約店の提示分相当(テストプログラム会員証提示で 3% 還元)。設定→お支払い方法→ポイント→「テストプログラム」の会員チェック ON のときだけ「あわせて提示」並記枠に出る(OFF なら消える)。バッジ・色がプログラム(紫)由来になることを確認 | 常時安定 |
| `test_discount_card_program` | **決済型の即時定率割引** discount+card_program(カラオケ館相当。#59): カード `test_card_epos` の決済条件付き最大30%OFF。`rate_rules`(ルーム30%/フリータイム25%の内訳)+`product_scope`(対象料金限定)+`rate_override`。rebate 施策と混ざったとき「最大30% OFF(対象商品)」表示になり、最良比較から分離される(テストバーガーの最良が変わらない)ことを確認 | 常時安定 |
| `test_upcoming` | **UPCOMING** 状態(常時未開始)、`requires_entry` | 常時安定 |
| `test_ending_soon` | **残り 3 日警告**(検証日に `period_end` を手直し) | **要手直し** |
| `test_municipal` | 自治体施策(`municipal`+`external`)、`region`(北海道札幌市=実在自治体。地域フィルタ・お知らせ表示の実機検証用)、**`payment_variants` 2 手段のショーケース**(#89。展開後 id は `test_municipal_test_paypay` / `test_municipal_test_aupay`): 共通項の継承、variant の `payment_instruction` 上書き(テストPayPay)とサービス既定の継承(テストauPAY。`qr_payments[].municipal_defaults`)、variant 固有の `ineligible_notes` 差分行+既定注記の連結(テストPayPay)、variant 別 `detail_url`/`store_search_url`/`point_currency_id`、施策レベルの `eligible_notes`/`ineligible_notes`(対象/対象外セクション+店舗検索誘導)、`per_transaction_cap`+`period_total_cap`、`may_end_early` | 常時安定 |
| `test_municipal_hiroshima_a` | 同一自治体・率の違う複数決済手段の 1 本目(広島県広島市 × テストPayPay 最大25%。展開後 id `test_municipal_hiroshima_a_test_paypay`)。率・上限がサービスで違うため variant にまとめず**別施策**にする例。自治体グルーピング(1 カードにストライプ 2 色)と `rate_rules`(段階制。中小25%/大手10%→「最大」表示+内訳)の検証用 | 常時安定 |
| `test_municipal_hiroshima_b` | 同一自治体・率の違う複数決済手段の 2 本目(広島県広島市 × テストauPAY 15%。展開後 id `test_municipal_hiroshima_b_test_aupay`) | 常時安定 |

#### 複数施策競合の確認

- **テストコンビニ**: test_card_program(7%)・test_store_rate_program(1.5% テストJCB)・test_promotion(10%)・test_recurrence_weekly(20% 金土)・test_lottery(抽選)・test_rebate_fixed(500 円還元 PayPay)・test_upcoming(25% 未開始)・test_ending_soon(15% 終了間近)
- **テストバーガー**: test_card_program(7%)・test_store_rate_program(10% テストJCB)・test_promotion(15% override)・test_brand_promotion(Visa 30% OFF)・test_recurrence_monthly(12% 5・20・30 日)・test_discount_fixed(300 円引き PayPay)・test_discount_card_program(最大30% OFF 対象料金限定・テストエポス)・test_upcoming(25% 未開始)
- **テストスーパー**: test_card_program(7%・Amex 除外)・test_product_scope(30% 対象商品限定 PayPay)・test_presentation_only(10% OFF 提示のみ)・test_program_presentation(3% 会員提示。会員 ON 時のみ)。無条件の 7% と商品限定 30%・提示のみ 10% OFF が並んでも最大おトク率が 7% のまま(商品限定・提示のみを最良比較に載せない)ことを確認できる
- **テストデパート**: test_presentation_only(10% OFF 提示のみ)・test_program_presentation(3% 会員提示。会員 ON 時のみ)。提示のみ施策しか無いチェーンの一覧ラベル「10% OFF(提示のみ)」と「あわせて提示」並記枠だけの判定画面の確認用(エポス優待×マルイ相当。alias「マルイ」+YOLP キーワード「マルイ」で実店舗ピンの実機確認ができる)
- **ポイント倍率(#39)**: `test_card` は `point_currency_id: test_point`(倍率×1.5 のテストポイント)を稼ぐ。設定→ポイント→「テスト倍率デーで表示」ON で、test_card の実効率(7%→10.5%)と promotion の施策側の率(10%→15% 等)の両方に倍率が掛かり、倍率バッジ+実質還元率注記が出ることを確認できる
- **倍率の選択と円建て通貨(#83)**: `test_aupay` は `point_currency_id: test_exchange`(倍率 ×1.1/×1.5 の選択式)を稼ぎ、同じ `test_aupay` の広島市施策だけが `point_currency_id: test_balance`(`value_fixed` の円建て)を明示している。設定→ポイント→「テスト交換所を使うときの還元率を表示」ON で倍率ピッカー(×1.1/×1.5)が現れ、`test_exchange_rebate`(テストコンビニ・テストバーガーの 10%)の率だけが 11%/15% と動き、広島市施策の 15% は動かないことを確認できる。「テスト残高」は `value_fixed` のため設定リストに現れない。「テスト交換所ポイント」の 1pt 価値も変えると掛け合わせの注記(合成後の 1pt 価値)が出る。判定カードの「還元:」行も 2 施策で「テスト交換所ポイント」「テスト残高」に分かれることを確認できる
- **倍率グループ(#84)**: `test_point` と `test_waon` が同一グループ `test_welcia` のウエル活相当倍率を持つ。設定→ポイントで**どちらの「テスト倍率デーで表示」チェックを入れても両方が連動して ON/OFF される**ことを確認できる(実データの Vポイント/WAON POINT と同型)
- **テストドラッグ**: test_product_scope(30% 対象商品限定 PayPay)のみ。商品限定施策しか無いチェーンの一覧ラベル「30% 還元(対象商品)」の確認用。実在ドラッグストア 6 チェーンを **banners**(テストドラッググループの業態)として持ち、gc グループ `0202001` で地図表示・業態レンズ・グループ束ね UI の実機確認ができる。施策側は `ineligible_banner_ids: ["test_sundrug"]` の使用例を兼ね、**サンドラッグの実店舗ピンだけ地図から消える**(看板スコープの実機確認用。#60)

#### 日付依存パターンの手直し手順

- **`test_ending_soon`**: `period_end` を「検証日の 3 日後」に設定する(例: 7/10 に検証するなら `"2026-07-13"`)。残り 0〜3 日で警告が表示される
- **`test_upcoming` → ACTIVE 遷移**: 常時 UPCOMING(2099 年)。ACTIVE 状態を見たい場合は `period_start` を検証日以前に変更する
- **`test_recurrence_weekly`**: 金・土曜に検証すると「対象日」、他の曜日では「次の対象日: ○/○」が表示される
- **`test_recurrence_monthly`**: 5・20・30 日に検証すると「対象日」、他の日では「次の対象日」が表示される

### 更新ルール

- `data/` のスキーマ変更時は `data-test/` も同時に更新する(同一コミット)
- **`schema_version` を上げるときは Kotlin 側の対応版も同時に上げる**: `MerchantsFile` / `CampaignsFile` / `PaymentMethodsFile` の `SUPPORTED_SCHEMA_VERSION`(app/.../data/Models.kt)。アプリは対応版より新しい `schema_version` のリモートデータをパースせず(`UnsupportedSchemaVersionException`)、キャッシュにも残さない——新スキーマのキーが `ignoreUnknownKeys` で黙って落ちて壊れた判定になるのを防ぐガード。旧アプリは同梱/既存キャッシュで動き続け、更新すれば新データを取り込む。旧アプリの実データテスト(`JudgmentEngineRealDataTest` 等)は data/ を直接読むため、定数の上げ忘れはテストで落ちる
- CI の整合性テスト(`TestDataIntegrityTest`)がパース成功・参照切れ・フィールド排他・記述形の規約(municipal の `payment_variants`・`operator` 省略・明示 null 不在)を検証する
- 日付依存パターンは 2 種類: 常時安定(期間を極端な未来/過去に設定)と検証時要手直し(残り 3 日警告等)。前者を基本とする

## 更新ルール

- 月 1 回、`sources` の公式 URL を確認して `verified_date` を更新する。
- 施策の改定があったら率・店舗リストを直し、`updated_at` を更新する。
- 整合性チェック: merchant_id / banner_ids の参照切れ・照合キー(name/reading/aliases/banners)の衝突がないこと、municipal が `payment_variants` で書かれていること、`operator` が導出値と同じなら書かれていないこと、明示 `null` が無いこと(`./gradlew :app:testDebugUnitTest` の実データテストが検証する)。
- 自治体施策の改定(率・期間・上限)は施策直下を 1 箇所直せば全サービスに効く。サービス別の確認は `payment_variants[].verified_date` を個別に更新する。

### 常設 card_program の収録基準(#58)

- **実効 2% 以上の merchant のみ `merchant_rules` に収録**する。2% 未満の特約店・パートナー店は入れない(見送り理由は施策の `memo` に残す)
- 「実効」は**そのカードの最有利構成**(最上位 `card_classes` の加算込み・1pt=1円換算)での絶対%(決済の通常ポイント込み)。例: J-POINT パートナーのセブン-イレブン(収録値 1.5% = S 基準)は W 換算 +0.5% で 2.0% になるため収録
- 根拠: 常時 1.5% 級+ポイント倍率の高還元カード(カタログ外含む)を常用するユーザーにとって、実効 2% 未満の常設施策は支払い方法の選択を変えないため
- 期間限定(promotion・自治体)の 5% 基準と水準が違うのは、常設は日常の支払い先の恒常的な選択に効く(低率でも累積する)のに対し、期間限定は期間・条件の把握という手間があり 5% 未満では行動を変える価値が薄いため
- **提示分**(ポイントカード提示・カード現物提示が条件の特典)は必ず決済分と分離して起こす。分離した提示分のうち**カード現物提示型**(エポス優待等)は `presentation_only: true` + `card_id` で(#80)、**ポイントプログラム会員提示型**(dポイントカード提示等。カード所有と無関係)は `presentation_only: true` + `point_program_id` で収録する(#39 で解禁)。閾値 2% は提示施策にも同じく適用する(全加盟店共通の通常提示分 1% は閾値未満かつ自明なので収録しない。dポイントの提示は全加盟店 0.5〜1% が標準で、マツキヨ/ココカラも税抜100円=1pt=1% と 2026-08-18 に公式確認済み——現時点で閾値を超える会員提示型の実例は無い。ランク倍率のようなユーザー属性による増率は施策ではないためデータ化しない)。詳細は collect-campaigns の mapping.md「提示と決済の分離」
- 自社経済圏の自明な組み合わせ(イオンカード×イオン系列、ルミネカード×ルミネ等)は率によらず収録しない(「アプリを見ないと分からない組み合わせ」にこそ判定価値があるため)
- この閾値は CI で機械検証しない(収集・メンテ時の運用基準)。実効値はカードクラス・1pt 価値のユーザー設定で変わるため収録生値では判定できず、data-test には率別グルーピング検証用に基準未満の値を意図的に置いている

### 期間限定キャンペーン・クーポンの運用

- **収録基準**: 還元率 5% 以上(自治体施策・カード会社期間限定)。クーポンは割引率 5% 以上 OR 割引額 100 円以上 AND 全員配布 AND 主要チェーン対象。「主要チェーン対象」は特定複数店舗限定(主要チェーンの一部店舗のみ等)でも、公式が対象店舗を言い切る網羅リストがあれば満たす(`official_store_list.list_is_exhaustive: true` 付きで収録。#64/#66)。常設 `card_program` の基準は上の「常設 card_program の収録基準」(実効 2% 以上)を使う。
- **情報源**: PayPay 自治体キャンペーン告知、au PAY/d払い/楽天ペイ公式、カード会社 Web サイト・メール通知。
- **運用フロー**: 月末に翌月分を収集 → campaigns.json に追加 → テスト → main にプッシュ。
- **期限切れデータ**: UI には一切表示しない。データは終了後 30 日保持し、その後手動削除。
