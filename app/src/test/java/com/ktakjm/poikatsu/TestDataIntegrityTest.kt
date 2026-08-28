package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_PERIOD_TOTAL
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_TRANSACTION
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.effectiveValueRate
import com.ktakjm.poikatsu.domain.mergeUserData
import com.ktakjm.poikatsu.domain.payoutCurrency
import com.ktakjm.poikatsu.domain.resolveCardCampaignRate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * data-test/ のショーケースデータの整合性テスト。
 * 実データ(JudgmentEngineRealDataTest)と同じ検証を通し、スキーマ変更で腐るのを CI で防ぐ。
 */
class TestDataIntegrityTest {

    private val fixture = RealData.test
    private val data get() = fixture.data
    private val campaignsRaw get() = fixture.campaignsRaw

    @Test
    fun `テストデータ_記述形の規約_payment_variants_operator省略_null不在`() {
        assertCampaignAuthoringRules("テストデータ", fixture)
    }

    /**
     * 2 手段の payment_variants ショーケース(#89): 共通項の継承・サービス既定の補完(payment_instruction
     * は空なら既定、ineligible_notes は末尾に連結)・variant 側の上書きが展開後の Campaign に出ること。
     */
    @Test
    fun `テストデータ_payment_variantsが2手段に展開され既定と上書きが効く`() {
        val byId = data.campaigns.associateBy { it.id }
        val paypay = byId.getValue("test_municipal_test_paypay")
        val aupay = byId.getValue("test_municipal_test_aupay")
        assertEquals(paypay.name, aupay.name)
        assertEquals(paypay.rateBase, aupay.rateBase)
        assertEquals("test_paypay", paypay.paymentMethodId)
        assertEquals("test_aupay", aupay.paymentMethodId)
        // 上書き: PayPay 側は施策固有の文言、au PAY 側はサービス既定を継承
        assertEquals("テストPayPay残高・テストPayPayクレジット・テストPayPayポイントで支払う", paypay.paymentInstruction)
        assertEquals("テストauPAY残高で支払う", aupay.paymentInstruction)
        // 連結: 共通 notes → variant 差分 → サービス既定
        assertEquals(
            listOf(
                "中小企業・小規模事業者の店舗以外は対象外",
                "大手チェーン店は対象外",
                "テストPayPay商品券での支払いは対象外",
                "テストPayPayクレジット以外のクレジットカードの併用は対象外",
            ),
            paypay.ineligibleNotes,
        )
        assertEquals(listOf("中小企業・小規模事業者の店舗以外は対象外", "大手チェーン店は対象外"), aupay.ineligibleNotes)
        assertEquals("test_balance", aupay.pointCurrencyId)
        assertEquals("テストPayPay", paypay.operator)
        assertEquals("テストauPAY", aupay.operator)
    }

    @Test
    fun `テストデータ_パースに成功する`() {
        assertTrue("merchants が空", data.merchants.isNotEmpty())
        assertTrue("campaigns が空", data.campaigns.isNotEmpty())
        assertTrue("cards が空", data.cards.isNotEmpty())
        assertTrue("card_brands が空", data.cardBrands.isNotEmpty())
    }

    /**
     * managed な QR promotion が、対象チェーンの判定に率と最低購入額つきで出ること。
     * 旧「実データ_楽天ペイ松屋プロモーションのQR判定」を data-test へ移した(#83)。
     * 実データ側は期間終了した施策を 30 日で削除する運用なので、特定の施策を名指しする
     * テストは削除のたびに落ちる。ショーケースは常時安定な data-test 側で固定する。
     */
    @Test
    fun `テストデータ_managedなQR施策が対象チェーンの判定に率と最低購入額つきで出る`() {
        val engine = JudgmentEngine(data)
        val drugstore = data.merchants.first { it.id == "test_drugstore" }
        val judgments = engine.judgeQr(drugstore, LocalDate.of(2026, 7, 10), setOf("test_paypay"))
        val judgment = judgments.first { it.campaign.id == "test_product_scope" }
        assertEquals("test_paypay", judgment.campaign.paymentMethodId)
        assertEquals(30.0, judgment.effectiveRate!!, 0.001)
        assertEquals(3000, judgment.minPurchase)
    }

