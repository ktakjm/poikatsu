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

## その他の方針

- 還元施策データ(data/*.json)は汎用に保ち、ユーザー固有の前提は data/profile.json に分離する
- 各社ロゴ画像は商標・著作権の問題があるため使用しない。識別はブランドカラー(campaigns.json の brand_color)で行う
- 判定ロジックは domain/ 配下に Android 非依存の純 Kotlin で書き、実データ(data/*.json)を使ったユニットテストを維持する
- ビルド確認は `./gradlew :app:testDebugUnitTest :app:assembleDebug`
