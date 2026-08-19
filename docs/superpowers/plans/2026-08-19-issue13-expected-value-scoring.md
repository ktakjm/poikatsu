# issue #13 期待価値スコア比較 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 判定エンジンを「還元率比較」から「円換算の実質還元率(期待価値スコア)比較」へ拡張する — 1pt 価値の通貨単位化・期間限定ポイントの失効通知・提示スタック合算・rebate vs coupon 損益分岐。

**Architecture:** 案B(スコア層新設)。`domain/ExpectedValueScoring.kt` に円価値換算の純関数を集約し、`JudgmentEngine` は名目率の解決に専念、判定(`CampaignJudgment.effectiveRate`)は実質%になる。ウエル活倍率・1pt 価値の適用はマージ層(`UserDataMerge`)からスコア層へ一本化し、`usesCardRate` 型の二重適用の罠を構造的に除去する。

**Tech Stack:** Kotlin(domain=純 Kotlin、java.time)、Jetpack Compose + Material 3、DataStore Preferences、kotlinx.serialization、JUnit4。

**Spec:** `docs/superpowers/specs/2026-08-19-issue13-expected-value-scoring-design.md`

## Global Constraints

- ビルド確認は毎タスク `./gradlew :app:testDebugUnitTest :app:assembleDebug`(CLAUDE.md)
- **コミットはユーザーの指示があってから**(CLAUDE.md)。本計画の各タスクは「テスト・ビルドが通る」で完了とし、コミットは各 Stage 末のチェックポイントでユーザーに確認する。テンプレートの「Step: Commit」は本プロジェクトでは適用しない
- domain/ は Android 非依存の純 Kotlin を維持(Timber も使わない)。「今日」は引数で渡す
- UI 文言: 「キャンペーン」(施策✕)・「お店」(店舗✕)・「お支払い方法」。文末疑問符は全角？。docs/コードコメントは「施策」のまま
- UI 色は `MaterialTheme.colorScheme` のロールから。注意・警告は `warningColor()` / container 対(ExtendedColors.kt)。絵文字でなく Material アイコン(material-icons-core の範囲)
- 面上の `ListItem` は `transparentListItemColors()`(UiHelpers)
- M3 DatePicker は横画面では `DisplayMode.Input` で開き `verticalScroll` を付ける(既存 `EditorDatePickerDialog` と同じ)
- データ(data/*.json)は汎用に保つ。ユーザー固有値は DataStore
- 新規依存ライブラリは追加しない(追加する場合は docs/licenses.md 先行)
- スキーマ変更時は data/README.md と docs/code-guide.md §3 の ER 図を更新する
- テストは「Kotlin フィクスチャ」「実データ(`実データ_` プレフィックス)」「data-test ショーケース」の 3 層(code-guide §8)

### スコアの定義(全タスク共通の式)

```
通貨価値係数 = 1pt価値(円。ユーザー設定、既定 1.0) × (倍率ON ? point_multiplier.factor : 1.0)
実質%(スコア) = 名目還元率 × 通貨価値係数
```

- 通貨が特定できない判定(payoutCurrency が null。例: 三菱UFJカード)は係数 1
- discount(定額)・lottery はスコア対象外(現行の除外を維持)
- 既定値(1pt=1円・倍率OFF・残高未入力)では全判定の数値が現行実装と一致すること(Stage 2 の不変条件)

---

## Stage 1: 価値モデルの通貨単位化(スキーマ v10 + DataStore + 設定 UI)

### Task 1: PointCurrency へ 1pt 価値を移設(モデル+マージ)

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/Models.kt`(PointCurrency / PaymentCard / PointValueConfig の doc)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/UserDataMerge.kt`
- Test: `app/src/test/java/com/ktakjm/poikatsu/JudgmentEngineTest.kt`(`PointCurrencyMergeTest` / `CardClassMergeTest`)

**Interfaces:**
- Produces: `PointCurrency.pointValueConfig: PointValueConfig?`(JSON `point_value`)、`PointCurrency.valueYen: Double`(@Transient、既定 1.0)、`mergeUserData(..., pointCurrencyValues: Map<String, Double> = emptyMap())`
- 注意: `PaymentCard.pointValueConfig` と `CardOverride.pointValue` はこのタスクでは**まだ消さない**(Task 3 の移行が先)。マージの 1pt 価値の解決元だけ通貨側を優先する

- [ ] **Step 1: 失敗するテストを書く**(`PointCurrencyMergeTest` に追加)

```kotlin
@Test
fun `通貨の1pt価値がユーザー設定から実効率に掛かる`() {
    // valueYen=0.5 を設定した通貨を稼ぐカードは実効率が半分になる
    val result = mergeUserData(
        PoikatsuData(
            merchants = emptyList(),
            campaigns = emptyList(),
            cards = listOf(smccLike),
            pointCurrencies = listOf(vpoint),
            updatedAt = "",
        ),
        cardOverrides = emptyMap(),
        ownedBrands = emptySet(),
        customCards = emptyList(),
        customCampaigns = emptyList(),
        enabledPointMultipliers = emptySet(),
        pointCurrencyValues = mapOf("vp" to 0.5),
    )
    assertEquals(3.5, result.engineData.cards.single().effectiveRateDefault!!, 1e-9)
    assertEquals(0.5, result.engineData.pointCurrencies.single().valueYen, 1e-9)
}

@Test
fun `通貨の1pt価値の既定はカタログのdefaultで未設定なら1円`() {
    val jpointLike = PointCurrency(
        id = "jp",
        name = "テストJポイント",
        pointValueConfig = PointValueConfig(label = "Jポイントの価値", default = 1.0, note = ""),
    )
    val card = smccLike.copy(pointCurrencyId = "jp")
    val result = mergeUserData(
        PoikatsuData(merchants = emptyList(), campaigns = emptyList(), cards = listOf(card), pointCurrencies = listOf(jpointLike), updatedAt = ""),
        cardOverrides = emptyMap(), ownedBrands = emptySet(), customCards = emptyList(),
        customCampaigns = emptyList(), enabledPointMultipliers = emptySet(),
    )
    assertEquals(1.0, result.engineData.pointCurrencies.single().valueYen, 0.0)
    assertEquals(7.0, result.engineData.cards.single().effectiveRateDefault!!, 0.0)
}
```

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*PointCurrencyMergeTest*'`
Expected: FAIL(`pointValueConfig` / `valueYen` / `pointCurrencyValues` が未定義のコンパイルエラー)

- [ ] **Step 3: 実装**

`Models.kt` — `PointCurrency` に追加(`PointValueConfig` の doc コメントから「カード単位の暫定表現」の記述を「通貨単位の 1pt 価値定義(#13 で移設)」へ更新):

```kotlin
    /** 1pt 価値の設定定義(任意)。label/note は J-POINT のように説明が要る通貨だけ持つ。#13 で通貨単位へ移設 */
    @SerialName("point_value") val pointValueConfig: PointValueConfig? = null,
    /** 実行時: ユーザー設定の 1pt 価値(円)。マージで設定し JSON には現れない。既定 1.0 円 */
    @Transient val valueYen: Double = 1.0,
```

`UserDataMerge.kt` — シグネチャに `pointCurrencyValues: Map<String, Double> = emptyMap()` を追加し、通貨マージと 1pt 価値の解決を変更:

```kotlin
    val mergedCurrencies = base.pointCurrencies.map { currency ->
        currency.copy(
            multiplierEnabled = currency.pointMultiplier != null && currency.id in enabledPointMultipliers,
            valueYen = pointCurrencyValues[currency.id] ?: currency.pointValueConfig?.default ?: 1.0,
        )
    }
    val mergedCurrencyById = mergedCurrencies.associateBy { it.id }
```

カードの 1pt 価値解決(既存 70 行目付近)を「通貨側を優先、旧カード単位の値はフォールバック」へ:

```kotlin
        // 1pt 価値は通貨単位(#13)。旧カード単位の上書き(CardOverride.pointValue)は
        // Task 3 の移行が済むまでフォールバックとして残す
        val pointValue = card.pointCurrencyId?.let { id -> mergedCurrencyById[id]?.valueYen }
            ?: ov?.pointValue ?: card.pointValueConfig?.default ?: 1.0
```

- [ ] **Step 4: テスト実行**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全件 PASS(既存の `CardClassMergeTest` は jcb がまだカード単位 point_value のため挙動不変で通る)

### Task 2: data/data-test を schema_version 10 へ(j_point 新設・point_value 移設)+ ドキュメント

**Files:**
- Modify: `data/payment_methods.json`
- Modify: `data-test/payment_methods.json`
- Modify: `data/README.md`(point_currencies / point_value の節)
- Modify: `docs/code-guide.md`(§3 ER 図: point_currencies に point_value、cards から point_value 削除)
- Test: `app/src/test/java/com/ktakjm/poikatsu/JudgmentEngineTest.kt`(`JudgmentEngineRealDataTest` / `TestDataIntegrityTest`)

**Interfaces:**
- Produces: 通貨 `j_point`(cards.jcb_original が `point_currency_id: "j_point"` で参照)、data-test の通貨 `test_jpoint`(test_card_jcb が参照)。カード側 `point_value` は JSON から削除

- [ ] **Step 1: 失敗するテストを書く**

`JudgmentEngineRealDataTest` に追加:

```kotlin
@Test
fun `実データ_1pt価値は通貨単位で定義されカード側にpoint_valueは無い`() {
    val jcb = paymentMethods.cards.first { it.id == "jcb_original" }
    assertEquals("j_point", jcb.pointCurrencyId)
    assertNull(jcb.pointValueConfig)
    val jpoint = paymentMethods.pointCurrencies.first { it.id == "j_point" }
    assertNotNull(jpoint.pointValueConfig)
    assertEquals(1.0, jpoint.pointValueConfig!!.default, 0.0)
    // 全カードでカード単位の point_value が廃止されていること
    paymentMethods.cards.forEach { assertNull("${it.id} にカード単位の point_value が残っている", it.pointValueConfig) }
}
```

`TestDataIntegrityTest` に同型のテスト(`test_card_jcb` → `test_jpoint`)を追加。

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*RealDataTest*' --tests '*TestDataIntegrityTest*'`
Expected: FAIL(`j_point` 通貨が存在しない)

- [ ] **Step 3: JSON を編集**

`data/payment_methods.json`: `schema_version` を 10 に。`point_currencies` に追加:

```json
    {
      "id": "j_point",
      "name": "J-POINT",
      "brand_color": "#00707C",
      "membership_program": false,
      "point_value": { "label": "J-POINTの価値", "default": 1.0, "note": "使い道により1pt=0.7〜1円。ポイントの使い道に合わせて調整できます" }
    }
```

`jcb_original` に `"point_currency_id": "j_point"` を追加し、カード側の `"point_value": {...}` 行を削除。`updated_at` を更新。

`data-test/payment_methods.json`: 同様に `schema_version` 10、通貨 `test_jpoint`(name "テストJポイント"、point_value は実データと同型・note "検証用")を追加、`test_card_jcb` に `point_currency_id: "test_jpoint"` を付けてカード側 point_value を削除。

- [ ] **Step 4: ドキュメント更新**

- `data/README.md`: `point_value` の説明をカードの節から point_currencies の節へ移し、「#13 で通貨単位へ移設済み。1pt 価値は全通貨でユーザー設定可能(既定 1.0 円)、point_value 定義は説明(label/note)が要る通貨のみ」へ書き換える
- `docs/code-guide.md` §3 ER 図: `point_currencies` に `point_value` を追加し、`cards` から `point_value` を削除

- [ ] **Step 5: テスト実行**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全件 PASS。**注意**: 既存の実データテストにカード側 point_value を参照するものがあれば(grep `pointValueConfig`)、通貨側参照へ書き換える

### Task 3: DataStore(1pt 価値・残高)+ 移行 + バックアップ v3

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/SettingsRepository.kt`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/SettingsBackup.kt`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt`(rebuild で merge へ受け渡し+一度きり移行)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/notification/CampaignNotificationWorker.kt`(mergeUserData 呼び出しに引数追加)
- Test: `app/src/test/java/com/ktakjm/poikatsu/SettingsBackupTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class PointBalance(val balancePt: Int, val expiryDate: String)`(SettingsRepository.kt。expiryDate は YYYY-MM-DD)
  - `AppSettings.pointCurrencyValues: Map<String, Double>` / `AppSettings.pointBalances: Map<String, PointBalance>`
  - `SettingsRepository.setPointCurrencyValue(currencyId: String, value: Double?)`(null で既定に戻す)
  - `SettingsRepository.setPointBalance(currencyId: String, balance: PointBalance?)`(null で削除)
  - `SettingsRepository.migrateCardPointValues(cardToCurrency: Map<String, String>)`
  - `SETTINGS_BACKUP_SCHEMA_VERSION = 3`、`SettingsBackup.pointCurrencyValues` / `pointBalances`
- Consumes: Task 1 の `mergeUserData(pointCurrencyValues=...)`

- [ ] **Step 1: 失敗するテストを書く**(`SettingsBackupTest` に追加)

```kotlin
@Test
fun `1pt価値と残高がバックアップに含まれ復元できる`() {
    val settings = AppSettings(
        pointCurrencyValues = mapOf("vpoint" to 1.5, "j_point" to 0.7),
        pointBalances = mapOf("rakuten_point" to PointBalance(balancePt = 500, expiryDate = "2026-09-01")),
    )
    val backup = settings.toBackup(exportedAt = "2026-08-19T00:00:00", appVersion = "0.5.0")
    assertEquals(3, backup.schemaVersion)
    val restored = decodeSettingsBackup(encodeSettingsBackup(backup))!!.toSettings()
    assertEquals(1.5, restored.pointCurrencyValues["vpoint"]!!, 0.0)
    assertEquals(500, restored.pointBalances["rakuten_point"]!!.balancePt)
    assertEquals("2026-09-01", restored.pointBalances["rakuten_point"]!!.expiryDate)
}

@Test
fun `v2バックアップは読めて新フィールドは空になる`() {
    val v2Json = """{"schemaVersion": 2, "cardOverrides": {"jcb_original": {"pointValue": 0.8}}}"""
    val restored = decodeSettingsBackup(v2Json)!!.toSettings()
    assertTrue(restored.pointCurrencyValues.isEmpty())
    // 旧 CardOverride.pointValue は残したまま復元される(次回 rebuild 時の移行で通貨側へ移る)
    assertEquals(0.8, restored.cardOverrides["jcb_original"]!!.pointValue!!, 0.0)
}
```

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsBackupTest*'`
Expected: FAIL(コンパイルエラー)

- [ ] **Step 3: 実装**

`SettingsRepository.kt`:

```kotlin
/**
 * 期間限定ポイントの残高と失効日(通貨ごとに1件=直近失効分。#13)。
 * 公式 API が無いため手入力。失効したら次の塊を入れ直す運用。
 */
@Serializable
data class PointBalance(
    /** 残高(pt) */
    val balancePt: Int,
    /** 失効日(YYYY-MM-DD)。この日までは利用可能、翌日以降は失効済み扱い */
    val expiryDate: String,
)
```

- `Keys` に `POINT_CURRENCY_VALUES = stringPreferencesKey("point_currency_values")` / `POINT_BALANCES = stringPreferencesKey("point_balances")` を追加
- `AppSettings` に `pointCurrencyValues: Map<String, Double> = emptyMap()` / `pointBalances: Map<String, PointBalance> = emptyMap()` を追加、`settings` Flow と `importSettings` に読み書きを追加(decode ヘルパは `decodeOverrides` と同型の `decodeCurrencyValues` / `decodePointBalances`)
- setter:

```kotlin
    /** 1pt の価値(円)。通貨単位(#13)。null で既定(カタログ default または 1.0 円)に戻す */
    suspend fun setPointCurrencyValue(currencyId: String, value: Double?) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeCurrencyValues().toMutableMap()
            if (value == null) current.remove(currencyId) else current[currencyId] = value
            prefs[Keys.POINT_CURRENCY_VALUES] = json.encodeToString(current)
        }
    }

    /** 期間限定ポイントの残高・失効日。null で削除(#13) */
    suspend fun setPointBalance(currencyId: String, balance: PointBalance?) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodePointBalances().toMutableMap()
            if (balance == null) current.remove(currencyId) else current[currencyId] = balance
            prefs[Keys.POINT_BALANCES] = json.encodeToString(current)
        }
    }

    /**
     * 旧カード単位の 1pt 価値(CardOverride.pointValue)を通貨単位へ移行する(#13)。
     * カード→通貨の対応はカタログ由来(呼び出し側=VM がデータロード後に渡す)。
     * 既に通貨側に値がある場合は通貨側を優先し、移行後は CardOverride 側を必ず消す
     * (バックアップ v2 の復元でも再移行できるよう、この関数は何度呼んでも安全)。
     */
    suspend fun migrateCardPointValues(cardToCurrency: Map<String, String>) {
        context.settingsDataStore.edit { prefs ->
            val overrides = prefs.decodeOverrides()
            val pending = overrides.filterValues { it.pointValue != null }
            if (pending.isEmpty()) return@edit
            val values = prefs.decodeCurrencyValues().toMutableMap()
            pending.forEach { (cardId, ov) ->
                val currencyId = cardToCurrency[cardId] ?: return@forEach
                values.putIfAbsent(currencyId, ov.pointValue!!)
            }
            val cleaned = overrides.mapValues { (_, ov) -> ov.copy(pointValue = null) }
            prefs[Keys.POINT_CURRENCY_VALUES] = json.encodeToString(values)
            prefs[Keys.CARD_OVERRIDES] = json.encodeToString(cleaned)
        }
    }
```

- `CardOverride.pointValue` の doc を「#13 で通貨単位(pointCurrencyValues)へ移行済み。読み込みは移行のために残す(書き込みは移行処理のみ)」へ更新し、`setPointValue(cardId, ...)` を削除(呼び出し元は Task 4 で差し替え)

`SettingsBackup.kt`:
- `SETTINGS_BACKUP_SCHEMA_VERSION = 3`、履歴コメントに「3 = 1pt 価値の通貨単位化(pointCurrencyValues)+期間限定ポイント残高(pointBalances)を追加、CardOverride.pointValue は移行用の残置(#13)」を追記
- `SettingsBackup` / `toBackup` / `toSettings` に両フィールドを追加(`toSettings` では `pointCurrencyValues.filterValues { it >= 0.0 }`・`pointBalances.filterValues { it.balancePt >= 0 }` で範囲外を落とす)

`MainViewModel.kt` の `rebuild()`:

```kotlin
        val merged = mergeUserData(
            // ...既存引数...
            enabledPointMultipliers = settings.enabledPointMultipliers,
            pointCurrencyValues = settings.pointCurrencyValues,
        )
```

データロード直後(applyData)に一度きり移行を発火(suspend なので viewModelScope):

```kotlin
        // 旧カード単位の 1pt 価値を通貨単位へ移行(#13)。対応表はカタログから引く
        val cardToCurrency = loaded.data.cards
            .mapNotNull { c -> c.pointCurrencyId?.let { c.id to it } }.toMap()
        viewModelScope.launch { settingsRepo.migrateCardPointValues(cardToCurrency) }
```

`CampaignNotificationWorker.kt` の `mergeUserData` 呼び出しにも `pointCurrencyValues = settings.pointCurrencyValues` を追加。

- [ ] **Step 4: テスト+ビルド**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS(`setPointValue` の呼び出し元が VM/設定 UI に残っていればこのタスク内で `setPointCurrencyValue` へ暫定差し替えして通す)

### Task 4: 設定 UI — 通貨行に「1pt の価値」ピッカー(プリセット付き)

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/PaymentMethodsSettings.kt`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt`(`PointCurrencySetting` 拡張 / `CardSetting` から pointValue 系を削除 / コールバック配線)
- Test: 目視(Compose テスト基盤なし)+ 既存単体テストの回帰

**Interfaces:**
- Produces: `PointCurrencySetting` に `valueYen: Double` / `pointValueConfig: PointValueConfig?` / `valueIsDefault: Boolean` を追加。VM に `fun setPointCurrencyValue(currencyId: String, value: Double?)`。`CardSetting.pointValueConfig` / `pointValue` は削除
- UI プリセット定数(PaymentMethodsSettings.kt 内): `使わない(0円)` / `等価(1円)` / カスタム入力

- [ ] **Step 1: VM の組み立てを拡張**

`MainViewModel` の `PointCurrencySetting` 組み立て(既存 1007-1025 行付近)に追加:

```kotlin
                PointCurrencySetting(
                    // ...既存...
                    valueYen = currency.id.let { id ->
                        settings.pointCurrencyValues[id] ?: currency.pointValueConfig?.default ?: 1.0
                    },
                    pointValueConfig = currency.pointValueConfig,
                    valueIsDefault = currency.id !in settings.pointCurrencyValues,
                )
```

`CardSetting` から `pointValueConfig` / `pointValue` を削除し、組み立て・カード行 UI(608-657 行の 1pt 価値行と `PointValueEditDialog` 呼び出し)を削除。VM の `setPointValue(cardId, ...)` を `setPointCurrencyValue(currencyId, value)`(`settingsRepo.setPointCurrencyValue` へ委譲)に置き換える。

- [ ] **Step 2: 通貨行に「1pt の価値」行とピッカーを追加**

「ポイント」セクションの各通貨(219 行〜の forEach 内、倍率チェックの後)に:

```kotlin
                // 1pt の価値(#13: 全通貨でユーザー設定可能。既定 1.0 円)
                ListItem(
                    headlineContent = { Text(currency.pointValueConfig?.label ?: "1ptの価値") },
                    supportingContent = currency.pointValueConfig?.note
                        ?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    trailingContent = {
                        Text("1pt=${trimRate(currency.valueYen)}円", style = MaterialTheme.typography.titleMedium)
                    },
                    colors = transparentListItemColors(),
                    modifier = Modifier.padding(start = 24.dp)
                        .clickable { editingValueCurrency = currency },
                )
```

ピッカーは既存 `PointValueEditDialog` を通貨向けに一般化(引数を `PointCurrencySetting` に、プリセットボタンを追加):

```kotlin
/** 1pt 価値のピッカー(#13)。プリセット(使わない=0円/等価=1円)+カスタム入力。「既定に戻す」で上書き解除 */
@Composable
private fun PointValuePickerDialog(
    currency: MainViewModel.PointCurrencySetting,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(trimRate(currency.valueYen)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(currency.pointValueConfig?.label ?: "${currency.name}の1ptの価値") },
        text = {
            Column {
                Text(
                    buildString {
                        append("1ポイントをいくらの価値として計算するか選んでください。判定の実質還元率に反映されます。")
                        currency.pointValueConfig?.note?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { text = "0" }, label = { Text("使わない(0円)") })
                    AssistChip(onClick = { text = "1" }, label = { Text("等価(1円)") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    prefix = { Text("1pt=") },
                    suffix = { Text("円") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toDoubleOrNull()?.takeIf { it >= 0.0 }) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("既定に戻す") } },
    )
}
```

旧カード用 `PointValueEditDialog` は削除。セクションの説明文(214 行)に「1ptの価値を設定すると、判定が実質還元率で比較されます。」を追記。

- [ ] **Step 3: テスト+ビルド**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS

### Stage 1 チェックポイント(ユーザー確認)

- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug` 全 PASS を確認
- [ ] ユーザーに実機検証を依頼: (1) 設定→ポイントに全通貨の「1ptの価値」行が出る、(2) JCB で設定していた 1pt 価値が J-POINT(通貨)へ引き継がれている、(3) 0円プリセットで JCB 施策の率が 0% になる、(4) バックアップの書き出し/読み込み
- [ ] コミット可否をユーザーに確認(メッセージ案: `feat: 1pt価値を通貨単位へ一般化しJ-POINTをpoint_currenciesへ移設 (#13)`)

---

## Stage 2: スコア層新設(実質%換算の一本化)

### Task 5: ExpectedValueScoring.kt — 換算の純関数

**Files:**
- Create: `app/src/main/java/com/ktakjm/poikatsu/domain/ExpectedValueScoring.kt`
- Test: `app/src/test/java/com/ktakjm/poikatsu/ExpectedValueScoringTest.kt`(新規)

**Interfaces:**
- Produces:
  - `fun currencyValueFactor(currency: PointCurrency?): Double`
  - `fun effectiveValueRate(nominalRate: Double?, currency: PointCurrency?): Double?`
  - `fun effectiveRateNote(nominalRate: Double?, effectiveRate: Double?): String?` — 異なるとき「実質○%相当」、同じ/どちらか null なら null

- [ ] **Step 1: 失敗するテストを書く**(新規ファイル)

```kotlin
package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.domain.currencyValueFactor
import com.ktakjm.poikatsu.domain.effectiveRateNote
import com.ktakjm.poikatsu.domain.effectiveValueRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 期待価値スコア(円換算の実質還元率)の純関数(#13)。フィクスチャのみで実データに依存しない */
class ExpectedValueScoringTest {

    private val multiplier = PointMultiplier(label = "倍率", factor = 1.5)

    @Test
    fun `係数は1pt価値と有効な倍率の積`() {
        assertEquals(1.0, currencyValueFactor(null), 0.0)
        assertEquals(0.5, currencyValueFactor(PointCurrency(id = "c", name = "", valueYen = 0.5)), 0.0)
        assertEquals(
            0.75,
            currencyValueFactor(
                PointCurrency(id = "c", name = "", valueYen = 0.5, pointMultiplier = multiplier, multiplierEnabled = true),
            ),
            1e-9,
        )
        // 倍率OFFなら factor は掛からない
        assertEquals(
            0.5,
            currencyValueFactor(
                PointCurrency(id = "c", name = "", valueYen = 0.5, pointMultiplier = multiplier, multiplierEnabled = false),
            ),
            1e-9,
        )
    }

    @Test
    fun `実質率は名目率×係数でnullは素通し`() {
        val welcatsu = PointCurrency(id = "vp", name = "", pointMultiplier = multiplier, multiplierEnabled = true)
        assertEquals(10.5, effectiveValueRate(7.0, welcatsu)!!, 1e-9)
        assertNull(effectiveValueRate(null, welcatsu))
        assertEquals(7.0, effectiveValueRate(7.0, null)!!, 0.0)
    }

    @Test
    fun `実質併記の注記は名目と異なるときだけ`() {
        assertEquals("実質10.5%相当", effectiveRateNote(7.0, 10.5))
        assertNull(effectiveRateNote(7.0, 7.0))
        assertNull(effectiveRateNote(null, 10.5))
        assertNull(effectiveRateNote(7.0, null))
    }
}
```

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExpectedValueScoringTest*'`
Expected: FAIL(未定義)

- [ ] **Step 3: 実装**

```kotlin
package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.PointCurrency

// 期待価値スコア(#13): 円換算の実質還元率。円価値換算はこのファイルに一本化する
// (マージ層とエンジン層で同じ係数を掛ける二重適用の罠を防ぐ。設計書 §2)。

/** 通貨価値係数 = 1pt価値(円) × 有効な条件付き倍率(ウエル活等)。通貨不明(null)は等価=1.0 */
fun currencyValueFactor(currency: PointCurrency?): Double {
    if (currency == null) return 1.0
    val factor = currency.takeIf { it.multiplierEnabled }?.pointMultiplier?.factor ?: 1.0
    return currency.valueYen * factor
}

/** 実質%(スコア) = 名目還元率 × 通貨価値係数 */
fun effectiveValueRate(nominalRate: Double?, currency: PointCurrency?): Double? =
    nominalRate?.let { it * currencyValueFactor(currency) }

/** 名目と実質が異なるときだけ「実質○%相当」の併記文を返す(UI の率表示・最大おトク率で共用) */
fun effectiveRateNote(nominalRate: Double?, effectiveRate: Double?): String? {
    if (nominalRate == null || effectiveRate == null) return null
    if (nominalRate == effectiveRate) return null
    return "実質${trimRate(effectiveRate)}%相当"
}
```

- [ ] **Step 4: テスト実行**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExpectedValueScoringTest*'`
Expected: PASS

### Task 6: 換算の適用点をマージ層からスコア層へ移す(エンジン統合)

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/UserDataMerge.kt`(1pt価値・倍率の乗算を撤去)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/Models.kt`(`PaymentCard.rateMultiplier` / `welcatsuApplied` を削除)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/JudgmentEngine.kt`(`boostedCampaignRate`/`campaignRateBoosted` 削除、judge 系で `effectiveValueRate` 適用、`CampaignJudgment.nominalRate` 追加)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt`(おトクタブ施策詳細の率換算・`multiplierCardNames` 注記の参照元)
- Test: `app/src/test/java/com/ktakjm/poikatsu/JudgmentEngineTest.kt`

**Interfaces:**
- Produces:
  - `CampaignJudgment.nominalRate: Double?`(名目率。`effectiveRate` は実質%になる)
  - `scaledStoreRate(rateOverride, card) = (rateOverride + card.rateBonus)`(名目。価値乗算はスコア層)
  - `mergeUserData` は `pointCurrencyValues` を通貨マスタの `valueYen` に写すだけ(カード実効率には掛けない)
- 不変条件: **既定値(1pt=1円・倍率OFF)では judgeAll の全判定の effectiveRate が現行実装と一致**。倍率ON時は判定レベルで従来と同値(カード実効率 7.0 → 判定 10.5)

- [ ] **Step 1: 失敗するテストを書く**(不変条件と新フィールド)

`PointCurrencyMergeTest` の既存 2 件を新仕様へ書き換え+判定レベルのテストを追加:

```kotlin
    // 書き換え: マージは名目のまま(価値の適用はスコア層=judgeAll 側)
    @Test
    fun `倍率を有効にしてもマージ後のカード実効率は名目のまま`() {
        val card = merged(setOf("vp")).engineData.cards.single()
        assertEquals(7.0, card.effectiveRateDefault!!, 0.0)
    }

    @Test
    fun `判定レベルでは倍率ONのカード施策が実質率になり名目率も持つ`() {
        val merchant = Merchant(id = "m", name = "テスト店", reading = "てすとてん", category = "その他")
        val campaign = Campaign(
            id = "c1", operator = "テスト", cardId = "smcc", name = "テスト施策",
            paymentInstruction = "カード利用", rateBase = 7.0, verifiedDate = "2026-06-01",
            merchantRules = listOf(MerchantRule(merchantId = "m")),
        )
        val engineData = merged(setOf("vp")).engineData.copy(
            merchants = listOf(merchant), campaigns = listOf(campaign),
        )
        val judgment = JudgmentEngine(engineData)
            .judgeAll(merchant, LocalDate.of(2026, 6, 28)).judgments.single()
        assertEquals(10.5, judgment.effectiveRate!!, 1e-9)
        assertEquals(7.0, judgment.nominalRate!!, 0.0)
        assertTrue(judgment.welcatsuApplied)
    }

    @Test
    fun `判定レベルでは1pt価値0円の通貨の施策は実質0%になる`() {
        // 「貯まるが使わない」層(設計書 §3): 名目率は残り実質が 0 になる
        val merchant = Merchant(id = "m", name = "テスト店", reading = "てすとてん", category = "その他")
        val campaign = Campaign(
            id = "c1", operator = "テスト", cardId = "smcc", name = "テスト施策",
            paymentInstruction = "カード利用", rateBase = 7.0, verifiedDate = "2026-06-01",
            merchantRules = listOf(MerchantRule(merchantId = "m")),
        )
        val base = PoikatsuData(
            merchants = listOf(merchant), campaigns = listOf(campaign),
            cards = listOf(smccLike), pointCurrencies = listOf(vpoint), updatedAt = "",
        )
        val engineData = mergeUserData(
            base, cardOverrides = emptyMap(), ownedBrands = emptySet(), customCards = emptyList(),
            customCampaigns = emptyList(), enabledPointMultipliers = emptySet(),
            pointCurrencyValues = mapOf("vp" to 0.0),
        ).engineData
        val judgment = JudgmentEngine(engineData)
            .judgeAll(merchant, LocalDate.of(2026, 6, 28)).judgments.single()
        assertEquals(0.0, judgment.effectiveRate!!, 0.0)
        assertEquals(7.0, judgment.nominalRate!!, 0.0)
    }
```

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*PointCurrencyMergeTest*'`
Expected: FAIL

- [ ] **Step 3: 実装**

1. `UserDataMerge.kt`: カードの `pointValue` / `factor` / `rateMultiplier` / `welcatsuApplied` の計算を削除し、`effectiveRateDefault = rawRate?.let { it + classBonus }`・`rateBonus = classBonus` のみに。コメントは「1pt 価値・倍率の円換算はスコア層(ExpectedValueScoring)で判定時に一括適用する(#13)。マージは名目率(クラス加算まで)を組む」と更新。**クラス加算の합성順のロジックコメントは残す**
2. `Models.kt`: `PaymentCard.rateMultiplier` / `welcatsuApplied` を削除
3. `JudgmentEngine.kt`:
   - `scaledStoreRate(rateOverride, card) = rateOverride + card.rateBonus`(doc の式も更新)
   - `boostedCampaignRate` / `campaignRateBoosted` を削除
   - `CampaignJudgment` に `/** 名目還元率(円換算前)。effectiveRate(実質%)と異なるとき UI が「実質○%相当」を併記する */ val nominalRate: Double? = null,` を追加
   - `buildJudgment` に `nominalRate: Double?` パラメータを追加してそのまま写す(lottery は effectiveRate と同様 null に落とす)
   - `judgeCards`: `val nominal = resolveCardCampaignRate(campaign, card, rule.rateOverride).effectiveRate` → `effectiveRate = effectiveValueRate(nominal, currency)`、`nominalRate = nominal`、`welcatsuApplied = currency?.multiplierEnabled == true && currency.pointMultiplier != null && nominal != null`。※通貨は `payoutCurrency(...)` が rebate 以外で null を返すため、discount/lottery に係数が掛かることはない(既存テスト `即時割引の施策には通貨の倍率が掛からない` で担保)
   - `judgeQr` / `judgePrograms`: 同様に `val nominal = rule.rateOverride ?: campaign.rateBase` → `effectiveValueRate(nominal, currency)`
4. `MainViewModel.kt`:
   - おトクタブ施策詳細(2126 行付近)の `boostedCampaignRate` / `campaignRateBoosted` 利用を `effectiveValueRate` / `effectiveRateNote` ベースへ書き換え
   - 設定画面の `multiplierCardNames` 注記(「○○の還元率を×1.5で表示中」)の算出はカードの `welcatsuApplied` に依存しない形(通貨を稼ぐ所有カード名の列挙のまま)へ — 参照箇所を確認し `card.pointCurrencyId == currency.id` で引く
5. コンパイルエラーを全て解消(`welcatsuApplied` を参照する UI は `CampaignJudgment.welcatsuApplied` 側なので影響なしのはず。`PaymentCard.rateMultiplier` の参照は UserDataMerge/scaledStoreRate のみ)

- [ ] **Step 4: 全テスト実行と回帰の確認**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全 PASS。既存の倍率系テスト(`promotionの率にも払い出し通貨の倍率が掛かる` / `カードの実効率には倍率を二重適用しない` 等)は**期待値を変えずに**通ること(判定レベルの数値は不変が正)。`CardClassMergeTest` はマージ結果が名目になるため期待値を `(率+加算)` に更新し、価値込みの検証は判定レベルのテストへ移す

### Task 7: UI — 「実質○%相当」の併記表示

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/JudgmentEngine.kt`(`BestPaymentOption.nominalRate` 追加)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/JudgmentScreen.kt`(`BestOptionBanner` / `CampaignJudgmentCard` の率表示)
- Test: `app/src/test/java/com/ktakjm/poikatsu/JudgmentEngineTest.kt`

**Interfaces:**
- Produces: `BestPaymentOption.nominalRate: Double? = null`(determineBest が best 判定の nominalRate を写す)
- 表示規則: 名目==実質 → 従来表示(「7% 還元」)。異なる → 名目表示+「(実質10.5%相当)」を後置。lottery/discount は対象外

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
    @Test
    fun `bestOptionは名目率も持ち実質率で選ばれる`() {
        // Task 6 のフィクスチャを流用: 倍率ONのとき rate=実質10.5 / nominalRate=7.0
        val merchant = Merchant(id = "m", name = "テスト店", reading = "てすとてん", category = "その他")
        val campaign = Campaign(
            id = "c1", operator = "テスト", cardId = "smcc", name = "テスト施策",
            paymentInstruction = "カード利用", rateBase = 7.0, verifiedDate = "2026-06-01",
            merchantRules = listOf(MerchantRule(merchantId = "m")),
        )
        val engineData = merged(setOf("vp")).engineData.copy(
            merchants = listOf(merchant), campaigns = listOf(campaign),
        )
        val best = JudgmentEngine(engineData)
            .judgeAll(merchant, LocalDate.of(2026, 6, 28)).bestOption!!
        assertEquals(10.5, best.rate!!, 1e-9)
        assertEquals(7.0, best.nominalRate!!, 0.0)
    }
```

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*PointCurrencyMergeTest*'`
Expected: FAIL(`nominalRate` 未定義)

- [ ] **Step 3: 実装**

- `BestPaymentOption` に `val nominalRate: Double? = null` を追加、`determineBest` で `nominalRate = best.nominalRate` を写す
- `BestOptionBanner`(JudgmentScreen.kt:312): ラベルを名目基準にし、併記を追加:

```kotlin
    val label = formatBenefit(best.benefitType, best.nominalRate ?: best.rate, best.discountAmount) ?: return
    val note = effectiveRateNote(best.nominalRate, best.rate)
    val benefitLabel = "${best.method} $label" + (note?.let { "($it)" } ?: "")
```

- `CampaignJudgmentCard` の率表示: 表示値を `judgment.nominalRate ?: judgment.effectiveRate` にし、`effectiveRateNote(judgment.nominalRate, judgment.effectiveRate)` が非 null なら「(実質10.5%相当)」を率ラベルの直後に追記する。既存の `welcatsuApplied` の適用時注記(appliedNote)は表示条件そのまま(倍率が掛かった事実の説明として残す)
- 一覧ラベル `bestBenefitLabel()` は実質値のまま(短いラベルに併記は入れない。判定詳細で名目が分かる)

- [ ] **Step 4: 全テスト+ビルド**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS

### Stage 2 チェックポイント(ユーザー確認)

- [ ] 全テスト PASS(既定値で判定数値が Stage 1 時点と完全一致していることをテストが担保)
- [ ] 実機検証依頼: (1) ウエル活ONでウエルシアの判定が「7% 還元(実質10.5%相当)」になる、(2) 1pt=0.5円設定で実質が半分になる、(3) 既定値では表示が従来どおり
- [ ] コミット可否確認(案: `feat: 円換算の実質還元率(期待価値スコア)を導入し価値換算をスコア層へ一本化 (#13)`)

---

## Stage 3: 期間限定ポイントの残高・失効日+失効通知

### Task 8: 失効通知の純関数

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/ExpectedValueScoring.kt`
- Test: `app/src/test/java/com/ktakjm/poikatsu/ExpectedValueScoringTest.kt`

**Interfaces:**
- Produces:
  - `const val EXPIRY_NOTICE_DAYS = 30` / `const val EXPIRY_WARN_DAYS = 7`
  - `data class ExpiringPointNotice(val currencyId: String, val currencyName: String, val balancePt: Int, val expiryDate: LocalDate, val daysLeft: Long, val warn: Boolean)`
  - `fun expiringPointNotices(balances: Map<String, PointBalance>, currencies: List<PointCurrency>, today: LocalDate): List<ExpiringPointNotice>`
  - `fun PointBalance.isExpired(today: LocalDate): Boolean`(設定画面の「失効済み」表示と共用)

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
    @Test
    fun `失効30日以内の残高だけ通知になり近い順に並ぶ`() {
        val currencies = listOf(
            PointCurrency(id = "rp", name = "楽天ポイント"),
            PointCurrency(id = "dp", name = "dポイント"),
            PointCurrency(id = "vp", name = "Vポイント"),
        )
        val today = LocalDate.of(2026, 8, 19)
        val notices = expiringPointNotices(
            balances = mapOf(
                "rp" to PointBalance(balancePt = 500, expiryDate = "2026-08-22"), // 残り3日
                "dp" to PointBalance(balancePt = 300, expiryDate = "2026-09-10"), // 残り22日
                "vp" to PointBalance(balancePt = 900, expiryDate = "2026-12-01"), // 30日超=通知しない
            ),
            currencies = currencies,
            today = today,
        )
        assertEquals(listOf("rp", "dp"), notices.map { it.currencyId })
        assertEquals(3L, notices[0].daysLeft)
        assertTrue(notices[0].warn) // 7日以内は強調
        assertFalse(notices[1].warn)
        assertEquals("楽天ポイント", notices[0].currencyName)
    }

    @Test
    fun `失効日当日は通知され翌日からは失効済みで通知されない`() {
        val currencies = listOf(PointCurrency(id = "rp", name = "楽天ポイント"))
        val balance = mapOf("rp" to PointBalance(balancePt = 100, expiryDate = "2026-08-19"))
        assertEquals(1, expiringPointNotices(balance, currencies, LocalDate.of(2026, 8, 19)).size)
        assertEquals(0, expiringPointNotices(balance, currencies, LocalDate.of(2026, 8, 20)).size)
        assertTrue(balance["rp"]!!.isExpired(LocalDate.of(2026, 8, 20)))
        assertFalse(balance["rp"]!!.isExpired(LocalDate.of(2026, 8, 19)))
    }

    @Test
    fun `残高0や不正な日付や未知の通貨は通知しない`() {
        val currencies = listOf(PointCurrency(id = "rp", name = "楽天ポイント"))
        val today = LocalDate.of(2026, 8, 19)
        assertEquals(0, expiringPointNotices(mapOf("rp" to PointBalance(0, "2026-08-22")), currencies, today).size)
        assertEquals(0, expiringPointNotices(mapOf("rp" to PointBalance(100, "not-a-date")), currencies, today).size)
        assertEquals(0, expiringPointNotices(mapOf("unknown" to PointBalance(100, "2026-08-22")), currencies, today).size)
    }
```

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExpectedValueScoringTest*'`
Expected: FAIL

- [ ] **Step 3: 実装**(ExpectedValueScoring.kt に追加)

```kotlin
/** 失効通知を出す残り日数のしきい値(この日数以内で表示) */
const val EXPIRY_NOTICE_DAYS = 30L

/** warning 強調に切り替える残り日数のしきい値 */
const val EXPIRY_WARN_DAYS = 7L

/**
 * 期間限定ポイントの失効通知 1 件(#13)。施策の有無・決済手段と独立に、判定結果画面へ
 * 「残り3日で失効する楽天ポイント 500pt あり」を出すための算出結果。
 * 施策開催店でのポイント払いは施策対象か確認が要るため、判定(最良比較)には効かせない(設計書 §4)。
 */
data class ExpiringPointNotice(
    val currencyId: String,
    val currencyName: String,
    val balancePt: Int,
    val expiryDate: LocalDate,
    val daysLeft: Long,
    /** 残り EXPIRY_WARN_DAYS 日以内(warning 系ロールで強調) */
    val warn: Boolean,
)

/** 失効日を過ぎたか(失効日当日までは利用可能)。設定画面の「失効済み」表示と通知の除外で共用 */
fun PointBalance.isExpired(today: LocalDate): Boolean {
    val expiry = runCatching { LocalDate.parse(expiryDate) }.getOrNull() ?: return false
    return today.isAfter(expiry)
}

/** 失効30日以内・残高ありの通知を失効日の近い順に返す。不正な日付・未知の通貨は黙って落とす */
fun expiringPointNotices(
    balances: Map<String, PointBalance>,
    currencies: List<PointCurrency>,
    today: LocalDate,
): List<ExpiringPointNotice> {
    val currencyById = currencies.associateBy { it.id }
    return balances.mapNotNull { (id, balance) ->
        val currency = currencyById[id] ?: return@mapNotNull null
        if (balance.balancePt <= 0) return@mapNotNull null
        val expiry = runCatching { LocalDate.parse(balance.expiryDate) }.getOrNull() ?: return@mapNotNull null
        val daysLeft = ChronoUnit.DAYS.between(today, expiry)
        if (daysLeft < 0 || daysLeft > EXPIRY_NOTICE_DAYS) return@mapNotNull null
        ExpiringPointNotice(
            currencyId = id,
            currencyName = currency.name,
            balancePt = balance.balancePt,
            expiryDate = expiry,
            daysLeft = daysLeft,
            warn = daysLeft <= EXPIRY_WARN_DAYS,
        )
    }.sortedBy { it.daysLeft }
}
```

import に `com.ktakjm.poikatsu.data.PointBalance` / `java.time.LocalDate` / `java.time.temporal.ChronoUnit` を追加。

- [ ] **Step 4: テスト実行**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExpectedValueScoringTest*'`
Expected: PASS

### Task 9: 設定 UI — 残高・失効日の入力(通貨ごとに1件)

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/PaymentMethodsSettings.kt`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/CustomCampaignEditor.kt`(`EditorDatePickerDialog` を `internal` にして共用)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt`(`PointCurrencySetting` に balance / VM setter)
- Test: 目視+既存単体テストの回帰

**Interfaces:**
- Produces: `PointCurrencySetting.balance: PointBalance?` / `balanceExpired: Boolean`。VM に `fun setPointBalance(currencyId: String, balance: PointBalance?)`
- Consumes: Task 3 の `SettingsRepository.setPointBalance`、Task 8 の `PointBalance.isExpired`

- [ ] **Step 1: VM 拡張**

`PointCurrencySetting` に `val balance: PointBalance? = null` / `val balanceExpired: Boolean = false` を追加し、組み立てで `settings.pointBalances[currency.id]` と `balance?.isExpired(LocalDate.now()) == true` を設定。`setPointBalance` は `settingsRepo.setPointBalance` へ委譲。

- [ ] **Step 2: 通貨行に「期間限定ポイント」行を追加**(1pt 価値行の下)

```kotlin
                // 期間限定ポイントの残高・失効日(#13: 通貨ごとに1件=直近失効分)
                ListItem(
                    headlineContent = { Text("期間限定ポイント") },
                    supportingContent = {
                        when {
                            currency.balance == null -> Text("残高と失効日を登録すると、失効前にお知らせします")
                            currency.balanceExpired -> Text(
                                "${currency.balance.expiryDate} に失効済み。残高を入れ直してください",
                                color = warningColor(),
                            )
                            else -> Text("${"%,d".format(currency.balance.balancePt)}pt・${currency.balance.expiryDate} まで")
                        }
                    },
                    colors = transparentListItemColors(),
                    modifier = Modifier.padding(start = 24.dp).clickable { editingBalanceCurrency = currency },
                )
```

入力ダイアログ(残高 pt の数値入力+失効日ボタン→ `EditorDatePickerDialog`。「削除」で null):

```kotlin
/** 期間限定ポイントの残高・失効日入力(#13)。「削除」で登録を消す */
@Composable
private fun PointBalanceEditDialog(
    currency: MainViewModel.PointCurrencySetting,
    onDismiss: () -> Unit,
    onConfirm: (PointBalance?) -> Unit,
) {
    var balanceText by remember { mutableStateOf(currency.balance?.balancePt?.toString().orEmpty()) }
    var expiry by remember { mutableStateOf(currency.balance?.expiryDate.orEmpty()) }
    var showDatePicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${currency.name}の期間限定ポイント") },
        text = {
            Column {
                Text(
                    "直近で失効する分の残高と失効日を入力してください。失効が近づくと判定画面でお知らせします。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("残高") },
                    suffix = { Text("pt") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text(expiry.ifBlank { "失効日を選ぶ" })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pt = balanceText.toIntOrNull()
                    if (pt != null && pt > 0 && expiry.isNotBlank()) {
                        onConfirm(PointBalance(balancePt = pt, expiryDate = expiry))
                    }
                },
                enabled = balanceText.toIntOrNull()?.let { it > 0 } == true && expiry.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("削除") } },
    )
    if (showDatePicker) {
        EditorDatePickerDialog(
            initial = expiry.takeIf { it.isNotBlank() },
            onConfirm = { expiry = it; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
}
```

`EditorDatePickerDialog` は `CustomCampaignEditor.kt` で `private` → `internal` にする(横画面 Input モード+verticalScroll の既存実装をそのまま共用。シグネチャが上記と異なる場合は実物に合わせて呼び出し側を書く)。

- [ ] **Step 3: テスト+ビルド**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS

### Task 10: 判定画面に失効通知を表示

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt`(UiState に notices)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/JudgmentScreen.kt`(通知行)
- Test: 目視+回帰

**Interfaces:**
- Produces: `UiState.expiringPointNotices: List<ExpiringPointNotice>`(rebuild で `expiringPointNotices(settings.pointBalances, merged.engineData.pointCurrencies, LocalDate.now())` を設定)。JudgmentScreen が引数で受けて判定リスト最上部(bestOption バナーより上)に表示

- [ ] **Step 1: VM**: `rebuild()` で算出して UiState へ。判定はどのお店でも同じ内容なので Selection でなく UiState に持つ(施策と独立=設計書 §4)

- [ ] **Step 2: JudgmentScreen**: LazyColumn 先頭(`__empty` / `__best` より上)に:

```kotlin
        if (expiringNotices.isNotEmpty()) {
            item(key = "__expiring_points") { ExpiringPointsNotice(expiringNotices) }
        }
```

```kotlin
/** 期間限定ポイントの失効通知(#13)。施策の有無と独立に出す(warning=残り7日以内は強調) */
@Composable
private fun ExpiringPointsNotice(notices: List<ExpiringPointNotice>) {
    val warn = notices.any { it.warn }
    Surface(
        color = if (warn) warningContainerColor() else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (warn) onWarningContainerColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            notices.forEach { n ->
                val days = if (n.daysLeft == 0L) "今日" else "残り${n.daysLeft}日で"
                Text(
                    "${days}失効する${n.currencyName} ${"%,d".format(n.balancePt)}pt あり",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
```

判定 0 件(`__empty`)のときも通知は出す(施策と独立のため)。

- [ ] **Step 3: テスト+ビルド**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS

### Stage 3 チェックポイント(ユーザー確認)

- [ ] 実機検証依頼: (1) 残高・失効日を登録→どのお店の判定画面にも「残り○日で失効する楽天ポイント 500pt あり」が出る(キャンペーンの無いお店でも)、(2) 7日以内で warning 色になる、(3) 失効日を過ぎると通知が消え設定行に「失効済み」が出る
- [ ] コミット可否確認(案: `feat: 期間限定ポイントの残高・失効日入力と失効通知を追加 (#13)`)

---

## Stage 4: 提示スタック合算(実質○%相当)

### Task 11: 合算の純関数+judgeAll 統合

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/ExpectedValueScoring.kt`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/JudgmentEngine.kt`(`JudgmentResult.stackedRate`)
- Test: `app/src/test/java/com/ktakjm/poikatsu/ExpectedValueScoringTest.kt` / `JudgmentEngineTest.kt`

**Interfaces:**
- Produces:
  - `data class StackedRate(val totalRate: Double, val paymentRate: Double, val presentationRate: Double)`
  - `fun stackedRate(best: BestPaymentOption?, presentation: List<CampaignJudgment>): StackedRate?` — best が定率(rate 非 null)かつ提示側に合算可能な実質%(effectiveRate 非 null・discountAmount null・productScope null)が 1 つ以上あるときだけ返す
  - `JudgmentResult.stackedRate: StackedRate? = null`(judgeAll が設定)

- [ ] **Step 1: 失敗するテストを書く**(JudgmentEngineTest のプログラム提示テスト群の隣に追加。既存テスト `プログラム提示施策は決済施策と共存し最良は決済側から選ぶ`(:1309)のフィクスチャを流用する)

```kotlin
    @Test
    fun `提示施策があると決済分と合算した実質率が返る`() {
        // 既存 :1309 と同じデータ構成(決済 7% + プログラム提示 3%)で judgeAll を呼ぶ
        val result = /* :1309 と同じ engine.judgeAll(...) */
        val stacked = result.stackedRate!!
        assertEquals(7.0, stacked.paymentRate, 0.0)
        assertEquals(3.0, stacked.presentationRate, 0.0)
        assertEquals(10.0, stacked.totalRate, 1e-9)
    }

    @Test
    fun `提示施策が無ければ合算はnull`() {
        // 決済施策のみのチェーン(既存フィクスチャの mcdonalds)で stackedRate が null
        assertNull(engine.judgeAll(merchant("mcdonalds"), today).stackedRate)
    }
```

(実装時は :1309 のフィクスチャ定義をコピーせず同じ private ヘルパを使う。ヘルパが無ければ同クラス内の実データに合わせて記述する)

`ExpectedValueScoringTest` には純関数の境界を追加:

```kotlin
    @Test
    fun `合算は定率の提示だけを足し定額と対象商品限定は無視する`() {
        val best = BestPaymentOption(
            method = "テストカード", rate = 7.0, discountAmount = null,
            benefitType = BenefitType.REBATE, isTimeLimited = false, daysRemaining = null,
        )
        // effectiveRate=3.0 の提示 + discountAmount 付き提示 + effectiveRate=null の提示
        val presentation = listOf(
            presentationJudgment(rate = 3.0),
            presentationJudgment(rate = null, discount = 100),
            presentationJudgment(rate = null),
        )
        val stacked = stackedRate(best, presentation)!!
        assertEquals(3.0, stacked.presentationRate, 0.0)
        assertEquals(10.0, stacked.totalRate, 1e-9)
        assertNull(stackedRate(null, presentation)) // 最良が無ければ合算しない
        assertNull(stackedRate(best, emptyList()))
    }
```

(`presentationJudgment` はテスト内の private ヘルパ: presentation_only の Campaign を持つ最小の CampaignJudgment を組む)

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExpectedValueScoringTest*'`
Expected: FAIL

- [ ] **Step 3: 実装**(ExpectedValueScoring.kt)

```kotlin
/**
 * 提示スタック合算(#13 設計書 §5): 最良の決済手段の実質% + 併用可能な提示施策の実質%。
 * 異なる通貨の足し算は各判定が 1pt 価値設定で円換算済みのため正当(1pt=1円の暗黙仮定を置かない)。
 * 合算は二重取り(決済1+提示N)まで。定額(discountAmount)・率なし・対象商品限定の提示は足さない。
 */
data class StackedRate(
    val totalRate: Double,
    val paymentRate: Double,
    val presentationRate: Double,
)

fun stackedRate(best: BestPaymentOption?, presentation: List<CampaignJudgment>): StackedRate? {
    val paymentRate = best?.rate ?: return null
    val presentationRate = presentation
        .filter { it.discountAmount == null && it.campaign.productScope == null }
        .mapNotNull { it.effectiveRate }
        .sum()
    if (presentationRate <= 0.0) return null
    return StackedRate(
        totalRate = paymentRate + presentationRate,
        paymentRate = paymentRate,
        presentationRate = presentationRate,
    )
}
```

`JudgmentEngine.judgeAll`: `JudgmentResult` に `val stackedRate: StackedRate? = null` を追加し、

```kotlin
        val bestOption = determineBest(active)
        return JudgmentResult(active, bestOption, excluded, presentation, stackedRate(bestOption, presentation))
```

- [ ] **Step 4: テスト実行**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS

### Task 12: 合算の表示(BestOptionBanner)

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/JudgmentScreen.kt`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt`(Selection に stackedRate を写す)
- Test: 目視+回帰

**Interfaces:**
- Consumes: `JudgmentResult.stackedRate`(Selection 経由で JudgmentScreen へ)

- [ ] **Step 1: 実装**

- `Selection` に `val stackedRate: StackedRate? = null` を追加し `selectionFor` で写す
- `BestOptionBanner(best, stacked)`: stacked 非 null のとき 2 行目を追加:

```kotlin
            if (stacked != null) {
                Text(
                    "あわせて提示で実質${trimRate(stacked.totalRate)}%相当" +
                        "(お支払い${trimRate(stacked.paymentRate)}% + 提示${trimRate(stacked.presentationRate)}%)",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
```

- 表示条件: 既存バナーは `judgments.size >= 2` でしか出ないため、`stackedRate != null` のときは判定 1 件でもバナーを出すよう条件を `(selection.bestOption != null && (selection.judgments.size >= 2 || selection.stackedRate != null))` に変更(合算は 1 決済+提示でも意味がある)
- 既存の「あわせて提示でおトク」並記枠はそのまま(内訳の説明役。設計書 §5)

- [ ] **Step 2: テスト+ビルド**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS

### Stage 4 チェックポイント(ユーザー確認)

- [ ] 実機検証依頼: dポイント会員 ON+提示施策のあるお店で「あわせて提示で実質○%相当(お支払い○% + 提示○%)」が出る。会員 OFF で消える
- [ ] コミット可否確認(案: `feat: 提示スタック合算(実質○%相当)を最大おトク率バナーに表示 (#13)`)

---

## Stage 5: rebate vs coupon 損益分岐

### Task 13: 分岐額の純関数+判定への注記

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/ExpectedValueScoring.kt`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/JudgmentEngine.kt`(`CampaignJudgment.breakevenAmount` を judgeAll で付与)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/JudgmentScreen.kt`(定額カードの注記)
- Test: `app/src/test/java/com/ktakjm/poikatsu/ExpectedValueScoringTest.kt` / `JudgmentEngineTest.kt`

**Interfaces:**
- Produces:
  - `fun breakevenAmount(discountAmount: Int, bestRate: Double): Int?` — bestRate <= 0 なら null(分岐なし=金額によらず定額が得)、それ以外は 10 円単位切り上げ
  - `CampaignJudgment.breakevenAmount: Int? = null`(discountAmount 非 null の判定に judgeAll が付与。最良の実質%は**決済分のみ**=stackedRate でなく bestOption.rate を使う。設計書 §6)

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
    @Test
    fun `損益分岐額は割引額÷実質率を10円単位で切り上げ`() {
        assertEquals(2000, breakevenAmount(discountAmount = 100, bestRate = 5.0))
        assertEquals(1340, breakevenAmount(discountAmount = 100, bestRate = 7.5))  // 1333.3→1340
        assertEquals(960, breakevenAmount(discountAmount = 100, bestRate = 10.5)) // 952.4→960
        assertNull(breakevenAmount(discountAmount = 100, bestRate = 0.0))
    }
```

`JudgmentEngineTest`(フィクスチャ)に judgeAll レベルを追加: 定率 7% の施策と定額 100 円引きの施策が同居するチェーンで、定額判定に `breakevenAmount = 1430`(100÷0.07=1428.6→1430)が付き、定率判定には付かないこと。定率施策が無いチェーンでは定額判定の `breakevenAmount` が null のままであること。

- [ ] **Step 2: 実行して失敗を確認**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExpectedValueScoringTest*'`
Expected: FAIL

- [ ] **Step 3: 実装**

ExpectedValueScoring.kt:

```kotlin
/**
 * rebate vs coupon の損益分岐額(#13 設計書 §6): この金額未満の買い物なら定額(割引・定額還元)が得。
 * 比較相手は最良の実質%(決済分のみ。提示分は定額を使う場合でも併用でき両辺に等しく乗る)。
 * 10 円単位で切り上げ(端数の分岐点を「得」側に誤らせない保守側の丸め)。
 */
fun breakevenAmount(discountAmount: Int, bestRate: Double): Int? {
    if (bestRate <= 0.0) return null
    val raw = discountAmount * 100.0 / bestRate
    return (ceil(raw / 10.0) * 10.0).toInt()
}
```

`JudgmentEngine.judgeAll`: bestOption 決定後、active 内の定額判定へ付与:

```kotlin
        val bestOption = determineBest(active)
        val annotated = bestOption?.rate?.let { bestRate ->
            active.map { j ->
                if (j.discountAmount != null) j.copy(breakevenAmount = breakevenAmount(j.discountAmount, bestRate))
                else j
            }
        } ?: active
        return JudgmentResult(annotated, bestOption, excluded, presentation, stackedRate(bestOption, presentation))
```

`CampaignJudgment` に `/** この金額(円)未満の買い物ならこの定額特典が最良の定率より得(#13)。定率の最良が無いチェーンは null */ val breakevenAmount: Int? = null,` を追加。

JudgmentScreen の `CampaignJudgmentCard`(定額特典の表示部)に注記:

```kotlin
    judgment.breakevenAmount?.let {
        Text(
            "%,d円未満のお買い物ならこちらが得".format(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // 定率の最良が無いチェーンの定額特典(breakevenAmount == null かつ discountAmount != null かつ
    // bestOption == null)は「金額によらずこちらが得」— bestOption の有無は Selection 側で分かるため
    // CampaignJudgmentCard に isOnlyFixedBenefit: Boolean を渡して出し分ける
```

- [ ] **Step 4: 全テスト+ビルド**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS

### Stage 5 チェックポイント(ユーザー確認)

- [ ] 実機検証依頼: 定率と定額が同居するお店(エポス優待系)で定額カードに「○○円未満のお買い物ならこちらが得」が出る
- [ ] コミット可否確認(案: `feat: 定額特典に損益分岐額の注記を追加 (#13)`)

---

## 仕上げ: ドキュメント反映とクローズ

### Task 14: ロードマップ・PLAN 反映

- [ ] `docs/roadmap.md`: Phase 3 セクションを「実装済み」に更新し、#13 の内容(5 サブ機能+見送り 3 論点)と設計書へのリンクを記録
- [ ] `PLAN.md`: Phase 3 に完了マークと実績メモ(消化ボーナスは失効通知として実装=判定は変えない、の方針変更を明記)
- [ ] data-test に検証用ショーケースが揃っているか確認(1pt 価値付き通貨・残高入力で通知が出る動線)
- [ ] 最終確認: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
- [ ] ユーザーへ: 全体の実機検証・コミット/プッシュ・issue #13 のクローズコメント・Project Done 移動・バージョン(0.6.0 相当)タグの要否を確認

---

## Self-Review 済み事項

- スペック §1〜§10 の全要件にタスクが対応(§3=Task 1-4、§2=Task 5-7、§4=Task 8-10、§5=Task 11-12、§6=Task 13、§7=Task 7/10/12、§8=Task 3-4/9、§9-10=各 Stage 構成)
- 型整合: `PointBalance(balancePt, expiryDate)` / `PointCurrency.valueYen` / `CampaignJudgment.nominalRate` / `StackedRate(totalRate, paymentRate, presentationRate)` / `breakevenAmount` はタスク間で同名・同型
- 既知の注意点(実装者向け):
  - Task 6 が本計画最大の変更。既存テストの期待値を変えるのは**マージ層のテストだけ**で、判定レベルの数値は不変が正。判定レベルの数値が変わったらバグ
  - `payoutCurrency` は rebate 以外に null を返すため、discount/lottery に価値係数が掛からないのは既存ロジックで保証される
  - `EditorDatePickerDialog` の実シグネチャ(LocalDate か String か)は CustomCampaignEditor.kt:963 を確認して呼び出し側を合わせる
  - grep 必須: `rateMultiplier` / `welcatsuApplied` / `boostedCampaignRate` / `pointValueConfig` / `setPointValue` の全参照(UI・VM・テスト)を各タスクで潰す
