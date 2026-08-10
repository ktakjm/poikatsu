# poikatsu プロジェクト規約

店舗ごとに最適な支払い方法を判定する Android アプリ。全体計画は PLAN.md、施策データの仕様は data/README.md を参照。

## 依存ライブラリ追加時のルール(必須)

新しいライブラリ・プラグインを追加するときは、**追加前にライセンスを確認し、docs/licenses.md に追記する**こと。

- 採用可: Apache-2.0 / MIT / BSD / ISC
- 原則避ける: LGPL / MPL-2.0(採用したい場合は条件を docs/licenses.md に明記して判断)
- 採用不可: GPL / AGPL
- 判断基準の詳細と現在の依存一覧: docs/licenses.md

このプロジェクトは GitHub での公開と、将来的なアプリ一般公開(Play Store)を想定している。「個人利用なら大丈夫」ではなく公開基準で判断する。

## 作業フロー

- 実装 → `./gradlew :app:testDebugUnitTest :app:assembleDebug` で確認 → ユーザーが実機検証 → **ユーザーの指示があってからコミット・プッシュ**(勝手にコミットしない)
- データ(data/*.json・data-test/*.json)の変更は、設定→「開発者向け」→「開発者モード」ON→「同梱データを使う」を ON にすれば **push なしで実機検証できる**(JSON 編集のたびに `installDebug` で焼き直す)。リモート取得(GitHub raw main 優先)の経路で検証したい場合のみ実機検証の前にプッシュが必要で、その際は理由を説明して確認を取る
- 実機は手元のAndroid 10 / 12 / 16 の3台(だからminSdk=29)。インストールはAndroid Studioの▶ または `./gradlew installDebug`
- 進捗・保留事項は docs/roadmap.md に反映し、フェーズ完了時は PLAN.md にも記録する
- バージョニング・タグ: **SemVer**。未公開のうちは `0.x`(大きめの変更でもマイナー上げ)、初の一般公開(Play Store 等)で `1.0.0`。機能のまとまり・アーキ転換などの節目で main に**注釈付きタグ**(`git tag -a vX.Y.Z`)を打ち、同時に `app/build.gradle.kts` の `versionName`(設定画面「このアプリ」の表示値=`BuildConfig.VERSION_NAME`)と `versionCode` を合わせる。タグは `git push origin <tag>` で明示 push(自動では push されない)。中間の fix は patch、それ以外は打ちすぎない

## その他の方針

- 還元施策データ(data/*.json)は汎用に保つ。決済手段のマスタは data/payment_methods.json(カタログ)、ユーザー固有の差分(所有・還元率等)は設定画面から DataStore に分離する
- 各社ロゴ画像は商標・著作権の問題があるため使用しない。識別はブランドカラーで行う(payment_methods.json の cards / card_brands / qr_payments 側で一元管理。施策側には持たせない)
- 判定ロジックは domain/ 配下に Android 非依存の純 Kotlin で書き、実データ(data/*.json)を使ったユニットテストを維持する
- ログは **Timber** を使う(`android.util.Log` を直接使わない)。debug ビルドのみ Logcat 出力、release は無出力。domain/ は純 Kotlin を維持するため Timber を使わない。詳細は docs/code-guide.md「9. ログ方針」
- ビルド確認は `./gradlew :app:testDebugUnitTest :app:assembleDebug`

## UI・デザイン方針(Material 3 追従)

UI は Jetpack Compose + Material 3。新規画面・コンポーネントを足すとき、既存を直すときも以下に従う(詳細・背景は docs/code-guide.md「6. UI レイヤ」)。

- **配色は dynamic color 中心・最小**: 固定ブランド色は持たない。Android 12+ は dynamic color(壁紙追従)、11 以下は M3 標準ベースライン(`lightColorScheme()`/`darkColorScheme()`)にフォールバック。色は必ず `MaterialTheme.colorScheme` のロールから取る(生 RGB をハードコードしない)。ブランドカラー(brand_color)は発行体識別(地図ピン/カードラベル)の用途に限る。
- **セマンティックカラー**: 致命・不可は error 系、注意・要確認は warning 系(`ui/theme/ExtendedColors.kt`。M3 に warning ロールが無いため独自定義)を使う。`tertiary` 等のブランドアクセントを警告の意味に流用しない。**ロールは用途で使い分ける**(M3 の意味色が単色でなく container 対を持つのと同じ): 白に近い surface に乗せる**文字/アイコン**は `colorScheme.error` / `warningColor()`(単色)、グレーの `surfaceVariant`(カード地)等に**面で見せる注意/警告**は container 対(`errorContainer`+`onErrorContainer` / `warningContainerColor()`+`onWarningContainerColor()`)を `Surface` で使う。色文字を直接グレー地に乗せない(コントラスト不足)。
- **任意の背景色に乗せる文字色**は白/黒固定にせず `onColorFor()`(輝度判定)で読める方を選ぶ。
- **状態・装飾は絵文字でなく Material アイコン**で表し、色は colorScheme から取る。アイコンは `material-icons-core` の範囲で賄う(`-extended` は巨大なので依存としては追加しない)。core に無い理想形がどうしても必要な場合は、そのアイコンのパスデータだけを `ui/theme/AppIcons.kt` に個別コピーする(google/material-design-icons 由来・Apache-2.0。docs/licenses.md に記録)。それも過剰なら代替アイコン+色で表現。
- **タッチ領域は最小 48dp**(`IconButton` 等を 48dp 未満に潰さない。見た目を小さくしたいときはアイコン側だけ縮める)。
- **画面上部は `TopAppBar`**(手書き Row ヘッダーにしない)。アプリは単一 `Scaffold`(PoikatsuApp)で `topBar`/`bottomBar`/`snackbarHost` を画面状態に追従させ、`topBar` の分岐順は本文 `when` と必ず一致させる。**例外は地図タブ**: 地図系アプリの定石どおりタイトルバーを持たず、地図をステータスバー裏まで全面表示(full-bleed)し、上部に**場所検索バー**、条件付き「このエリアを検索」、自治体施策のお知らせピル、右下に現在地ボタンを浮きコントロールとして置く(詳細は code-guide.md 7.1)。
- **トップレベルのモード切替は下部 `NavigationBar`**(「お店」/「地図」/「おトク」/「設定」の 4 タブ)。`selectedTab`(`AppTab` enum)で選択中タブを管理し、判定詳細・店舗判定はその上のオーバーレイ。下部ナビはベースのタブ表示時のみ出し、オーバーレイ・ローディング・エラー時は隠す。専用の `ShortNavigationBar` は M3 Expressive 系のため不採用(標準 `NavigationBar` を `Modifier.height` で詰める)。**横画面では下部タブを左端の `NavigationRail` に置き換える**(縦方向の占有をなくす M3 の定石。`containerColor = surfaceContainer` を明示して下部タブと同色に揃える。判定は `LocalConfiguration` の向き)。地図タブは横画面ではボトムシートでなく**二ペイン**(地図=中央・全高、お店リスト=右 320dp 固定ペイン)にする(詳細は code-guide.md 7.1)。
- **お店タブの横画面は上部を圧縮し、一覧+詳細を list-detail 二ペインにする**(#54): 横画面(1ペイン)はタイトル直後に検索窓(320dp)+再取得を**左詰め**で同居させた1行ヘッダ(`SearchBarRow`。右寄せにすると直下のチップ行と分断される)にし、カテゴリチップは1行横スクロール、データ状態行は非表示(向き判定。初期説明文は結果が出れば消えるため横でも出す)。**横スクロールのチップ行は余地のある側をフェード**させ続きがあることを示す(`horizontalFadingEdges`。お店・地図共通)。一覧+詳細は material3-adaptive の `ListDetailPaneScaffold` で、**分割判断は directive 指定なしの M3 標準**(WindowSizeClass ベース。幅 Expanded 級=840dp 以上で2ペイン)に委ねる——幅が足りない窓では従来どおり全画面オーバーレイになりそれが仕様。**二ペイン時はグローバル TopAppBar を出さない**(アプリバーは各ペインに属する M3 の multi-pane 定石): 一覧ペイン先頭に「タイトル+再取得」行+全幅検索窓の**2行ヘッダ**を置き、詳細ペインは上端まで全高(1行同居はペイン幅では検索窓が短すぎるため2行に)。判定詳細・店舗判定は右ペインに出し(ペイン内ヘッダで✕/←、店舗判定はペイン内置換)、選択行は `secondaryContainer` でハイライト、Rail/下部ナビは出したまま。`ThreePaneScaffoldNavigator` は使わず UiState から destination を算出(画面遷移は UiState 一元の方針)。Rail=向き・ペイン分割=窓幅で判定基準は直交(詳細は code-guide.md 6.4)。設定への list-detail 展開は今後の課題。
- **おトクタブも同じ list-detail 二ペイン**(#55): 分割判断・ペイン様式(グローバル TopAppBar なし・一覧ペイン先頭に PaneHeader・選択カード `secondaryContainer` ハイライト・Rail/下部ナビ維持)は #54 と共通。施策詳細(CampaignDetail)は**タブ非依存のオーバーレイ**(お店タブのバナー・地図タブのお知らせピルからも開く)のため、**おトクタブ表示中に開いたときだけ右ペイン**に出し(`selectedTab` で出し分け)、他タブ発は従来どおり全画面。FAB(キャンペーン自作登録)は二ペインでも Scaffold 側のまま(詳細ペインは下端 88dp を空けて FAB 回避)、編集画面(CustomCampaignEditorScreen)は全画面フォームで横でも成立するため二ペイン化しない。地図ブリッジ(近くのお店を探す→戻るで復帰)は復帰先がおトクタブなので横画面では右ペインに復元される。**M3 `DatePicker`(カレンダー)は推奨高約 568dp 前提で横画面(高さ 412dp 級)では潰れて読めない**ため、日付ダイアログは横では Input モードで開き、カレンダー切替に備え `verticalScroll` も付ける(向き判定)。
- **地図タブの横画面の詳細はサイドシート**(#57): 地図タブから開く判定詳細・店舗判定・お知らせピル発の施策詳細は、横画面では全画面オーバーレイでなく**地図の上に浮く右端・全高・400dp のサイドシート**(`NearbyDetailSideSheet`。非モーダル・スクリムなし・左側だけ角丸+影)に出す。**開閉で地図・右ペイン(320dp)をリサイズしない**(ペイン幅可変案は GoogleMap の再レイアウトが開閉のたびに走るため不採用)。シート内様式は二ペインの詳細ペインと同じ(`PaneHeader` で✕/←、店舗判定はシート内置換、NavigationRail は表示のまま)。非モーダルのため、シート表示中のピンタップは詳細ごと差し替え(VM の `onPreviewNearby` 側で行う——マーカー onClick は remember 内で古い callback を掴むため UI 側ラッパでは効かない)、お知らせピルは開いていた判定詳細・店舗判定を施策詳細に置き換え、クラスタ/複合ピンのタップはシートを閉じてグループリストを見せる。nearby が無い間(初回ロード/エラー)は全画面オーバーレイにフォールバック。縦画面は無変更(詳細は code-guide.md 6.4)。
- **一時的な失敗は Snackbar**で通知し画面に常駐させない。致命的エラー(表示すべきコンテンツが無い)は全画面表示でよい。
- **リストは行ごとの全幅 Divider で区切らない**。ListItem の余白・グルーピングで分ける(Divider は「まとめる」用途に限る)。
- **edge-to-edge を維持**(`enableEdgeToEdge()` + Scaffold の inset)。inset は基本 Scaffold が `innerPadding` で配り、コンテンツ側はそれを当てる。地図タブだけは上端 inset を当てず地図をステータスバー裏まで描き、その高さ(`topInset`)を `NearbyPane`/`NearbyMap` に渡して浮きコントロールだけが避ける。ステータスバー/ナビバーのアイコン明暗は `MainActivity` で**システムのダーク設定でなくアプリのテーマ**(`darkTheme`)に追従させる(`WindowCompat` の `isAppearanceLight*Bars`。テーマ上書き時に full-bleed の地図上でアイコンが埋もれないように)。
- **地図ピンのクラスタリング**: 密集ピンは `maps-compose-utils` の `Clustering` で件数バッジ(`secondaryContainer` 色の円＋`outline` 縁＋件数)にまとめ、ズームインで個別ピン(ブランドカラー)に展開する。最小クラスタサイズは既定の 4 から **2 に下げ**(`rememberClusterRenderer` 経由で `DefaultClusterRenderer.minClusterSize=2`)、2〜3 個の近接ピンも画面上で重なるなら束ねる。クラスタタップは**常に内包店舗のリストを「この付近に N 件」シートで開き**、分解できるクラスタは同時にズーム+2(上限 `MAX_CLUSTER_ZOOM=19`)する(`onClusterOpen`。ズームだけだと下部シートが旧検索中心基準の全体リストのままで、タップしたクラスタと無関係な店舗が上位に並ぶため 2026-07 にリスト表示を追加)。**YOLP 再検索は自動実行しない**(高ズーム中のタップで極小半径の再検索が走り、取得済みリストを上書きしてしまうため 2026-07 に廃止。再検索は「このエリアを検索」で明示的に行う)。**ズームしても分解できないクラスタ**(広がりがズーム19時点のライブラリ束ね距離≒12m 以下、または上限到達後の再タップ)は、ズームせず複合ピンと同じ「同じ場所に N 件」シートになる(詳細は code-guide.md 7.1)。リスト行タップ時はクラスタ解除のため最低 `SELECTION_MIN_ZOOM=17` まで寄る(密集商業施設では解除できなくても許容)。**同一地点の重なり対策**: 同一ビル 1F/2F 等、座標が近接(10m 以内・連結成分)する店舗はマーカー生成前に `groupByProximity` でグルーピングし、代表マーカー(`MapMarker.groupSize > 1`)として 1 つにまとめる。見た目はライブラリクラスタと同じ件数バッジだが、タップ時はズームではなく BottomSheet にグループ内の店舗リストを表示する(ズームしても分解できないため)。クラスタリングは `NearbyMap.kt` 内部に完全に閉じ込め、アプリ側は `List<MapMarker>` を渡すだけ。現在地(青ドット+向き)は SDK 純正の my-location レイヤー表示(位置・方位は `ManualLocationSource` でアプリが供給)でクラスタとは無関係。YOLP の同一店舗重複登録(座標違い)への対処は code-guide.md 7.1「同一店舗の重複登録と集約」参照。
- **Google 標準 POI ラベルはズーム連動で抑制**: ズーム 18 以上(`POI_SUPPRESS_ZOOM`)では JSON スタイルで `poi` の labels を全カテゴリ off にし(コンビニ・個人クリニック等がアプリの店舗ピンと紛らわしいため)、18 未満はデフォルト表示のまま(Google の重要度ランキングでランドマーク級のみ出る)。JSON スタイルに規模・Tier の軸は無いためズーム連動で代替。`visibility:"on"` を使うルールはダークモード配色を上書きしてラベルが浮くため off 系ルールのみで構成する(詳細は code-guide.md 7.1)。
- **地図の起点コントロール（場所検索）**: 地図タブの起点は 3 種類: (1) GPS 現在地(既定)、(2) 地図パン→「このエリアを検索」、(3) **地名検索**（検索バーに地名入力→`Geocoder` で候補5件→選択で地図移動＋再検索）。起点が地名のとき距離表示はその地点基準（「{起点名}から○○m」、10文字超は省略）、リストの並び順は地図中心基準になる。GPS 起点のときは「現在地から○○m」と表示する。📍（現在地で検索）または検索バーの✕で GPS 起点に戻る。✕はカメラを動かさず距離だけ再計算する（YOLP 再取得なし）。「このエリアを検索」はパンで画面の約2割以上移動(下限 50m)、またはズームアウトで表示範囲が倍以上になったときだけ表示する。起点とレンズ（ジャンル/チェーン絞り込み）は直交し、互いに干渉しない。
- **M3 Expressive(波形プログレス `*WavyProgressIndicator` / モーフィング `LoadingIndicator`)は alpha 専用**(material3 1.5.0-alpha 系)。安定版重視の方針のため、stable 化するまで採用しない。