    @Test
    fun `テストデータ_同じQRの2施策が倍率で片方だけ動くショーケースが成立している`() {
        // #83 のショーケース: test_aupay は既定で test_exchange(選択式倍率)を稼ぐが、
        // 広島市施策だけは test_balance(円建て)を明示している。倍率 ON で前者の実質率だけが
        // 動くことが実機で確認できる状態を CI で守る
        val currencies = mergeUserData(
            base = data,
            cardOverrides = emptyMap(),
            ownedBrands = emptySet(),
            customCards = emptyList(),
            customCampaigns = emptyList(),
            enabledPointMultipliers = setOf("test_exchange"),
            pointMultiplierFactors = mapOf("test_exchange" to 1.5),
        ).engineData.pointCurrencies
        val qr = data.qrPayments.first { it.id == "test_aupay" }
        val byId = data.campaigns.associateBy { it.id }
        val exchange = byId.getValue("test_exchange_rebate")
        val balance = byId.getValue("test_municipal_hiroshima_b_test_aupay")

        assertEquals(15.0, effectiveValueRate(balance.rateBase, payoutCurrency(balance, currencies, null, qr))!!, 1e-9)
        assertEquals(15.0, effectiveValueRate(exchange.rateBase, payoutCurrency(exchange, currencies, null, qr))!!, 1e-9)
        // 同じ 15% でも成り立ちが違う: 残高は名目そのまま、交換所は 10% × 1.5
        assertEquals(10.0, exchange.rateBase!!, 0.0)
    }

    @Test
    fun `テストデータ_倍率グループのショーケースが成立している`() {
        // #84 のショーケース: test_point と test_waon が同一グループの倍率(ウエル活相当)を持ち、
        // 設定画面でどちらのチェックを入れても両方の倍率が連動して有効になることを実機で
        // 確認できる状態を CI で守る(定義の完全一致は実データと同じ整合性ルール)
        val groups = data.pointCurrencies
            .filter { it.pointMultiplier?.group != null }
            .groupBy { it.pointMultiplier!!.group }
        assertTrue("倍率グループのショーケース(同一グループの2通貨)が data-test に無い", groups.isNotEmpty())
        groups.forEach { (group, members) ->
            assertTrue("グループ '$group' は2通貨以上で使う", members.size >= 2)
            assertEquals(
                "グループ '$group' の倍率定義は全通貨で完全一致させる: ${members.map { it.id }}",
                1,
                members.map { it.pointMultiplier }.distinct().size,
            )
        }
        // 片方の id だけ有効化しても全員有効になる(マージのグループ連動)
        val (group, members) = groups.entries.first()
        val merged = mergeUserData(
            base = data,
            cardOverrides = emptyMap(),
            ownedBrands = emptySet(),
            customCards = emptyList(),
            customCampaigns = emptyList(),
            enabledPointMultipliers = setOf(members.first().id),
        ).engineData.pointCurrencies
        members.forEach { member ->
            assertTrue(
                "グループ '$group' の ${member.id} が連動して有効になっていない",
                merged.first { it.id == member.id }.multiplierEnabled,
            )
        }
    }

    @Test
    fun `テストデータ_merchant_rulesの参照切れがない`() {
        val ids = data.merchants.map { it.id }.toSet()
        val broken = data.campaigns.flatMap { c -> c.merchantRules.map { c.id to it.merchantId } }
            .filter { (_, mid) -> mid !in ids }
        assertEquals(emptyList<Pair<String, String>>(), broken)
    }

