# issue #86: StoreScope enum 化 + 帰属 Attribution sealed 化 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 施策データの判別フィールド(store_scope・帰属4種)の生 String / nullable 分岐を型付き(enum / sealed interface)に集約し、散在する 12+13 箇所の分岐を when 網羅チェックが効く形にする。

**Architecture:** JSON スキーマは不変。`Campaign` のコンストラクタ引数を `storeScopeRaw: String`(@SerialName("store_scope"))に改名し、導出プロパティ `val storeScope: StoreScope` を生やす(ユーザー確認済み)。帰属は `sealed interface Attribution`(Card/Brand/Qr/Program)+導出 `val attribution: Attribution?` を data/Models.kt に置き(Models.brandColorOf 自身が使うため domain には置けない)、`CustomPayment` にも同じ Attribution を導出で共用する。単純なフィールド構築(CustomCampaigns.toCampaigns の Campaign 生成)は raw フィールドのまま(シリアライズの源泉)。

**Tech Stack:** Kotlin / kotlinx.serialization / JUnit4。ビルド確認は `./gradlew :app:testDebugUnitTest :app:assembleDebug`。

**Spec:** GitHub issue #86(refactor: StoreScope の enum 化と施策帰属4種の sealed 化)

## Global Constraints

- JSON スキーマ不変(`@SerialName` で store_scope / card_id / card_brand / payment_method_id / point_program_id のまま)
- 整合性テスト(JudgmentEngineTest「実データ_施策の帰属は4種のうちちょうど1つ」等)はそのまま残す
- domain/ は Android 非依存の純 Kotlin を維持
- 外部挙動は不変(リファクタ)。既存テストが全部通ることがガード
- **コミット・プッシュはユーザーの指示があってから**(CLAUDE.md。本計画にコミット手順は含めない)
- type 別 sealed class 化(和集合クラスの解消)はスコープ外

---

### Task 1: StoreScope enum + Campaign.storeScope の enum 化(raw 改名)

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/Models.kt`(Campaign 定義 359 行付近)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/JudgmentEngine.kt:700,787,893,939,978`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/CustomCampaigns.kt:124`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/UiHelpers.kt:784`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt:956`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/CampaignScreen.kt:459,516`
- Modify: `app/src/test/java/com/ktakjm/poikatsu/JudgmentEngineTest.kt:555,585,751,758,2082,2356,2366,2449,2917`
- Modify: `app/src/test/java/com/ktakjm/poikatsu/BannerTest.kt:100`
- Modify: `app/src/test/java/com/ktakjm/poikatsu/CustomCampaignTest.kt:154,166`
- Create: `app/src/test/java/com/ktakjm/poikatsu/CampaignModelTest.kt`

**Interfaces:**
- Produces: `enum class StoreScope(val jsonValue: String) { MANAGED, EXTERNAL }` + `StoreScope.fromString(s: String): StoreScope`(不明値は MANAGED)、`Campaign.storeScope: StoreScope`(導出)、`Campaign.storeScopeRaw: String`(旧 storeScope。JSON との写像)

- [x] **Step 1: 失敗するテストを書く**

`app/src/test/java/com/ktakjm/poikatsu/CampaignModelTest.kt` を新規作成:

```kotlin
package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.StoreScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignModelTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `fromString_managedとexternalを対応するenumへ`() {
        assertEquals(StoreScope.MANAGED, StoreScope.fromString("managed"))
        assertEquals(StoreScope.EXTERNAL, StoreScope.fromString("external"))
    }

    @Test
    fun `fromString_不明値はMANAGEDにフォールバック`() {
        assertEquals(StoreScope.MANAGED, StoreScope.fromString("unknown_value"))
        assertEquals(StoreScope.MANAGED, StoreScope.fromString(""))
    }

    @Test
    fun `JSONのstore_scopeが導出プロパティでenumになる`() {
        val c = json.decodeFromString<Campaign>(
            """{"id":"c1","operator":"op","name":"n","verified_date":"2026-01-01","store_scope":"external"}"""
        )
        assertEquals(StoreScope.EXTERNAL, c.storeScope)
    }

    @Test
    fun `store_scope省略時はMANAGED`() {
        val c = json.decodeFromString<Campaign>(
            """{"id":"c1","operator":"op","name":"n","verified_date":"2026-01-01"}"""
        )
        assertEquals(StoreScope.MANAGED, c.storeScope)
    }
}
```

- [x] **Step 2: テストが失敗することを確認**

Run: `./gradlew :app:testDebugUnitTest --tests com.ktakjm.poikatsu.CampaignModelTest`
Expected: コンパイルエラー(unresolved reference: StoreScope)

