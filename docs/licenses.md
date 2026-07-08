# 依存ライブラリのライセンス調査

最終確認日: 2026-06-19(`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` で実依存を列挙して確認)

## 結論

| 利用形態 | 判定 |
|---|---|
| 個人利用 + GitHub公開リポジトリ | **問題なし**(追加作業も不要) |
| 将来のアプリ一般公開(Play Store等) | **問題なし**(ただしライセンス表記画面の追加が必要 → 下記) |

## APK に含まれる依存(ランタイム)

| グループ | 用途 | ライセンス |
|---|---|---|
| androidx.*(compose, activity, lifecycle, core ほか全AndroidXファミリ) | UI・基盤 | Apache-2.0 |
| org.jetbrains.kotlin(stdlib) | 言語ランタイム | Apache-2.0 |
| org.jetbrains.kotlinx(coroutines, serialization-json) | 非同期・JSON | Apache-2.0 |
| org.jetbrains:annotations | アノテーション | Apache-2.0 |
| com.google.guava(listenablefuture スタブ) | AndroidXの推移的依存 | Apache-2.0 |
| org.jspecify | null安全アノテーション | Apache-2.0 |
| com.squareup.okhttp3:okhttp(+ 推移的依存 com.squareup.okio) | リモートデータ取得・YOLP 呼び出し(M4で追加, 2026-06-12) | Apache-2.0 |
| com.google.maps.android:maps-compose | 近隣検索の地図表示(Compose ラッパー。2026-06-20 追加) | Apache-2.0 |
| com.google.maps.android:maps-compose-utils(+ 推移的な android-maps-utils) | 地図ピンのクラスタリング(密集ピンを件数バッジにまとめる。2026-06-24 追加) | Apache-2.0 |
| com.google.android.gms:play-services-maps(+ 推移的な play-services-* / Google Maps SDK) | 地図描画 SDK 本体(maps-compose の依存) | **プロプライエタリ**(Google APIs Terms of Service。OSS ではない) |
| com.google.android.gms:play-services-location | 現在地取得(Fused Location Provider。2026-07-08 追加) | **プロプライエタリ**(play-services-maps と同じ Google APIs ToS 枠。API キー・課金は不要) |
| androidx.datastore:datastore-preferences | 設定(テーマ・マイカード)の永続化(2026-06-19 追加) | Apache-2.0 |
| com.jakewharton.timber:timber | ログ出力ラッパー(debug ビルドのみ Logcat 出力、release は無出力。2026-06-27 追加) | Apache-2.0 |

**OSS 依存はすべて Apache License 2.0**(コピーレフト/GPL系は一切なし)。**例外は Google Play Services 系(`play-services-maps` / `play-services-location`)で、これらは OSS ではなくプロプライエタリなサービス SDK**(Google APIs ToS 準拠)。location は maps で受け入れ済みの GMS 依存の範囲内であり、新たな規約・課金・キーは発生しない(位置情報取得は課金対象 API ではない)。バイナリ同梱はするが、Apache-2.0 §4 のような NOTICE 義務ではなく Google の規約・帰属表示(SDK が自動描画)に従う。判断の背景は下記「方針転換」と [map-data-stack.md](map-data-stack.md)。

> 旧構成の osmdroid(Apache-2.0・OSM 公式タイル)は 2026-06-20 に Google Maps へ差し替えて**依存から除去**した。OSM 公式タイルの一般配布制限(OSM Tile Usage Policy)も併せて解消。経緯は次節と [map-data-stack.md](map-data-stack.md)。

### 地図描画・店舗データの方針転換(2026-06-20)

OSM(osmdroid 描画 + Overpass データ)の品質(新規店欠落・支店名欠落・地図デザイン)が実用に劣るため、**描画を Google Maps SDK、店舗データを YOLP ローカルサーチAPI へ移行する決定**をした。調査の全文・規約・フェーズ戦略は [map-data-stack.md](map-data-stack.md) に集約。本ファイルにはライセンス/規約面の要点のみ記録する。

