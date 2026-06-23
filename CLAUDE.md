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
- 例外: データ(data/*.json)の変更はアプリがGitHub raw(main)を優先取得するため、実機検証の前にプッシュが必要な場合がある。その際は理由を説明して確認を取る
- 実機は手元のAndroid 10 / 12 / 16 の3台(だからminSdk=29)。インストールはAndroid Studioの▶ または `./gradlew installDebug`
- 進捗・保留事項は docs/roadmap.md に反映し、フェーズ完了時は PLAN.md にも記録する
- バージョニング・タグ: **SemVer**。未公開のうちは `0.x`(大きめの変更でもマイナー上げ)、初の一般公開(Play Store 等)で `1.0.0`。機能のまとまり・アーキ転換などの節目で main に**注釈付きタグ**(`git tag -a vX.Y.Z`)を打ち、同時に `app/build.gradle.kts` の `versionName`(設定画面「このアプリ」の表示値=`BuildConfig.VERSION_NAME`)と `versionCode` を合わせる。タグは `git push origin <tag>` で明示 push(自動では push されない)。中間の fix は patch、それ以外は打ちすぎない

## その他の方針

- 還元施策データ(data/*.json)は汎用に保ち、ユーザー固有の前提は data/profile.json に分離する
- 各社ロゴ画像は商標・著作権の問題があるため使用しない。識別はブランドカラー(campaigns.json の brand_color)で行う
- 判定ロジックは domain/ 配下に Android 非依存の純 Kotlin で書き、実データ(data/*.json)を使ったユニットテストを維持する
- ビルド確認は `./gradlew :app:testDebugUnitTest :app:assembleDebug`

## UI・デザイン方針(Material 3 追従)

UI は Jetpack Compose + Material 3。新規画面・コンポーネントを足すとき、既存を直すときも以下に従う(詳細・背景は docs/code-guide.md「6. UI レイヤ」)。

- **配色は dynamic color 中心・最小**: 固定ブランド色は持たない。Android 12+ は dynamic color(壁紙追従)、11 以下は M3 標準ベースライン(`lightColorScheme()`/`darkColorScheme()`)にフォールバック。色は必ず `MaterialTheme.colorScheme` のロールから取る(生 RGB をハードコードしない)。ブランドカラー(brand_color)は発行体識別(地図ピン/カードラベル)の用途に限る。
- **セマンティックカラー**: 致命・不可は error 系、注意・要確認は warning 系(`ui/theme/ExtendedColors.kt`。M3 に warning ロールが無いため独自定義)を使う。`tertiary` 等のブランドアクセントを警告の意味に流用しない。**ロールは用途で使い分ける**(M3 の意味色が単色でなく container 対を持つのと同じ): 白に近い surface に乗せる**文字/アイコン**は `colorScheme.error` / `warningColor()`(単色)、グレーの `surfaceVariant`(カード地)等に**面で見せる注意/警告**は container 対(`errorContainer`+`onErrorContainer` / `warningContainerColor()`+`onWarningContainerColor()`)を `Surface` で使う。色文字を直接グレー地に乗せない(コントラスト不足)。
- **任意の背景色に乗せる文字色**は白/黒固定にせず `onColorFor()`(輝度判定)で読める方を選ぶ。
- **状態・装飾は絵文字でなく Material アイコン**で表し、色は colorScheme から取る。アイコンは `material-icons-core` の範囲で賄う(`-extended` は巨大なので原則追加しない。足りなければ代替アイコン+色で表現)。
- **タッチ領域は最小 48dp**(`IconButton` 等を 48dp 未満に潰さない。見た目を小さくしたいときはアイコン側だけ縮める)。
- **画面上部は `TopAppBar`**(手書き Row ヘッダーにしない)。アプリは単一 `Scaffold`(PoikatsuApp)で `topBar`/`bottomBar`/`snackbarHost` を画面状態に追従させ、`topBar` の分岐順は本文 `when` と必ず一致させる。**例外は地図(近く)モード**: 地図系アプリの定石どおりタイトルバーを持たず、地図をステータスバー裏まで全面表示(full-bleed)し、設定/このエリアを検索/現在地は地図上の浮きコントロールに置く(詳細は code-guide.md 7.1)。
- **トップレベルのモード切替は下部 `NavigationBar`**(「対象チェーン店」/「近く」)。`nearby == null/!= null` を選択中タブとして流用し、判定詳細・店舗判定はその上のオーバーレイ。下部ナビはベースの 2 タブ表示時のみ出し、オーバーレイ・ローディング・エラー時は隠す。専用の `ShortNavigationBar` は M3 Expressive 系のため不採用(標準 `NavigationBar` を `Modifier.height` で詰める)。
- **一時的な失敗は Snackbar**で通知し画面に常駐させない。致命的エラー(表示すべきコンテンツが無い)は全画面表示でよい。
- **リストは行ごとの全幅 Divider で区切らない**。ListItem の余白・グルーピングで分ける(Divider は「まとめる」用途に限る)。
- **edge-to-edge を維持**(`enableEdgeToEdge()` + Scaffold の inset)。inset は基本 Scaffold が `innerPadding` で配り、コンテンツ側はそれを当てる。地図(近く)モードだけは上端 inset を当てず地図をステータスバー裏まで描き、その高さ(`topInset`)を `NearbyPane`/`NearbyMap` に渡して浮きコントロールだけが避ける。ステータスバー/ナビバーのアイコン明暗は `MainActivity` で**システムのダーク設定でなくアプリのテーマ**(`darkTheme`)に追従させる(`WindowCompat` の `isAppearanceLight*Bars`。テーマ上書き時に full-bleed の地図上でアイコンが埋もれないように)。
- **地図ピンのクラスタリング**: 密集ピンは `maps-compose-utils` の `Clustering` で件数バッジ(`inverseSurface` 色の円＋白縁＋件数)にまとめ、ズームインで個別ピン(ブランドカラー)に展開する。クラスタタップは現在ズーム+2(上限 `MAX_CLUSTER_ZOOM=19`)＋プレビュー解除。リスト行タップ時はクラスタ解除のため最低 `SELECTION_MIN_ZOOM=17` まで寄る(密集商業施設では解除できなくても許容)。クラスタリングは `NearbyMap.kt` 内部に完全に閉じ込め、アプリ側は `List<MapMarker>` を渡すだけ。現在地マーカー(青ドット)はクラスタ対象外。
- **M3 Expressive(波形プログレス `*WavyProgressIndicator` / モーフィング `LoadingIndicator`)は alpha 専用**(material3 1.5.0-alpha 系)。安定版重視の方針のため、stable 化するまで採用しない。
