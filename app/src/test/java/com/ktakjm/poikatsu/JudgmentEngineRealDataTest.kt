package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_PERIOD_TOTAL
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_TRANSACTION
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.StoreScope
import com.ktakjm.poikatsu.domain.AppLink
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.domain.WALLET_APP_LABEL
import com.ktakjm.poikatsu.domain.WALLET_APP_PACKAGE
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.payoutCurrency
import com.ktakjm.poikatsu.domain.resolveCardCampaignRate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * リポジトリ直下 data/ の実データを読み込み、パース成功・構造整合性・
 * 施策固有の振る舞いを検証する。ロジック自体の網羅は JudgmentEngineTest で行う。
 */
class JudgmentEngineRealDataTest {

    private val fixture = RealData.production
    private val data get() = fixture.data
    private val campaignsRaw get() = fixture.campaignsRaw

    @Test
    fun `実データ_記述形の規約_payment_variants_operator省略_null不在`() {
        assertCampaignAuthoringRules("実データ", fixture)
    }

    @Test
    fun `実データ_自治体施策はサービス既定の文言が補われている`() {
        // 既定を持つサービス(PayPay・楽天ペイ)の自治体施策は、施策側に書かなくても既定注記を持つ
        val municipal = data.campaigns.filter { it.campaignType == CampaignType.MUNICIPAL }
        assertTrue(municipal.isNotEmpty())
        municipal.forEach { c ->
            val defaults = data.qrPayments.first { it.id == c.paymentMethodId }.municipalDefaults
            defaults?.ineligibleNotes?.forEach { note ->
                assertTrue("${c.id}: 既定注記「$note」が展開後に無い", note in c.ineligibleNotes)
            }
        }
    }
    private val engine = JudgmentEngine(data)
    private val today = LocalDate.of(2026, 6, 28)

    @Test
    fun `実データ_merchant_rulesの参照切れがない`() {
        val ids = data.merchants.map { it.id }.toSet()
        val broken = data.campaigns.flatMap { c -> c.merchantRules.map { c.id to it.merchantId } }
            .filter { (_, mid) -> mid !in ids }
        assertEquals(emptyList<Pair<String, String>>(), broken)
    }

    @Test
    fun `実データ_card_idの参照切れがない`() {
        val cardIds = data.cards.map { it.id }.toSet()
        val broken = data.campaigns.filter { it.cardId != null && it.cardId !in cardIds }.map { it.id }
        assertEquals(emptyList<String>(), broken)
    }

    @Test
    fun `実データ_payment_method_idの参照切れがない`() {
        val qrIds = data.qrPayments.map { it.id }.toSet()
        val broken = data.campaigns.filter { it.paymentMethodId != null && it.paymentMethodId !in qrIds }.map { it.id }
        assertEquals(emptyList<String>(), broken)
    }

    @Test
    fun `実データ_施策の帰属は4種のうちちょうど1つ`() {
        data.campaigns.forEach { c ->
            val owners = listOfNotNull(c.cardId, c.cardBrand, c.paymentMethodId, c.pointProgramId)
            assertEquals(
                "${c.id}: card_id(${c.cardId}) / card_brand(${c.cardBrand}) / " +
                    "payment_method_id(${c.paymentMethodId}) / point_program_id(${c.pointProgramId}) は" +
                    "ちょうど1つが non-null",
                1,
                owners.size,
            )
        }
    }

    @Test
    fun `実データ_ポイント通貨の参照が正しい`() {
        val currencyIds = data.pointCurrencies.map { it.id }.toSet()
        assertTrue("point_currencies が未収録", currencyIds.isNotEmpty())
        data.cards.mapNotNull { c -> c.pointCurrencyId?.let { c.id to it } }.forEach { (cardId, cur) ->
            assertTrue("cards '$cardId': point_currency_id '$cur' が point_currencies に無い", cur in currencyIds)
        }
        data.qrPayments.mapNotNull { q -> q.pointCurrencyId?.let { q.id to it } }.forEach { (qrId, cur) ->
            assertTrue("qr_payments '$qrId': point_currency_id '$cur' が point_currencies に無い", cur in currencyIds)
        }
        data.campaigns.forEach { c ->
            c.pointCurrencyId?.let {
                assertTrue("${c.id}: point_currency_id '$it' が point_currencies に無い", it in currencyIds)
            }
            c.pointProgramId?.let {
                assertTrue("${c.id}: point_program_id '$it' が point_currencies に無い", it in currencyIds)
            }
        }
    }

    @Test
    fun `実データ_倍率のfactorはfactor_optionsの最小値と一致する`() {
        // 未選択時の既定は保守側(#83)。カタログの factor がそのまま既定値になるため、
        // 選択肢を持つ通貨では最小値と一致していないと好条件側に倒れる
        data.pointCurrencies.forEach { cur ->
            val pm = cur.pointMultiplier ?: return@forEach
            if (pm.factorOptions.isEmpty()) return@forEach
            assertTrue("${cur.id}: factor_options は factor を含む必要がある", pm.factor in pm.factorOptions)
            assertEquals(
                "${cur.id}: factor は factor_options の最小値(保守側)にする",
                pm.factorOptions.min(),
                pm.factor,
                0.0,
            )
        }
    }