- [x] **Step 3: Models.kt に enum + 導出プロパティを実装**

Campaign 定義の直前(Recurrence の後)に追加:

```kotlin
/**
 * 施策の店舗スコープ。managed=収録チェーン(merchant_rules)で判定する通常施策、
 * external=お店を列挙できない全店型(おトクタブ専用。お店・地図の判定には出ない)。
 */
enum class StoreScope(val jsonValue: String) {
    MANAGED("managed"),
    EXTERNAL("external");

    companion object {
        fun fromString(s: String): StoreScope = entries.find { it.jsonValue == s } ?: MANAGED
    }
}
```

Campaign のコンストラクタ引数を改名(JSON 名は @SerialName で不変):

```kotlin
    /** store_scope の生値(JSON との写像)。分岐には導出の [storeScope] を使う */
    @SerialName("store_scope") val storeScopeRaw: String = "managed",
) {
    val storeScope: StoreScope get() = StoreScope.fromString(storeScopeRaw)
}
```

(Campaign は現在ボディ無しの data class。`)` を `) {` にしてボディを追加する)

- [x] **Step 4: 生文字列比較 12 箇所を置換(コンパイルエラー駆動)**

`storeScope` の型が変わるためコンパイルが全使用箇所を洗い出す。置換内容:

- `JudgmentEngine.kt:700` `.filter { it.storeScope == "managed" && isTargetDay(it, today) }` → `.filter { it.storeScope == StoreScope.MANAGED && isTargetDay(it, today) }`
- `JudgmentEngine.kt:787` `if (campaign.storeScope == "external")` → `if (campaign.storeScope == StoreScope.EXTERNAL)`
- `JudgmentEngine.kt:893,939,978` `.filter { it.storeScope == "managed" }` → `.filter { it.storeScope == StoreScope.MANAGED }`
- `CustomCampaigns.kt:124` `storeScope = if (allStores) "external" else "managed",` → `storeScopeRaw = (if (allStores) StoreScope.EXTERNAL else StoreScope.MANAGED).jsonValue,`
- `UiHelpers.kt:784`・`MainViewModel.kt:956`・`CampaignScreen.kt:459` `== "managed"` → `== StoreScope.MANAGED`
- `CampaignScreen.kt:516` `judgments.first { it.campaign.storeScope == "managed" }` → `judgments.first { it.campaign.storeScope == StoreScope.MANAGED }`
- 各ファイルに `import com.ktakjm.poikatsu.data.StoreScope` を追加

テスト側:

- `JudgmentEngineTest.kt:555` ヘルパ引数 `storeScope: String = "managed"` → `storeScope: StoreScope = StoreScope.MANAGED`、585 の受け渡しを `storeScopeRaw = storeScope.jsonValue`
- `JudgmentEngineTest.kt:751,758` `storeScope = "external"` → `storeScope = StoreScope.EXTERNAL`(managed 側も同様)
- `JudgmentEngineTest.kt:2082,2917`(生値バリデーション)`c.storeScope in validScopes` → `c.storeScopeRaw in validScopes`(JSON 生値の検査という意図を維持)
- `JudgmentEngineTest.kt:2356,2366` `c.storeScope == "managed"` → `c.storeScope == StoreScope.MANAGED`(external も同様)
- `JudgmentEngineTest.kt:2449` `when (c.storeScope)` の分岐 `"managed"` / `"external"` → `StoreScope.MANAGED` / `StoreScope.EXTERNAL`
- `BannerTest.kt:100` `storeScope = "managed",` → `storeScopeRaw = "managed",`(または既定値なら行削除)
- `CustomCampaignTest.kt:154,166` `assertEquals("external", campaign.storeScope)` → `assertEquals(StoreScope.EXTERNAL, campaign.storeScope)`(managed も同様)

- [x] **Step 5: 全テスト実行**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS(CampaignModelTest 含む全件)

---

### Task 2: Attribution sealed interface + Campaign / CustomPayment の導出プロパティ

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/Models.kt`(StoreScope の隣)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/SettingsRepository.kt:75-82`(CustomPayment)
- Test: `app/src/test/java/com/ktakjm/poikatsu/CampaignModelTest.kt`(追記)

**Interfaces:**
- Consumes: Task 1 の Campaign(storeScopeRaw 改名済み)
- Produces: `sealed interface Attribution { data class Card(val id: String); data class Brand(val name: String); data class Qr(val id: String); data class Program(val id: String) }`、`Campaign.attribution: Attribution?`、`CustomPayment.attribution: Attribution?`(拡張プロパティ)

