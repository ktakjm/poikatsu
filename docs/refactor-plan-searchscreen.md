# SearchScreen.kt リファクタリング計画

**作成: 2026-06-29（Phase C 実装直後）**
**このファイルはリファクタリング完了後に削除する。**

## 背景

Phase C で 4 タブ化（探す/近く/キャンペーン/設定）を実装した結果、`SearchScreen.kt` が 2017 行に膨張し、ファイル名も実態と乖離している。Phase D（判定画面の期間限定対応）・Phase E（設定画面の拡張）で対象ファイルにさらにコードが積まれる前に分割する。

## 実施内容

### 1. SearchScreen.kt を PoikatsuApp.kt にリネーム

ルート Scaffold + タブルーティングが主責務なので、ファイル名を実態に合わせる。

### 2. SettingsScreen.kt を抽出（~250 行）

対象の関数:
- `SettingsScreen`
- `CardSettingItem`
- `BrandDropdown`
- `RateEditDialog`
- `ThemeModeRow`
- `SettingsSectionHeader`
- `CommitRefRow`

Phase E で QR 決済・自治体登録 UI を追加するとさらに膨らむため、先に分離する。

### 3. CampaignScreen.kt を抽出（~220 行）

対象の関数:
- `CampaignPane`
- `CampaignCard`
- `CampaignBenefitLine`
- `groupCampaignsForDisplay`
- `campaignFilterLabel`
- `campaignBenefitText`
- `formatCap`

Phase D/F でカード表示が増えるため、先に分離する。

### 4. JudgmentScreen.kt を抽出（~300 行）

対象の関数:
- `JudgmentDetail`
- `JudgmentCard`
- `JudgmentCardBody`
- `LocationHintNote`
- `StoreCheckScreen`
- `StoreVerdictCard`

Phase D で期間限定バッジ・QR セクション・クーポン表示を追加するとここが大幅に膨らむ。

### 5. 共有ユーティリティの扱い

以下の関数は複数ファイルから使われるため、`PoikatsuApp.kt` に `internal` で残すか、量が増えたら `UiUtils.kt` に分離する:
- `trimRate` — SearchPane, CampaignPane, NearbyPane, JudgmentDetail
- `parseBrandColor` — SearchResultCard, CampaignCard, JudgmentCard, NearbyPane
- `onColorFor` — JudgmentCardBody
- `NoticeRow` — JudgmentCardBody, StoreVerdictCard（将来）
- `CategoryTag` — JudgmentDetail
- `Centered` — CampaignPane（空状態）、NearbyPane（ロード/エラー）
- `PaddedColumn` — PoikatsuApp のルーティング
- `dataStatusLabel` — SearchPane, SettingsScreen

方針: 一旦まとめて `UiHelpers.kt`（internal）に出す。

### 6. 変数名・コメントの修正

- `baseTabsVisible` のコメント「ベースの 2 タブ表示」→「ベースのタブ表示」
- `onCloseNearby()` — 中身が `onSelectTab(SEARCH)` のラッパーなのでコメントを更新
- code-guide.md の該当箇所（設定オーバーレイ、歯車、2 タブ等の記述）を 4 タブ構造に更新

## 分割後の構成（想定）

```
ui/
  PoikatsuApp.kt      (~700 行)  ルート Scaffold + SearchPane + NearbyPane
  SettingsScreen.kt    (~250 行)  設定タブ
  CampaignScreen.kt    (~220 行)  キャンペーンタブ
  JudgmentScreen.kt    (~300 行)  判定詳細 + 店舗判定
  NearbyMap.kt         (~665 行)  地図（既存・変更なし）
  UiHelpers.kt         (~100 行)  共有ユーティリティ
  MainViewModel.kt     (~1005 行) ViewModel（変更なし）
  theme/               (既存・変更なし)
```

## 確認方法

- 機能変更ゼロ（リファクタリングのみ）
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` が通ること
- 可能であれば実機で探す/近く/キャンペーン/設定の各タブが Phase C 実装直後と同じ動作をすること