    @Test
    fun `実データ_au PAY残高還元の施策はaupay_balanceを払い出す`() {
        // au PAY は施策ごとに Ponta ポイントと au PAY残高に分かれる(#83)。残高は円建てで
        // 増価しないため、Ponta のまま放置すると交換所倍率が残高還元にも掛かってしまう
        val currencies = data.pointCurrencies
        val balanceCampaigns = data.campaigns.filter { c ->
            c.paymentMethodId == "aupay" && c.memo.any { it.contains("還元はau PAY残高") }
        }
        assertTrue("au PAY残高還元の施策が実データに無い(検出条件が古い可能性)", balanceCampaigns.isNotEmpty())
        balanceCampaigns.forEach { c ->
            val qr = data.qrPayments.first { it.id == "aupay" }
            assertEquals(
                "${c.id}: memo が au PAY残高還元と言っているので point_currency_id を aupay_balance にする",
                "aupay_balance",
                payoutCurrency(c, currencies, card = null, qr = qr)?.id,
            )
        }
    }

    @Test
    fun `実データ_円建て通貨は倍率も1pt価値の定義も持たない`() {
        // value_fixed は「ユーザーが調整する余地が無い」ことの表明(#83)。増価の定義が
        // 同居すると設定画面に出さない方針と矛盾する
        data.pointCurrencies.filter { it.valueFixed }.forEach { cur ->
            assertNull("${cur.id}: value_fixed の通貨に point_multiplier は持たせない", cur.pointMultiplier)
            assertNull("${cur.id}: value_fixed の通貨に point_value は持たせない", cur.pointValueConfig)
        }
    }

    @Test
    fun `実データ_プログラム帰属の施策はpresentation_only必須`() {
        // point_program_id は提示型専用の帰属(#39)。決済型をプログラムに帰属させると
        // 判定エンジンが「どの支払い方法か」を解決できない
        data.campaigns.filter { it.pointProgramId != null }.forEach { c ->
            assertTrue("${c.id}: point_program_id 指定の施策は presentation_only: true が必須", c.presentationOnly)
        }
    }

    @Test
    fun `実データ_ウエル活の倍率はVポイント通貨に定義されSMCCが稼ぐ`() {
        // 旧 cards[].point_multiplier(#39 で通貨マスタへ正規化)の挙動維持を実データで検証する
        val vpoint = data.pointCurrencies.firstOrNull { it.id == "vpoint" }
        assertNotNull("point_currencies に vpoint が無い", vpoint)
        assertEquals(1.5, vpoint!!.pointMultiplier!!.factor, 0.0)
        assertEquals("vpoint", data.cards.first { it.id == "smcc" }.pointCurrencyId)
    }

    @Test
    fun `実データ_同一グループの倍率定義は完全一致する`() {
        // 倍率グループ(#84)は「同じ事実を複数通貨が持つ」ときの重複を許容する仕組み。
        // 定義がずれると改定時に片方だけ直す事故がそのまま出荷されるため、完全一致を強制する
        data.pointCurrencies
            .filter { it.pointMultiplier?.group != null }
            .groupBy { it.pointMultiplier!!.group }
            .forEach { (group, members) ->
                assertTrue("グループ '$group' は2通貨以上で使う(1通貨ならグループ不要)", members.size >= 2)
                assertEquals(
                    "グループ '$group' の倍率定義は全通貨で完全一致させる: ${members.map { it.id }}",
                    1,
                    members.map { it.pointMultiplier }.distinct().size,
                )
            }
    }

    @Test
    fun `実データ_ウエル活の倍率はWAON POINTとVポイントが同一グループで持つ`() {
        // ウエル活 ×1.5 は WAON POINT の価値特性で、Vポイントは等価交換の連鎖で同じ倍率になる
        // (#84)。両通貨が同一グループで持ち、設定の ON/OFF・倍率改定が連動することを保証する
        val vpoint = data.pointCurrencies.first { it.id == "vpoint" }
        val waon = data.pointCurrencies.firstOrNull { it.id == "waon_point" }
        assertNotNull("point_currencies に waon_point が無い", waon)
        assertNotNull("vpoint のウエル活倍率に group が無い", vpoint.pointMultiplier!!.group)
        assertEquals(vpoint.pointMultiplier!!.group, waon!!.pointMultiplier?.group)
    }

    @Test
    fun `実データ_AEON Pay残高還元の施策はaeon_pay_balanceを払い出す`() {
        // AEON Pay は au PAY と同じ「1決済手段・2通貨」構造(#84)。多数派の残高を
        // qr_payments 側の既定にし、memo が残高還元と言う施策は継承で aeon_pay_balance になる
        val currencies = data.pointCurrencies
        val qr = data.qrPayments.first { it.id == "aeon_pay" }
        val balanceCampaigns = data.campaigns.filter { c ->
            c.paymentMethodId == "aeon_pay" && c.memo.any { it.contains("還元はAEON Pay残高") }
        }
        assertTrue("AEON Pay残高還元の施策が実データに無い(検出条件が古い可能性)", balanceCampaigns.isNotEmpty())
        balanceCampaigns.forEach { c ->
            assertEquals(
                "${c.id}: memo が AEON Pay残高還元と言っているので払い出しは aeon_pay_balance にする",
                "aeon_pay_balance",
                payoutCurrency(c, currencies, card = null, qr = qr)?.id,
            )
        }
    }

