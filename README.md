# poikatsu

店舗名やカテゴリから「どの支払い方法が最も得か」を判定する個人用 Android アプリ。

- 対応施策: 三井住友カード「対象のコンビニ・飲食店で最大8%還元」、三菱UFJカード「ポイントアッププログラム(最大20%)」
- 施策データはこのリポジトリの [`data/`](data/) に JSON で持ち、アプリが起動時に raw 配信で取得・キャッシュする(データ更新にアプリの再ビルド不要)
- 全体計画: [PLAN.md](PLAN.md) / データ仕様: [data/README.md](data/README.md) / ライセンス調査: [docs/licenses.md](docs/licenses.md)

## 免責

還元施策の情報は手動収集であり、正確性・最新性は保証しません。各施策の `verified_date`(最終確認日)を確認の上、適用条件は必ず公式サイトで確認してください。

## ビルド

Android Studio で開くか:

```
./gradlew :app:assembleDebug        # APK 作成
./gradlew :app:testDebugUnitTest    # ユニットテスト(データ整合性チェック含む)
```
