# 依存ライブラリのライセンス調査

最終確認日: 2026-06-12(`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` で実依存を列挙して確認)

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
| com.squareup.okhttp3:okhttp(+ 推移的依存 com.squareup.okio) | リモートデータ取得(M4で追加, 2026-06-12) | Apache-2.0 |

**ランタイム依存はすべて Apache License 2.0。** コピーレフト(GPL系)は一切なし。

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