    @Test
    fun `実データ_WAON POINT還元のAEON Pay施策はwaon_pointを払い出す`() {
        // 岐阜市だけ WAON POINT 付与(一次情報確認済み 2026-08-22)。既定(aeon_pay_balance)の
        // 例外なので施策側に point_currency_id を明示する(「多数派を既定・例外を明示」の規則。#83/#84)
        val currencies = data.pointCurrencies
        val qr = data.qrPayments.first { it.id == "aeon_pay" }
        val waonCampaigns = data.campaigns.filter { c ->
            c.paymentMethodId == "aeon_pay" && c.memo.any { it.contains("還元はWAON POINT") }
        }
        assertTrue("WAON POINT還元の施策が実データに無い(検出条件が古い可能性)", waonCampaigns.isNotEmpty())
        waonCampaigns.forEach { c ->
            assertEquals(
                "${c.id}: memo が WAON POINT還元と言っているので point_currency_id を waon_point にする",
                "waon_point",
                payoutCurrency(c, currencies, card = null, qr = qr)?.id,
            )
        }
    }

    @Test
    fun `実データ_メルカリポイント還元の施策はmercari_pointを払い出す`() {
        // かなトク等のメルペイ施策はメルカリポイント付与(かなトク公式で確認 2026-08-22)。
        // qr_payments.merpay の既定継承で解決される
        val currencies = data.pointCurrencies
        val qr = data.qrPayments.first { it.id == "merpay" }
        val campaigns = data.campaigns.filter { c ->
            c.paymentMethodId == "merpay" && c.memo.any { it.contains("還元はメルカリポイント") }
        }
        assertTrue("メルカリポイント還元の施策が実データに無い(検出条件が古い可能性)", campaigns.isNotEmpty())
        campaigns.forEach { c ->
            assertEquals(
                "${c.id}: 払い出しは mercari_point(merpay の既定継承)にする",
                "mercari_point",
                payoutCurrency(c, currencies, card = null, qr = qr)?.id,
            )
        }
    }

    @Test
    fun `実データ_三菱UFJカードはグローバルポイントを稼ぐ`() {
        // ポイントアッププログラムの払い出しはグローバルポイント(公式で確認 2026-08-22)。
        // 収録率は 1pt=5円相当の交換先基準で、キャッシュバック等は 3〜5円に変動するため
        // point_value の説明(label/note)を持たせてユーザーが調整できるようにする
        assertEquals("global_point", data.cards.first { it.id == "mufg" }.pointCurrencyId)
        val currency = data.pointCurrencies.firstOrNull { it.id == "global_point" }
        assertNotNull("point_currencies に global_point が無い", currency)
        assertNotNull("global_point は価値が交換先で変動するため point_value の説明が要る", currency!!.pointValueConfig)
    }

    @Test
    fun `実データ_カード直下に旧point_multiplierが残っていない`() {
        // #39 で cards[].point_multiplier → point_currencies[].point_multiplier へ移設。
        // ignoreUnknownKeys のため旧位置のキーはパース時に黙って捨てられる(静かに壊れる)ので構造で検出する
        val root = kotlinx.serialization.json.Json.parseToJsonElement(fixture.paymentMethodsRaw).jsonObject
        root.getValue("cards").jsonArray.forEach { card ->
            val obj = card.jsonObject
            assertTrue(
                "cards '${obj["id"]}': 旧スキーマのキー point_multiplier が残っている(point_currencies へ移す)",
                "point_multiplier" !in obj,
            )
        }
    }

    @Test
    fun `実データ_アカチャンホンポは公式リストで3状態判定できる`() {
        val merchant = data.merchants.first { it.id == "akachan_honpo" }
        assertTrue(engine.canCheckStore(merchant))
        // 公式の対象外店舗(ららぽーとTOKYO-BAY内)→ 対象外
        assertEquals(StoreEligibility.INELIGIBLE, engine.checkStore(merchant, "ららぽーとTOKYO-BAY店").single().eligibility)
        // 公式の対象店舗 → 対象
        assertEquals(StoreEligibility.ELIGIBLE, engine.checkStore(merchant, "アリオ札幌店").single().eligibility)
        // どちらのリストにも無い → 要確認
        assertEquals(StoreEligibility.UNKNOWN, engine.checkStore(merchant, "架空のどこか店").single().eligibility)
    }