- [x] **Step 1: 失敗するテストを書く**

CampaignModelTest.kt に追記(import に `com.ktakjm.poikatsu.data.Attribution`, `com.ktakjm.poikatsu.data.CustomPayment`, `com.ktakjm.poikatsu.data.attribution`, `org.junit.Assert.assertNull` を追加):

```kotlin
    private fun campaign(
        cardId: String? = null,
        cardBrand: String? = null,
        paymentMethodId: String? = null,
        pointProgramId: String? = null,
    ) = Campaign(
        id = "c1", operator = "op", name = "n", verifiedDate = "2026-01-01",
        cardId = cardId, cardBrand = cardBrand,
        paymentMethodId = paymentMethodId, pointProgramId = pointProgramId,
    )

    @Test
    fun `attribution_帰属4種がそれぞれ対応するAttributionになる`() {
        assertEquals(Attribution.Card("epos"), campaign(cardId = "epos").attribution)
        assertEquals(Attribution.Brand("Amex"), campaign(cardBrand = "Amex").attribution)
        assertEquals(Attribution.Qr("paypay"), campaign(paymentMethodId = "paypay").attribution)
        assertEquals(Attribution.Program("dpoint"), campaign(pointProgramId = "dpoint").attribution)
    }

    @Test
    fun `attribution_帰属なしはnull`() {
        assertNull(campaign().attribution)
    }

    @Test
    fun `CustomPaymentのattributionも同じ型に写る`() {
        assertEquals(Attribution.Card("epos"), CustomPayment(cardId = "epos").attribution)
        assertEquals(Attribution.Qr("paypay"), CustomPayment(qrPaymentId = "paypay").attribution)
        assertEquals(Attribution.Brand("Visa"), CustomPayment(cardBrand = "Visa").attribution)
        assertNull(CustomPayment().attribution)
    }
```

- [x] **Step 2: テストが失敗することを確認**

Run: `./gradlew :app:testDebugUnitTest --tests com.ktakjm.poikatsu.CampaignModelTest`
Expected: コンパイルエラー(unresolved reference: Attribution)

- [x] **Step 3: Models.kt / SettingsRepository.kt に実装**

Models.kt(StoreScope の隣)に追加:

```kotlin
/**
 * 施策の帰属先(#86)。card_id / card_brand / payment_method_id / point_program_id は
 * 「ちょうど1つ non-null」の排他(整合性テストで強制)で、その和を型で表す。
 * 分岐はこの型への when で書き、網羅チェックを効かせる。JSON スキーマ側は従来の
 * 4 nullable フィールドのまま(パース後の導出のみ)。
 */
sealed interface Attribution {
    /** カード帰属(payment_methods.json cards.id)。card_program / promotion */
    data class Card(val id: String) : Attribution

    /** 国際ブランド帰属(イシュアー不問。例: Amex 30% OFF) */
    data class Brand(val name: String) : Attribution

    /** QR 決済帰属(qr_payments.id) */
    data class Qr(val id: String) : Attribution

    /** ポイントプログラム帰属(point_currencies.id。提示型 #39) */
    data class Program(val id: String) : Attribution
}
```

Campaign のボディ(Task 1 で作成済み)に追加:

```kotlin
    /** 帰属先の導出(#86)。排他が壊れたデータでは cardId → cardBrand → paymentMethodId → pointProgramId の優先順 */
    val attribution: Attribution?
        get() = when {
            cardId != null -> Attribution.Card(cardId)
            cardBrand != null -> Attribution.Brand(cardBrand)
            paymentMethodId != null -> Attribution.Qr(paymentMethodId)
            pointProgramId != null -> Attribution.Program(pointProgramId)
            else -> null
        }
```

SettingsRepository.kt の CustomPayment 定義直後に追加:

```kotlin
/** カスタム決済手段の帰属先(#86)。campaigns.json 側と同じ [Attribution] に写して分岐を共用する */
val CustomPayment.attribution: Attribution?
    get() = when {
        cardId != null -> Attribution.Card(cardId)
        qrPaymentId != null -> Attribution.Qr(qrPaymentId)
        cardBrand != null -> Attribution.Brand(cardBrand)
        else -> null
    }
```

- [x] **Step 4: テスト実行**

Run: `./gradlew :app:testDebugUnitTest --tests com.ktakjm.poikatsu.CampaignModelTest`
Expected: PASS

---

### Task 3: domain 層の帰属分岐を Attribution に置換

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/data/Models.kt`(brandColorOf 629-638)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/JudgmentEngine.kt`(payoutCurrency 228-241・activeManagedMerchantIds 701・resolveCard 813-818・judgeCards 894・judgeQr 940・judgePrograms 979)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/NotificationPlanner.kt:81-94`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/CampaignGrouping.kt:22-31`