    @Test
    fun `テストデータ_施策の帰属の参照と排他が正しい`() {
        val cardIds = data.cards.map { it.id }.toSet()
        val qrIds = data.qrPayments.map { it.id }.toSet()
        val currencyIds = data.pointCurrencies.map { it.id }.toSet()
        data.campaigns.forEach { c ->
            val owners = listOfNotNull(c.cardId, c.cardBrand, c.paymentMethodId, c.pointProgramId)
            assertEquals(
                "${c.id}: card_id(${c.cardId}) / card_brand(${c.cardBrand}) / " +
                    "payment_method_id(${c.paymentMethodId}) / point_program_id(${c.pointProgramId}) は" +
                    "ちょうど1つが non-null",
                1,
                owners.size,
            )
            c.cardId?.let { assertTrue("${c.id}: card_id '$it' が cards に無い", it in cardIds) }
            c.cardBrand?.let { brand ->
                assertTrue(
                    "${c.id}: card_brand '$brand' がカタログの card_brands に無い(設定画面で登録できない)",
                    data.cardBrands.any { it.name.equals(brand, ignoreCase = true) },
                )
            }
            c.paymentMethodId?.let { assertTrue("${c.id}: payment_method_id '$it' が qr_payments に無い", it in qrIds) }
            c.pointProgramId?.let {
                assertTrue("${c.id}: point_program_id '$it' が point_currencies に無い", it in currencyIds)
                assertTrue("${c.id}: point_program_id 指定の施策は presentation_only: true が必須", c.presentationOnly)
            }
            c.pointCurrencyId?.let {
                assertTrue("${c.id}: point_currency_id '$it' が point_currencies に無い", it in currencyIds)
            }
        }
        (data.cards.mapNotNull { it.pointCurrencyId } + data.qrPayments.mapNotNull { it.pointCurrencyId })
            .forEach { cur ->
                assertTrue("point_currency_id '$cur' が point_currencies に無い", cur in currencyIds)
            }
    }

    @Test
    fun `テストデータ_merchant_rulesのineligible_brandsがカタログのcard_brandsを参照している`() {
        data.campaigns.forEach { c ->
            c.merchantRules.flatMap { it.ineligibleBrands }.forEach { brand ->
                assertTrue(
                    "${c.id}: ineligible_brands '$brand' がカタログの card_brands に無い(typo だと除外が効かない)",
                    data.cardBrands.any { it.name.equals(brand, ignoreCase = true) },
                )
            }
        }
    }

    @Test
    fun `テストデータ_rate_baseとdiscount_amountはちょうど一方がnon-null`() {
        data.campaigns.forEach { c ->
            val hasRate = c.rateBase != null
            val hasDiscount = c.discountAmount != null
            if (BenefitType.fromString(c.benefitType) == BenefitType.LOTTERY) {
                assertTrue("${c.id}: lottery は rate_base / discount_amount を持たない", !hasRate && !hasDiscount)
            } else {
                assertTrue(
                    "${c.id}: rate_base(${c.rateBase}) と discount_amount(${c.discountAmount}) はちょうど一方が non-null",
                    hasRate xor hasDiscount,
                )
            }
        }
    }

    @Test
    fun `テストデータ_recurrenceはdays_of_weekかdays_of_monthのどちらか一方`() {
        val validDays = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        data.campaigns.forEach { c ->
            val r = c.recurrence ?: return@forEach
            assertTrue(
                "${c.id}: days_of_week と days_of_month はどちらか一方だけ指定する",
                r.daysOfWeek.isEmpty() xor r.daysOfMonth.isEmpty(),
            )
            r.daysOfWeek.forEach { d -> assertTrue("${c.id}: invalid day_of_week '$d'", d in validDays) }
            r.daysOfMonth.forEach { d -> assertTrue("${c.id}: invalid day_of_month $d", d in 1..31) }
        }
    }

    @Test
    fun `テストデータ_walletsの値が既知でeligibleとineligibleが重複しない`() {
        val known = setOf("apple_pay", "google_pay")
        data.campaigns.forEach { c ->
            (c.eligibleWallets + c.ineligibleWallets).forEach { w ->
                assertTrue("${c.id}: unknown wallet '$w'", w in known)
            }
            val overlap = c.eligibleWallets.intersect(c.ineligibleWallets.toSet())
            assertTrue("${c.id}: eligible/ineligible が重複 $overlap", overlap.isEmpty())
        }
    }