    /**
     * コジマ×ビックカメラの網羅リスト施策(#64)。施策が期限切れ削除されたら検証対象なしで抜ける
     * (collect-campaigns の削除運用でテストが壊れないように)。
     */
    @Test
    fun `実データ_コジマの網羅リスト施策は掲載店だけ対象になる`() {
        val merchant = data.merchants.firstOrNull { it.id == "kojima" } ?: return
        val exhaustiveCampaigns = data.campaigns.filter { c ->
            c.merchantRules.any { it.merchantId == "kojima" && it.officialStoreList?.listIsExhaustive == true }
        }
        if (exhaustiveCampaigns.isEmpty()) return
        // 網羅リストだけのチェーンでも「このお店が対象か調べる」導線を出す(#70)
        assertTrue(engine.canCheckStore(merchant))
        // 実 POI 名の照合: 正式名・別名(コジマ単独表記)ともチェーンに一致する。
        // かな3文字キー(こじま)+かな始まり支店名(ららぽーと等)は境界判定の既知の制限で
        // 照合不可のため(#60)、別名の検証は漢字始まりの支店名で行う
        assertEquals("kojima", engine.matchStore("コジマ×ビックカメラ 浦和店")?.merchant?.id)
        assertEquals("kojima", engine.matchStore("コジマ 三鷹店")?.merchant?.id)
        // 首都圏リスト掲載店(浦和)は首都圏施策で対象、千葉施策では掲載なし=対象外
        val urawa = engine.checkStore(merchant, "コジマ×ビックカメラ浦和店")
        assertTrue(
            urawa.filter { it.campaign.id.contains("shutoken") }
                .all { it.eligibility == StoreEligibility.ELIGIBLE },
        )
        assertTrue(
            urawa.filter { it.campaign.id.contains("chiba") }
                .all { it.eligibility == StoreEligibility.INELIGIBLE },
        )
        // どのリストにも無い店舗(首都圏・千葉外)は全施策で対象外(網羅リストの断定)
        val sapporo = engine.checkStore(merchant, "コジマ×ビックカメラ札幌店")
        assertTrue(sapporo.isNotEmpty())
        assertTrue(sapporo.all { it.eligibility == StoreEligibility.INELIGIBLE })
        // 施策単位の間引き: 未掲載店では網羅リスト施策が全部間引かれる
        assertEquals(
            exhaustiveCampaigns.map { it.id }.toSet(),
            engine.exhaustiveListIneligibleCampaignIds(merchant, "コジマ×ビックカメラ札幌店"),
        )
    }

    // ---- 実データの新フィールド検証 ----