**Interfaces:**
- Consumes: `Campaign.attribution: Attribution?`(Task 2)
- Produces: なし(既存シグネチャ不変。外部挙動不変)

- [x] **Step 1: Models.brandColorOf を when(attribution) に置換**

```kotlin
    fun brandColorOf(campaign: Campaign): String? = when (val a = campaign.attribution) {
        is Attribution.Card -> cards.firstOrNull { it.id == a.id }?.brandColor
        is Attribution.Brand -> cardBrands.firstOrNull { it.name.equals(a.name, ignoreCase = true) }?.color
        is Attribution.Qr -> qrPayments.firstOrNull { it.id == a.id }?.brandColor
        is Attribution.Program -> pointCurrencies.firstOrNull { it.id == a.id }?.brandColor
        null -> null
    }
```

- [x] **Step 2: JudgmentEngine の分岐を置換**

resolveCard(813-818):

```kotlin
    private fun resolveCard(campaign: Campaign): PaymentCard? = when (val a = campaign.attribution) {
        is Attribution.Card -> data.cards.firstOrNull { it.id == a.id }
        is Attribution.Brand -> data.cards.firstOrNull { it.brand.equals(a.name, ignoreCase = true) }
        else -> null
    }
```

payoutCurrency(228-241)の id 解決チェーン(優先順の KDoc は現状のまま活きる):

```kotlin
    val id = campaign.pointCurrencyId
        ?: when (val a = campaign.attribution) {
            is Attribution.Program -> a.id
            is Attribution.Card -> card?.pointCurrencyId
            is Attribution.Qr -> qr?.pointCurrencyId
            is Attribution.Brand, null -> null
        }
        ?: return null
```

activeManagedMerchantIds(701): `.filter { it.paymentMethodId != null || resolveCard(it) != null }` → `.filter { it.attribution is Attribution.Qr || resolveCard(it) != null }`

judgeCards(894): `.filter { it.paymentMethodId == null }` → `.filter { it.attribution !is Attribution.Qr }`

judgeQr(940): `.filter { it.paymentMethodId != null && it.paymentMethodId in enabledQrIds }` → `.filter { val a = it.attribution; a is Attribution.Qr && a.id in enabledQrIds }`

judgePrograms(979): `.filter { it.pointProgramId != null && it.pointProgramId in memberships }` → `.filter { val a = it.attribution; a is Attribution.Program && a.id in memberships }`

(judgeQr/judgePrograms の本文内 `qrPaymentMap[campaign.paymentMethodId]` 等の単純参照はフィルタ通過後で分岐でないため raw のまま)

- [x] **Step 3: NotificationPlanner.backedByUserPayments を when(attribution) に置換**

```kotlin
/** 施策の紐付け先(決済手段・プログラム会員 #39)をユーザーが持っているか(resolveCard / judgeQr / judgePrograms のフィルタと同じ基準) */
private fun backedByUserPayments(
    campaign: Campaign,
    ownedCards: List<PaymentCard>,
    enabledQrIds: Set<String>,
    memberships: Set<String>,
): Boolean = when (val a = campaign.attribution) {
    is Attribution.Qr -> a.id in enabledQrIds
    is Attribution.Card -> ownedCards.any { it.id == a.id }
    is Attribution.Brand -> ownedCards.any { it.brand.equals(a.name, ignoreCase = true) }
    is Attribution.Program -> a.id in memberships
    null -> true // 決済手段の紐付けが無い施策は決済側の条件では絞らない
}
```

- [x] **Step 4: CampaignGrouping の cardProgram / pointProgram キー分岐を置換**

```kotlin
fun campaignGroupKey(campaign: Campaign): String {
    val attribution = campaign.attribution
    return when {
        campaign.campaignType == CampaignType.MUNICIPAL -> "municipal:" + (campaign.region?.name ?: campaign.id)
        campaign.isCustom -> customCampaignBaseId(campaign.id)
        campaign.campaignType == CampaignType.CARD_PROGRAM && attribution is Attribution.Card && !campaign.isTimeLimited ->
            "cardProgram:" + attribution.id
        // 常設のプログラム提示施策(#39)もプログラム単位に束ねる(発行体単位の束ねと同じ理由)
        campaign.campaignType == CampaignType.CARD_PROGRAM && attribution is Attribution.Program && !campaign.isTimeLimited ->
            "pointProgram:" + attribution.id
        else -> campaign.id
    }
}
```