    @Test
    fun `テストデータ_旧スキーマのキーが残っていない`() {
        listOf("\"note\":", "\"exclusion_note\":", "\"conditions\":").forEach { key ->
            assertTrue("旧スキーマのキー $key が残っている", key !in campaignsRaw)
        }
    }

    @Test
    fun `テストデータ_payment_instructionが空でない`() {
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: payment_instruction が空", c.paymentInstruction.isNotBlank())
        }
    }

    @Test
    fun `テストデータ_両階層のnotes併用ショーケースを含む`() {
        // campaign 直下(施策全体)と merchant_rules(店舗固有)の対象/対象外がレベル横断で連結される
        // パターン(SMCC/MUFG 相当)を data-test でも検証できること
        val both = data.campaigns.filter { c ->
            (c.eligibleNotes.isNotEmpty() || c.ineligibleNotes.isNotEmpty()) &&
                c.merchantRules.any { it.eligibleNotes.isNotEmpty() || it.ineligibleNotes.isNotEmpty() }
        }
        assertTrue("両階層併用のショーケース施策が存在する", both.isNotEmpty())
        // memo(非表示)のショーケースも維持する
        assertTrue("memo を持つショーケース施策が存在する", data.campaigns.any { it.memo.isNotEmpty() })
    }

    @Test
    fun `テストデータ_rate_rulesの段階制パターンを含み整合している`() {
        val tiered = data.campaigns.filter { it.rateRules.isNotEmpty() }
        assertTrue("段階制(rate_rules)のショーケース施策が存在する", tiered.isNotEmpty())
        tiered.forEach { c ->
            c.rateRules.forEach { r ->
                assertTrue("${c.id}: rate_rules の condition が空", r.condition.isNotBlank())
                assertTrue("${c.id}: rate_rules の rate($r) は正の値", r.rate > 0)
            }
            assertEquals(
                "${c.id}: rate_base(${c.rateBase}) は rate_rules の最大値であること",
                c.rateRules.maxOf { it.rate },
                c.rateBase,
            )
        }
    }

    @Test
    fun `テストデータ_product_scopeのショーケースを含み整合している`() {
        val validScopes = setOf(MIN_PURCHASE_SCOPE_TRANSACTION, MIN_PURCHASE_SCOPE_PERIOD_TOTAL)
        data.campaigns.forEach { c ->
            assertTrue(
                "${c.id}: invalid min_purchase_scope '${c.minPurchaseScope}'",
                c.minPurchaseScope in validScopes,
            )
            if (c.minPurchaseScope != MIN_PURCHASE_SCOPE_TRANSACTION) {
                assertNotNull("${c.id}: min_purchase_scope を指定するなら min_purchase が必要", c.minPurchase)
            }
            c.productScope?.let {
                assertTrue("${c.id}: product_scope の label が空", it.label.isNotBlank())
            }
        }
        // ショーケース(対象商品限定 + 期間累計の最低購入額 + 要エントリー)が揃っていること
        val showcase = data.campaigns.first { it.id == "test_product_scope" }
        assertNotNull("product_scope ショーケースが必要", showcase.productScope)
        assertEquals(MIN_PURCHASE_SCOPE_PERIOD_TOTAL, showcase.minPurchaseScope)
        assertNotNull(showcase.minPurchase)
        assertTrue("requires_entry のショーケースが必要", showcase.requiresEntry)
    }

    @Test
    fun `テストデータ_presentation_onlyのショーケースを含む`() {
        val showcase = data.campaigns.first { it.id == "test_presentation_only" }
        assertTrue("presentation_only ショーケースが必要", showcase.presentationOnly)
        // エポス優待相当(常設 card_program のカード現物提示型)を再現する:
        // resolveCardCampaignRate の提示分岐(カードの通常率でなく施策側の率)を実機で確認できる形
        assertEquals(CampaignType.CARD_PROGRAM, showcase.campaignType)
        assertNull("常設(期間なし)で安定させる", showcase.periodEnd)
        assertNotNull("提示特典の率が必要(定率でないと率分岐を検証できない)", showcase.rateBase)
    }

    @Test
    fun `テストデータ_ポイント通貨とプログラム提示のショーケースを含む`() {
        // ウエル活相当(倍率付き通貨)と会員プログラム(dポイント特約店の提示分相当)を実機で確認できる形(#39)
        assertTrue(
            "倍率付き通貨のショーケースが必要(設定画面のポイント倍率チェック・倍率適用の実機確認用)",
            data.pointCurrencies.any { it.pointMultiplier != null },
        )
        assertTrue(
            "会員プログラムのショーケースが必要(設定画面の会員チェックの実機確認用)",
            data.pointCurrencies.any { it.membershipProgram },
        )
        assertTrue(
            "倍率付き通貨を稼ぐカードが必要(実効率×倍率の実機確認用)",
            data.cards.any { card ->
                data.pointCurrencies.any { it.id == card.pointCurrencyId && it.pointMultiplier != null }
            },
        )
        val program = data.campaigns.first { it.pointProgramId != null }
        assertTrue("プログラム提示ショーケースは presentation_only", program.presentationOnly)
        assertNull("常設(期間なし)で安定させる", program.periodEnd)
        assertNotNull("提示特典の率が必要(並記枠の率表示の実機確認用)", program.rateBase)
    }

    @Test
    fun `テストデータ_1pt価値は通貨単位で定義されカード側にpoint_valueは無い`() {
        // #13: 実データと同型(test_card_jcb → test_jpoint)
        val jcb = data.cards.first { it.id == "test_card_jcb" }
        assertEquals("test_jpoint", jcb.pointCurrencyId)
        val jpoint = data.pointCurrencies.first { it.id == "test_jpoint" }
        assertNotNull(jpoint.pointValueConfig)
        assertEquals(1.0, jpoint.pointValueConfig!!.default, 0.0)
        // カード側の旧キーはモデルから消えた分 ignoreUnknownKeys で黙って捨てられるため構造で検出する
        val root = kotlinx.serialization.json.Json.parseToJsonElement(fixture.paymentMethodsRaw).jsonObject
        root.getValue("cards").jsonArray.forEach { card ->
            val obj = card.jsonObject
            assertTrue(
                "cards '${obj["id"]}': カード単位の point_value が残っている(point_currencies へ移す)",
                "point_value" !in obj,
            )
        }
    }

    @Test
    fun `テストデータ_display_nameのショーケースを含み空白でない`() {
        data.campaigns.forEach { c ->
            c.displayName?.let { dn ->
                assertTrue("${c.id}: display_name が空文字・空白", dn.isNotBlank())
                assertTrue(
                    "${c.id}: municipal は display_name を持たせない",
                    c.campaignType != CampaignType.MUNICIPAL,
                )
            }
        }
        // 多チェーン + display_name のショーケース(カードタイトルの手動略記)が揃っていること
        val showcase = data.campaigns.first { it.id == "test_product_scope" }
        assertNotNull("display_name ショーケースが必要", showcase.displayName)
        assertTrue(
            "display_name ショーケースは多チェーン施策であること",
            showcase.merchantRules.map { it.merchantId }.distinct().size >= 2,
        )
    }

    @Test
    fun `テストデータ_type_benefitType_storeScopeが有効な値`() {
        val validTypes = CampaignType.entries.map { it.jsonValue }.toSet()
        val validBenefitTypes = BenefitType.entries.map { it.jsonValue }.toSet()
        val validScopes = setOf("managed", "external")
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: invalid type '${c.type}'", c.type in validTypes)
            assertTrue("${c.id}: invalid benefitType '${c.benefitType}'", c.benefitType in validBenefitTypes)
            assertTrue("${c.id}: invalid storeScope '${c.storeScopeRaw}'", c.storeScopeRaw in validScopes)
        }
    }
}