- **Google Maps SDK for Android**(`com.google.android.gms:play-services-maps`): OSS ではなく **Google の利用規約に縛られるプロプライエタリな「サービスSDK」**。Apache/MIT 等の OSS ライセンス枠では扱わない。描画は無料無制限だが、**課金アカウント(クレカ)+ API キー + Play Services 依存**が必須。API キーは公開アプリで抽出可能なため**パッケージ名 + 署名 SHA-1 で制限**する。上記の「Play Services 非依存」方針はこの採用で**意図的に転換**する(緩和策: 描画層を `NearbyMap.kt` に隔離し将来 MapLibre 等へ差し替え可能なまま維持)。
- **YOLP ローカルサーチAPI**: Yahoo(LINEヤフー)の **Web API サービス**(同梱ライブラリではなく HTTP API)。順守事項=**①取得データを永続キャッシュしない**(2022改訂 第6条。現状 POI を永続化していないので維持)、**②アプリ下部に「Web Services by Yahoo! JAPAN」等のクレジット表示**(色・サイズを潰さない)、**③1アプリ1日5万リクエストまで無料**、**④無料配布が前提**(データを有料の壁の裏に置く/対価源にすると利用権消滅)。地図描画系 API/SDK は 2020 年に廃止済みのためデータ系のみ利用。
- **API キー/アプリ ID の管理**: 公開リポジトリのため**コミット禁止**。`local.properties` → `BuildConfig` 経由で注入する(BuildConfig は現状未使用のため新規に有効化が必要)。
- **公開時の追加義務**: Yahoo クレジットの常設表示。Google Maps の帰属表示は SDK が自動描画するため別途不要。詳細・収益化時の再確認事項は [map-data-stack.md](map-data-stack.md) §3.2/§7。

## APK に含まれない依存(義務は発生しない)

| 対象 | ライセンス | 備考 |
|---|---|---|
| JUnit 4 | EPL-1.0 | テスト時のみ。配布物に含まれないため義務なし |
| Espresso / androidx.test | Apache-2.0 | 同上 |
| Android Gradle Plugin / Gradle(wrapper jar含む) | Apache-2.0 | ビルドツール。wrapper jar のリポジトリ同梱は標準慣行で問題なし |

## 利用形態ごとの整理

### 個人利用 + GitHub公開(現在)

- Apache-2.0 のライブラリを「使う」だけで、ライブラリのソースを同梱・改変していないため、リポジトリ側に表記義務は発生しない。
- このリポジトリ自体のライセンスは未設定(=デフォルトで全権利留保)。他人の利用を許可したい場合は MIT か Apache-2.0 の LICENSE ファイルを追加する(公開時に判断)。

### アプリ一般公開時(Play Store等)

- Apache-2.0 §4 により、**バイナリ配布時はライセンス文と NOTICE の提供が必要**。アプリでは「オープンソースライセンス」画面を設けるのが標準的な充足方法。
- 実装は手作業でなくツールで行う: **AboutLibraries**(MIT)か **Google Play services の oss-licenses-plugin**(Apache-2.0)が依存を自動収集して表示画面を生成してくれる。公開直前のタスクとして PLAN.md のリリース準備に積む。
- ライセンス以外の公開時の留意点(参考): 還元情報の正確性に関する免責表示、ストア掲載文での他社商標(三井住友カード等)の言及方法。ロゴ不使用・ブランドカラー方針は data/README.md 参照。

## 新しい依存を追加するときのルール

CLAUDE.md に記載(追加時に都度このファイルを更新する)。判断基準:

- **そのまま採用可**: Apache-2.0, MIT, BSD-2/3-Clause, ISC
- **条件確認の上で**: LGPL(動的リンクなら可だがAndroidでは扱いが面倒なので原則避ける), MPL-2.0(ファイル単位コピーレフト)
- **採用しない**: GPL, AGPL(アプリ全体への伝播義務があるため)
- ライセンス不明・独自ライセンスのものは採用前に原文を読む