    @Test
    fun `実データ_各施策のtype_benefitType_storeScopeが有効な値`() {
        val validTypes = CampaignType.entries.map { it.jsonValue }.toSet()
        val validBenefitTypes = BenefitType.entries.map { it.jsonValue }.toSet()
        val validScopes = setOf("managed", "external")
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: invalid type '${c.type}'", c.type in validTypes)
            assertTrue("${c.id}: invalid benefitType '${c.benefitType}'", c.benefitType in validBenefitTypes)
            assertTrue("${c.id}: invalid storeScope '${c.storeScopeRaw}'", c.storeScopeRaw in validScopes)
        }
    }

    @Test
    fun `実データ_rate_baseとdiscount_amountはちょうど一方がnon-null`() {
        data.campaigns.forEach { c ->
            val hasRate = c.rateBase != null
            val hasDiscount = c.discountAmount != null
            // 抽選は確定特典ではないため率・額を持たない(当選確率・最大額は memo の文章)
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
    fun `実データ_recurrenceはdays_of_weekかdays_of_monthのどちらか一方`() {
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
    fun `実データ_walletsの値が既知でeligibleとineligibleが重複しない`() {
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
    fun `実データ_旧スキーマのキーが残っていない`() {
        // #41 で note/exclusion_note → eligible_notes/ineligible_notes、conditions → memo に改名した。
        // ignoreUnknownKeys のため旧キーはパース時に黙って捨てられる(静かに壊れる)ので生テキストで検出する
        listOf("\"note\":", "\"exclusion_note\":", "\"conditions\":").forEach { key ->
            assertTrue("旧スキーマのキー $key が残っている", key !in campaignsRaw)
        }
    }

    @Test
    fun `実データ_payment_instructionが空でない`() {
        // 支払い手段は必ず明示する(同名ブランドで対象決済手段が別物になり得る: au PAY(QR) と au PAY カード)
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: payment_instruction が空", c.paymentInstruction.isNotBlank())
        }
    }

    @Test
    fun `実データ_notesとmemoの線引きが守られている`() {
        // 線引き: 見落とすと損する言い切りは eligible/ineligible_notes(表示)、memo は非表示の補足のみ。
        // 「反映済み」注記(事実の本体が別フィールドにある印)だけは memo に対象外文言を書いてよい
        data.campaigns.forEach { c ->
            (c.eligibleNotes + c.ineligibleNotes + c.overviewIneligibleNotes + c.memo).forEach { n ->
                assertTrue("${c.id}: 空白の note がある", n.isNotBlank())
            }
            c.merchantRules.forEach { r ->
                (r.eligibleNotes + r.ineligibleNotes).forEach { n ->
                    assertTrue("${c.id}/${r.merchantId}: 空白の note がある", n.isNotBlank())
                }
            }
            c.memo.forEach { m ->
                if ("反映済み" in m) return@forEach
                assertTrue(
                    "${c.id}: memo に対象外/のみ対象の言い切りが残っている(表示フィールドへ移す): $m",
                    "対象外" !in m && "のみ対象" !in m,
                )
            }
        }
    }

    @Test
    fun `実データ_三井住友はウォレット起動リンク_MUFGはGoogle Pay警告`() {
        val merchant = data.merchants.first { it.id == "seven_eleven" }
        val judgments = engine.judgeCards(merchant, LocalDate.of(2026, 7, 8))
        val smcc = judgments.first { it.campaign.id == "smcc_combini_restaurant" }
        assertEquals(listOf(AppLink(WALLET_APP_PACKAGE, WALLET_APP_LABEL)), smcc.appLinks)
        val mufg = judgments.first { it.campaign.id == "mufg_point_up_program" }
        assertTrue(mufg.appLinks.isEmpty())
        // MUFG は apple_pay が eligible なので「Apple Payは対象」の付記まで出る
        assertTrue(mufg.warnings.any { it.contains("Google Pay") && it.contains("Apple Payは対象") })
    }

    // ---- JCB J-POINT パートナー(#52。card_program の店舗別レート) ----

    @Test
    fun `実データ_JPOINTパートナーは店舗別レートで判定される`() {
        // カタログ直パース(未マージ)は S・1pt=1円相当(rateBonus 0・通貨価値係数 1)で収録値がそのまま出る
        val seven = data.merchants.first { it.id == "seven_eleven" }
        val sevenJcb = engine.judgeCards(seven, today).first { it.campaign.id == "jcb_jpoint_partner" }
        assertEquals(1.5, sevenJcb.effectiveRate!!, 0.001)
        val gusto = data.merchants.first { it.id == "gusto" }
        val gustoJcb = engine.judgeCards(gusto, today).first { it.campaign.id == "jcb_jpoint_partner" }
        assertEquals(10.0, gustoJcb.effectiveRate!!, 0.001)
        // 施策全体ビュー専用の注記(収録範囲の説明。overview_ineligible_notes)は店舗判定カードに
        // 混ぜない(#52): マクドナルドの判定を見るユーザーに「低還元率のお店は非表示」は無関係な情報
        assertTrue(gustoJcb.campaign.overviewIneligibleNotes.isNotEmpty())
        gustoJcb.ineligibleNotes.forEach { note ->
            assertTrue("店舗判定に overview 注記が混入: $note", note !in gustoJcb.campaign.overviewIneligibleNotes)
        }
    }

    @Test
    fun `実データ_店舗別レートを持つcard_programはrate_baseが最大値で全ルールに率がある`() {
        // 登録規則(#52): card_program で店舗別レートを使うなら全ルールに rate_override を書き
        // (省略するとその店だけカードの最大実効率で表示され誤り)、rate_base はその最大値にする
        // (effective_rate_default と一致し「最大○%」表示・スケール計算の基準になる)
        data.campaigns
            .filter { it.campaignType == CampaignType.CARD_PROGRAM }
            .filter { c -> c.merchantRules.any { it.rateOverride != null } }
            .forEach { c ->
                c.merchantRules.forEach { r ->
                    assertNotNull("${c.id}/${r.merchantId}: 店舗別レート施策の全ルールに rate_override が必要", r.rateOverride)
                }
                assertEquals(
                    "${c.id}: rate_base は rate_override の最大値であること",
                    c.merchantRules.maxOf { it.rateOverride!! },
                    c.rateBase,
                )
            }
    }

    @Test
    fun `実データ_JPOINTのカタログ既定率はrate_baseと一致する`() {
        val jcbCard = data.cards.first { it.id == "jcb_original" }
        val jcbCampaign = data.campaigns.first { it.id == "jcb_jpoint_partner" }
        // effective_rate_default = 最大レート店の収録値。ずれると一覧の「最大○%」と判定の率が食い違う
        assertEquals(jcbCampaign.rateBase!!, jcbCard.effectiveRateDefault!!, 0.0)
        // クラスは保守側(加算の小さい方)を先頭にする(未選択時の既定)
        assertTrue(jcbCard.cardClasses.size >= 2)
        assertEquals(jcbCard.cardClasses.minOf { it.rateBonus }, jcbCard.cardClasses.first().rateBonus, 0.0)
    }

    @Test
    fun `実データ_1pt価値は通貨単位で定義されカード側にpoint_valueは無い`() {
        // #13: J-POINT の 1pt 価値をカード単位(cards[].point_value)から通貨単位
        // (point_currencies[].point_value)へ移設。jcb_original は point_currency_id で
        // j_point を参照し、カード側の point_value は廃止される
        val jcb = data.cards.first { it.id == "jcb_original" }
        assertEquals("j_point", jcb.pointCurrencyId)
        val jpoint = data.pointCurrencies.first { it.id == "j_point" }
        assertNotNull(jpoint.pointValueConfig)
        assertEquals(1.0, jpoint.pointValueConfig!!.default, 0.0)
        // 全カードでカード単位の point_value が廃止されていること。モデルから
        // PaymentCard.point_value を消したため、旧位置のキーは ignoreUnknownKeys で黙って
        // 捨てられる(静かに壊れる)ので構造で検出する
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
    fun `実データ_エポス優待は提示と決済が分離され割引は最良比較から外れる`() {
        // #59: エポス優待は「提示のみ」と「決済条件付き」を別施策で収録する(#58 の分離ルール)。
        // 割引はルーム料金等の部分料金に限定されるため product_scope を持ち、bestOption に載らない
        val eposCampaigns = data.campaigns.filter { it.cardId == "epos" }
        assertTrue(eposCampaigns.isNotEmpty())
        eposCampaigns.filter { it.benefitType == "discount" }.forEach { c ->
            assertNotNull("${c.id}: 部分料金への割引優待は product_scope を持つこと", c.productScope)
        }
        // ビッグエコー: 提示30%OFF と決済コース10%OFF が別施策として両方判定に出る
        val bigEcho = data.merchants.first { it.id == "big_echo" }
        val judgments = engine.judgeCards(bigEcho, today)
        val presentation = judgments.first { it.campaign.id == "epos_yutai_presentation" }
        assertTrue(presentation.campaign.presentationOnly)
        // 提示施策はカードの実効率(2.5%)でなく施策側の率(rate_override)を出す(#80)
        assertEquals(30.0, presentation.effectiveRate!!, 0.0)
        val course = judgments.first { it.campaign.id == "epos_yutai_bigecho_course" }
        assertFalse(course.campaign.presentationOnly)
        assertEquals(BenefitType.DISCOUNT, course.benefitType)
        // 決済型 discount card_program でも rate_override が実効率になる(カードの2.5%が出ない)
        assertEquals(10.0, course.effectiveRate!!, 0.0)
        // 施策全体ビュー(おトクタブ。店舗指定なし)でもカードのカタログ既定値(2.5%)でなく
        // 施策の最大値が出る(#59 実機フィードバック: カラオケ館 30% OFF が 2.5% 表示になっていた)
        val eposCard = data.cards.first { it.id == "epos" }
        val karaokekan = data.campaigns.first { it.id == "epos_yutai_karaokekan" }
        assertEquals(30.0, resolveCardCampaignRate(karaokekan, eposCard).effectiveRate!!, 0.0)
        val monteroza = data.campaigns.first { it.id == "epos_yutai_monteroza" }
        assertEquals(2.5, resolveCardCampaignRate(monteroza, eposCard).effectiveRate!!, 0.0)
    }

    @Test
    fun `実データ_OWNDAYSは網羅リストで掲載店だけ対象になる`() {
        val owndays = data.merchants.first { it.id == "owndays" }
        // 網羅リストのみのチェーンでも「このお店が対象か調べる」導線を出す(#70)
        assertTrue(engine.canCheckStore(owndays))
        // YOLP のデータセット自体に OWNDAYS がほぼ無い実測(#52)に基づく地図注記(#70 施策3)
        assertFalse(owndays.yolpCoverageNote.isNullOrBlank())
        assertEquals(
            StoreEligibility.ELIGIBLE,
            engine.checkStore(owndays, "OWNDAYS 池袋西口店").single().eligibility,
        )
        // 掲載のない店舗は対象外と断定され、施策単位で間引かれる
        assertEquals(
            StoreEligibility.INELIGIBLE,
            engine.checkStore(owndays, "OWNDAYS 架空モール店").single().eligibility,
        )
        assertEquals(
            setOf("jcb_jpoint_partner"),
            engine.exhaustiveListIneligibleCampaignIds(owndays, "OWNDAYS 架空モール店"),
        )
        // YOLP の実 POI 名は「オンデーズ」表記(2026-08 実測)。カナ表記でもチェーン照合でき、
        // 網羅リスト(店名はブランド名抜きで収録)にも一致する
        assertEquals("owndays", engine.matchStore("オンデーズ ナイン秋葉原ラジオ会館店")?.merchant?.id)
        assertEquals(
            StoreEligibility.ELIGIBLE,
            engine.checkStore(owndays, "オンデーズ ナイン秋葉原ラジオ会館店").single().eligibility,
        )
        // 本社 POI(株式会社オンデーズ)もチェーンに照合されるが、掲載なし=対象外で自動的に間引かれる
        assertEquals("owndays", engine.matchStore("株式会社オンデーズ上野マルイ店")?.merchant?.id)
        assertEquals(
            setOf("jcb_jpoint_partner"),
            engine.exhaustiveListIneligibleCampaignIds(owndays, "株式会社オンデーズ"),
        )
    }

    /**
     * 東京靴流通センターの沖縄県限定網羅リスト施策(#70 で「近くの対象のお店を探す」に本土の
     * 非対象店が並んだバグの再現データ)。施策が期限切れ削除されたら検証対象なしで抜ける。
     */
    @Test
    fun `実データ_東京靴流通センターの沖縄網羅リストは未掲載店で施策単位に間引かれる`() {
        val campaign = data.campaigns.firstOrNull { it.id == "aupay_chiyoda_okinawa_coupon_2026_08" } ?: return
        val merchant = data.merchants.first { it.id == "tokyo_kutsu_ryutsu_center" }
        // 沖縄の掲載店は対象
        assertEquals(
            StoreEligibility.ELIGIBLE,
            engine.checkStore(merchant, "東京靴流通センター 泡瀬店").single().eligibility,
        )
        // 本土の店は掲載なし=対象外と断定され、施策単位で間引かれる
        // (地図はブリッジ(チェーン絞り込み)中でもこの店を出さない)
        assertEquals(
            setOf(campaign.id),
            engine.exhaustiveListIneligibleCampaignIds(merchant, "東京靴流通センター 王子店"),
        )
    }

    @Test
    fun `実データ_JPOINT専用チェーンは未所有だとYOLP検索対象に入らない`() {
        // jcb_original を未所有にすると、J-POINT だけが参照するチェーン(OWNDAYS 等)は
        // 判定に出ない = YOLP 検索(keyword ソース)からも外れる
        assertTrue("owndays" in engine.activeManagedMerchantIds(today))
        val withoutJcb = JudgmentEngine(data.copy(cards = data.cards.filter { it.id != "jcb_original" }))
        assertFalse("owndays" in withoutJcb.activeManagedMerchantIds(today))
        // 他施策(SMCC/MUFG)が参照するチェーンは残る
        assertTrue("seven_eleven" in withoutJcb.activeManagedMerchantIds(today))
    }

    @Test
    fun `実データ_常設施策はcard_program_managed`() {
        data.campaigns.filter { it.campaignType == CampaignType.CARD_PROGRAM }.forEach { c ->
            assertTrue("${c.id}: card_program should be managed", c.storeScope == StoreScope.MANAGED)
            assertNull("${c.id}: card_program should not have period_end", c.periodEnd)
        }
    }

    @Test
    fun `実データ_自治体施策はmunicipal_external`() {
        val municipal = data.campaigns.filter { it.campaignType == CampaignType.MUNICIPAL }
        assertTrue("自治体施策が1件以上存在する", municipal.isNotEmpty())
        municipal.forEach { c ->
            assertTrue("${c.id}: municipal should be external", c.storeScope == StoreScope.EXTERNAL)
            assertNotNull("${c.id}: municipal should have region", c.region)
            assertNotNull("${c.id}: municipal should have period_start", c.periodStart)
            // 終了日は明示されるか、未定なら早期終了型(予算上限到達で終了=かなトク等)であること
            assertTrue(
                "${c.id}: municipal should have period_end or be may_end_early",
                c.periodEnd != null || c.mayEndEarly,
            )
            assertNotNull("${c.id}: municipal should have payment_method_id", c.paymentMethodId)
            assertTrue("${c.id}: municipal merchant_rules should be empty", c.merchantRules.isEmpty())
        }
    }

    @Test
    fun `実データ_rate_rulesがある施策はrate_baseがその最大値`() {
        // 段階制(中小20%/大手10%等)の登録規則: 全条件を rate_rules に列挙し、
        // rate_base にはその最大値を入れる(表示は「最大○%」)。AI 収集時の登録ゆれをここで検出する
        data.campaigns.filter { it.rateRules.isNotEmpty() }.forEach { c ->
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
    fun `実データ_min_purchase_scopeとproduct_scopeが整合している`() {
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
    }

    @Test
    fun `実データ_display_nameは空白でなく自治体施策には持たせない`() {
        data.campaigns.forEach { c ->
            c.displayName?.let { dn ->
                assertTrue("${c.id}: display_name が空文字・空白", dn.isNotBlank())
                // 自治体は region タイトル固定で display_name を参照しない(登録しても表示されない)
                assertTrue(
                    "${c.id}: municipal は display_name を持たせない",
                    c.campaignType != CampaignType.MUNICIPAL,
                )
            }
        }
    }

    @Test
    fun `実データ_同一自治体の複数決済手段がマージ可能`() {
        val municipal = data.campaigns.filter { it.campaignType == CampaignType.MUNICIPAL }
        val grouped = municipal.groupBy { it.region?.name }
        val multiProvider = grouped.filter { it.value.size > 1 }
        assertTrue("複数決済手段の自治体施策が存在する", multiProvider.isNotEmpty())
        multiProvider.forEach { (name, campaigns) ->
            val providers = campaigns.map { it.paymentMethodId }.distinct()
            assertEquals("$name: 各レコードは異なる決済手段", campaigns.size, providers.size)
        }
    }

    @Test
    fun `実データ_promotionはscopeに応じて期間とmerchant_rulesを持つ`() {
        // managed: 特定チェーン対象(お店/地図タブの判定に出す)。merchant_rules 書き忘れで
        // 「判定に一切出ない死にデータ」になるのを防ぐため、期間と merchant_rules を強制する。
        // external: 全加盟店対象(抽選型等。チェーンを列挙できない)。おトクタブ専用で判定エンジンの
        // 対象外なので merchant_rules は持たせず(持っていても判定に出ず誤解のもと)、期間も任意
        // (常設の抽選会等は period 無し)。#44
        val promotions = data.campaigns.filter { it.campaignType == CampaignType.PROMOTION }
        assertTrue("promotion が1件以上存在する", promotions.isNotEmpty())
        promotions.forEach { c ->
            when (c.storeScope) {
                StoreScope.MANAGED -> {
                    assertNotNull("${c.id}: managed promotion should have period_start", c.periodStart)
                    assertNotNull("${c.id}: managed promotion should have period_end", c.periodEnd)
                    assertTrue("${c.id}: managed promotion should have merchant_rules", c.merchantRules.isNotEmpty())
                }
                StoreScope.EXTERNAL -> {
                    assertTrue(
                        "${c.id}: external promotion should not have merchant_rules",
                        c.merchantRules.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `実データ_おトクタブ用_6月30日にactiveとupcomingが存在する`() {
        val june30 = LocalDate.of(2026, 6, 30)
        val active = engine.activeCampaigns(june30).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        val upcoming = engine.upcomingCampaigns(june30).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        assertTrue("6/30にactiveまたはupcomingが存在する", active.isNotEmpty() || upcoming.isNotEmpty())
    }

    @Test
    fun `実データ_おトクタブ用_7月1日にactive campaignsが存在する`() {
        val july1 = LocalDate.of(2026, 7, 1)
        val timeLimited = engine.activeCampaigns(july1).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        assertTrue("time-limited active not empty on 7/1: ${timeLimited.map { it.id }}", timeLimited.isNotEmpty())
    }

    @Test
    fun `実データ_QRなしカタログでもupcomingCampaignsは動く`() {
        val noQrEngine = JudgmentEngine(data.copy(qrPayments = emptyList()))
        val june30 = LocalDate.of(2026, 6, 30)
        val upcoming = noQrEngine.upcomingCampaigns(june30).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        assertTrue("upcoming should work without QR payments: ${upcoming.map { it.id }}", upcoming.isNotEmpty())
    }

    @Test
    fun `実データ_カードブランドカタログが読み込めていて施策の参照先がある`() {
        assertTrue("card_brands が空", data.cardBrands.isNotEmpty())
        data.campaigns.mapNotNull { it.cardBrand }.forEach { brand ->
            assertTrue(
                "card_brand '$brand' がカタログの card_brands に無い(設定画面で登録できない)",
                data.cardBrands.any { it.name.equals(brand, ignoreCase = true) },
            )
        }
    }

    @Test
    fun `実データ_merchant_rulesのineligible_brandsがカタログのcard_brandsを参照している`() {
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
    fun `実データ_カテゴリ一覧は「その他」が末尾`() {
        // カテゴリチップの並びはデータ定義順だが、雑多な「その他」だけは常に末尾へ送る
        // (ファッション等のカテゴリ追加で「その他」が列の途中に挟まらないように)
        assertEquals("その他", engine.categories.last())
        assertEquals(engine.categories.toSet().size, engine.categories.size)
    }

    @Test
    fun `実データ_QR決済カタログが読み込めている`() {
        val qr = data.qrPayments
        assertTrue(qr.isNotEmpty())
        assertTrue(qr.any { it.id == "paypay" })
        assertTrue(qr.any { it.id == "aupay" })
        assertTrue(qr.any { it.id == "dpay" })
        assertTrue(qr.any { it.id == "rakuten_pay" })
    }

    @Test
    fun `実データ_yolpConfigが読み込めている`() {
        val config = data.yolpConfig
        assertNotNull(config)
        assertEquals(5, config!!.gcGroups.size)
        assertEquals("0123,0115,0101013", config.gcGroups[0].gc)
        assertEquals("0205", config.gcGroups[1].gc)
        assertEquals("0202001", config.gcGroups[2].gc)
        // エポス優待(#59)で追加した居酒屋(モンテローザ系)とカラオケ
        assertEquals("0110", config.gcGroups[3].gc)
        assertEquals("0124002", config.gcGroups[4].gc)
    }

    @Test
    fun `実データ_keyword検索のmerchantが正しく設定されている`() {
        val keywordMerchants = data.merchants.filter { it.yolpSearch == "keyword" }
        val keywordIds = keywordMerchants.map { it.id }.toSet()
        assertTrue("curves" in keywordIds)
        assertTrue("akachan_honpo" in keywordIds)
        assertTrue("ok_store" in keywordIds)
        assertTrue("pizza_hut" in keywordIds)
        assertTrue("ueshima_coffee" in keywordIds)
        assertTrue("hamazushi" in keywordIds)
    }

    @Test
    fun `実データ_coke_onはyolp_search_none`() {
        val cokeOn = data.merchants.first { it.id == "coke_on" }
        assertEquals("none", cokeOn.yolpSearch)
    }

    @Test
    fun `実データ_gc検索のmerchantはデフォルトのgc`() {
        val gcMerchants = data.merchants.filter { it.yolpSearch == "gc" }
        assertTrue(gcMerchants.any { it.id == "seven_eleven" })
        assertTrue(gcMerchants.any { it.id == "mcdonalds" })
        assertTrue(gcMerchants.any { it.id == "gusto" })
    }
}