(import に `com.ktakjm.poikatsu.data.Attribution` を追加)

- [x] **Step 5: 全テスト実行**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS(既存テストが外部挙動不変のガード)

---

### Task 4: ui / merge 層の帰属分岐を Attribution に置換

**Files:**
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/UiHelpers.kt`(isCardProgramBundle 1053-1062)
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/CustomCampaignEditor.kt:80-102`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/ui/MainViewModel.kt:939,960-961,984,988,1023`
- Modify: `app/src/main/java/com/ktakjm/poikatsu/domain/UserDataMerge.kt:152-154`

**Interfaces:**
- Consumes: `Campaign.attribution` / `CustomPayment.attribution`(Task 2)
- Produces: なし(既存シグネチャ不変)

- [x] **Step 1: UiHelpers.isCardProgramBundle を「帰属の同一性」で書き直す**

同一 card_id(または point_program_id)で束ねる判定は「全施策の attribution が同一の Card / Program」と同値(data class の等価性を利用):

```kotlin
internal fun isCardProgramBundle(campaigns: List<Campaign>): Boolean {
    if (campaigns.size < 2 || campaigns.any { it.campaignType != CampaignType.CARD_PROGRAM }) return false
    val attribution = campaigns.mapTo(HashSet()) { it.attribution }.singleOrNull()
    return attribution is Attribution.Card || attribution is Attribution.Program
}
```

- [x] **Step 2: CustomCampaignEditor の同一 when 2 つを 1 つの共有キーに統一**

PaymentOptionUi.key(88-93)と CustomPayment.optionKey(98-102)を、Attribution 上の 1 関数へ寄せる:

```kotlin
/** 選択状態の同一性キー(PaymentOptionUi と CustomPayment の相互照合用)。null は旧 else 分岐("brand:null")と同値 */
private fun Attribution?.selectionKey(): String = when (this) {
    is Attribution.Card -> "card:$id"
    is Attribution.Qr -> "qr:$id"
    is Attribution.Brand -> "brand:$name"
    is Attribution.Program -> "program:$id" // CustomPayment には現れない(網羅性のためだけの分岐)
    null -> "brand:null"
}
```

PaymentOptionUi 側は `val key: String get() = toPayment().attribution.selectionKey()`、CustomPayment 側の `optionKey()` は削除して呼び出し箇所(選択照合)を `payment.attribution.selectionKey()` に置換。

- [x] **Step 3: MainViewModel の cardId / cardBrand 分岐を置換**

- 939 `val resolved = if (c.cardId != null) resolveCardCampaignRate(c, newOwnedCardsById[c.cardId]) else null` → `val resolved = (c.attribution as? Attribution.Card)?.let { resolveCardCampaignRate(c, newOwnedCardsById[it.id]) }`
- 960-961 も同型(第3引数 `r.rateOverride` 付き)で同様に置換
- 984 `any { it.cardBrand != null }` → `any { it.attribution is Attribution.Brand }`
- 988 `filter { it.cardId == card.id }` → `filter { it.attribution == Attribution.Card(card.id) }`
- 1023 `campaigns.mapNotNull { it.cardBrand }` → `campaigns.mapNotNull { (it.attribution as? Attribution.Brand)?.name }`

- [x] **Step 4: UserDataMerge.operatorFor を when(attribution) に置換**

```kotlin
    val operatorFor = { p: CustomPayment ->
        when (val a = p.attribution) {
            is Attribution.Brand -> a.name
            is Attribution.Card -> paymentNames[a.id] ?: "カスタム"
            is Attribution.Qr -> paymentNames[a.id] ?: "カスタム"
            else -> "カスタム"
        }
    }
```

- [x] **Step 5: 全テスト実行**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS

---

### Task 5: 最終検証

- [x] **Step 1: フルビルド確認**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL・テスト全件 PASS

- [x] **Step 2: 置換漏れの確認**

Run: `grep -rn '"managed"\|"external"' app/src/main/java --include='*.kt'`
Expected: ヒットは StoreScope enum 定義(jsonValue)と CustomCampaigns の `.jsonValue` 生成箇所のみ(あるいはゼロ)

Run: `grep -rn 'cardId != null ->\|cardBrand != null ->\|paymentMethodId != null ->\|pointProgramId != null ->' app/src/main/java --include='*.kt'`
Expected: ヒットは Models.kt / SettingsRepository.kt の attribution 導出のみ

- [x] **Step 3: ユーザーへ報告**

変更サマリと実機検証の依頼(同梱データ経路のため push 不要)。コミットはユーザーの指示を待つ。
